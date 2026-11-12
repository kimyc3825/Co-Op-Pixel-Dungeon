// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

final class DungeonSession implements AutoCloseable {
    enum Connection {
        LOCAL(""), RELAY("중계 연결"), DIRECT("P2P 직접 연결");

        final String label;
        Connection(String label) { this.label = label; }
    }

    record Frame(InnApi.DungeonState state, long hostAtReceipt, long receivedAt,
                 String notice, Connection connection) {
        long serverTime() {
            if (state == null) return 0;
            return state.pausedUntil() > 0 ? state.now()
                : state.now() + Math.max(0, wallTime() - state.wallNow());
        }

        long wallTime() {
            return state == null ? 0 : hostAtReceipt + elapsedSince(receivedAt);
        }
    }

    private record Command(long sequence, InnApi.Action action) {}
    private record Wire(String type, long sequence, List<Command> commands, InnApi.DungeonState state) {}
    private record Packet(long sequence, String message) {}

    private static final long RELAY_SYNC_MS = 165;
    private static final long DIRECT_SYNC_MS = 2_500;
    private static final long STATE_HEARTBEAT_MS = 650;
    private static final long DIRECT_TIMEOUT_MS = 3_500;
    private static final long COMMAND_RETRY_MS = 275;
    private static final long ENTRY_TIMEOUT_MS = 16_000;
    private static final long HOST_TIMEOUT_MS = 14_000;
    private static final int MAX_MESSAGE_LENGTH = 24 * 1024;
    private static final int MAX_COMMANDS = 16;

    private final InnApi api;
    private final String key;
    private final String matchId;
    private final InnApi.Person practicePlayer;
    private final String theme;
    private final boolean friendlyFire;
    private final BiConsumer<DungeonSession, Frame> listener;
    private final ScheduledThreadPoolExecutor network = new ScheduledThreadPoolExecutor(1,
        task -> new Thread(task, "dungeon-link"));
    private final ArrayBlockingQueue<InnApi.Action> pending = new ArrayBlockingQueue<>(MAX_COMMANDS);
    private final Gson gson = new Gson();
    private final long startedAt = System.nanoTime();
    private final long epoch = System.currentTimeMillis();
    private volatile boolean closed;

    // All battle and transport state below is confined to the dungeon executor.
    private InnApi.LinkState link;
    private HostBattle battle;
    private PeerTransport peer;
    private boolean host;
    private boolean terminal;
    private boolean wasDirect;
    private boolean syncInFlight;
    private boolean signalInFlight;
    private boolean relayInFlight;
    private long nextSyncAt;
    private long nextSignalAt;
    private long nextRelayAt;
    private long relayReceived;
    private long lastSnapshotAt;
    private long lastSnapshotSequence;
    private long nextHeartbeatAt;
    private long directSince;
    private long lastDirectPacketAt;
    private long nextPingAt;
    private long nextCommandAt;
    private long packetSequence;
    private long commandSequence;
    private final ArrayDeque<Command> unackedCommands = new ArrayDeque<>(MAX_COMMANDS);
    private Packet relayPending;
    private Frame frame;

    DungeonSession(InnApi api, String key, String matchId, InnApi.Person practicePlayer,
                   BiConsumer<DungeonSession, Frame> listener) {
        this(api, key, matchId, practicePlayer, DungeonLayout.SEWERS, listener);
    }

    DungeonSession(InnApi api, String key, String matchId, InnApi.Person practicePlayer, String theme,
                   BiConsumer<DungeonSession, Frame> listener) {
        this(api, key, matchId, practicePlayer, theme, false, listener);
    }

    DungeonSession(InnApi api, String key, String matchId, InnApi.Person practicePlayer, String theme,
                   boolean friendlyFire, BiConsumer<DungeonSession, Frame> listener) {
        this.api = api;
        this.key = key;
        this.matchId = matchId;
        this.practicePlayer = practicePlayer;
        this.theme = DungeonLayout.themeOrDefault(theme);
        this.friendlyFire = friendlyFire;
        this.listener = listener;
        network.execute(this::enter);
        network.scheduleAtFixedRate(this::tick, 0, 25, TimeUnit.MILLISECONDS);
    }

    private void enter() {
        if (closed) return;
        if (practicePlayer != null) {
            host = true;
            battle = new HostBattle(UUID.randomUUID().toString(), practicePlayer, null, now(), seed(), theme, friendlyFire);
            tick();
            return;
        }
        api.enterLink(key, matchId).whenComplete((next, error) -> {
            if (closed && error == null) {
                api.leaveLink(key, matchId);
                return;
            }
            submit(() -> {
                if (closed || terminal) return;
                if (error == null) acceptLink(next);
                else finish("파티 던전에 입장하지 못했습니다. 여관에서 다시 시도해 주세요.");
            });
        });
    }

    void action(InnApi.Action action) {
        if (!closed) pending.offer(action);
    }

    private void tick() {
        if (closed || terminal) return;
        long now = now();
        if (frame == null && elapsedSince(startedAt) >= ENTRY_TIMEOUT_MS) {
            finish("동료와 던전 연결을 열지 못했습니다. 여관에서 다시 시도해 주세요.");
            return;
        }
        if (practicePlayer != null) {
            if (battle != null) advanceHost(now, false);
            return;
        }
        if (link == null) return;
        checkDirectLiveness(now);
        boolean direct = direct();
        boolean switched = direct != wasDirect;
        if (switched) {
            wasDirect = direct;
            nextSyncAt = 0;
            nextCommandAt = 0;
            nextPingAt = 0;
            relayPending = null;
            if (frame != null) publish(frame.state(), frame.hostAtReceipt(), frame.receivedAt(), frame.notice());
        }
        if (!syncInFlight && now >= nextSyncAt) sync(now, direct);
        if (!link.paired()) return;
        publishSignal(now);
        if (host) {
            advanceHost(now, switched || now >= nextHeartbeatAt);
        } else {
            if (direct && now >= nextPingAt) {
                peer.send(gson.toJson(new Wire("ping", 0, null, null)));
                nextPingAt = now + STATE_HEARTBEAT_MS;
            }
            if (frame != null && now - lastSnapshotAt >= HOST_TIMEOUT_MS) {
                finish("파티장과의 연결이 끊겼습니다.");
                return;
            }
            sendGuestCommands(now);
        }
        flushRelay(now);
    }

    private void advanceHost(long now, boolean sendHeartbeat) {
        battle.advance(now);
        for (InnApi.Action action; (action = pending.poll()) != null; ) {
            battle.input(selfId(), ++commandSequence, action, now);
        }
        boolean changed = frame == null || frame.state().revision() != battle.revision();
        if (changed) publish(battle.snapshot(selfId(), now), now, System.nanoTime(), "");
        if (link != null && (changed || sendHeartbeat)) {
            InnApi.DungeonState snapshot = battle.snapshot(link.partnerId(), now);
            long sequence = ++packetSequence;
            transmit(packet(new Wire("state", sequence, null, snapshot)));
            nextHeartbeatAt = now + STATE_HEARTBEAT_MS;
        }
    }

    private void sendGuestCommands(long now) {
        for (InnApi.Action action; (action = pending.poll()) != null; ) {
            if (frame == null || !maySend(action, frame.state())) continue;
            if (unackedCommands.size() == MAX_COMMANDS) unackedCommands.removeFirst();
            unackedCommands.addLast(new Command(++commandSequence, action));
            nextCommandAt = 0;
        }
        if (!unackedCommands.isEmpty() && now >= nextCommandAt) {
            // Each relay slot carries the full unacknowledged tail, preserving inputs when it is replaced.
            transmit(packet(new Wire("input", unackedCommands.getLast().sequence(), List.copyOf(unackedCommands), null)));
            nextCommandAt = now + COMMAND_RETRY_MS;
        }
    }

    private static boolean maySend(InnApi.Action action, InnApi.DungeonState state) {
        String status = state.status();
        if ("chat".equals(action.type())) return !"closed".equals(status);
        if ("pause".equals(action.type())) return "active".equals(status) || "won".equals(status);
        if (state.pausedUntil() > 0) return false;
        return "active".equals(status) ||
            "won".equals(status) && ("move".equals(action.type()) || "eat".equals(action.type()));
    }

    private void sync(long now, boolean direct) {
        syncInFlight = true;
        nextSyncAt = now + (direct ? DIRECT_SYNC_MS : RELAY_SYNC_MS);
        api.syncLink(key, matchId).whenComplete((next, error) -> submit(() -> {
            syncInFlight = false;
            if (closed || terminal) return;
            if (error == null) {
                acceptLink(next);
            } else if (linkEnded(error) && !direct()) {
                finish("파티 연결이 종료되었습니다.");
            } else {
                nextSyncAt = Math.max(nextSyncAt, now() + 350);
            }
        }));
    }

    private void acceptLink(InnApi.LinkState next) {
        if (!matchId.equals(next.id())) {
            finish("파티 연결 정보를 확인할 수 없습니다.");
            return;
        }
        if (next.closed()) {
            finish("동료가 원정을 종료했습니다.");
            return;
        }
        boolean paired = link != null && link.paired();
        link = next;
        if (!next.paired()) return;
        if (!paired) {
            host = "host".equals(next.role());
            if (host) {
                battle = new HostBattle(next.id(), person(next.selfId(), next.selfName()),
                    person(next.partnerId(), next.partnerName()), now(), seed(), theme, friendlyFire);
            }
            openPeer();
        }
        if (peer != null && next.signal() != null) peer.applyRemote(next.signal());
        if (next.message() != null && next.messageSeq() > relayReceived) {
            relayReceived = next.messageSeq();
            receive(next.message());
        }
    }

    private void openPeer() {
        if (Boolean.getBoolean("noxac.inn.relayOnly")) return;
        try {
            String stun = System.getProperty("noxac.inn.stun", "stun:stun.l.google.com:19302");
            peer = new PeerTransport(host, stun, this::submit, message -> {
                lastDirectPacketAt = now();
                receive(message);
            });
        } catch (RuntimeException | LinkageError unavailable) {
            // The relay uses the same battle protocol on systems without the WebRTC runtime.
        }
    }

    private void publishSignal(long now) {
        if (peer == null || signalInFlight || now < nextSignalAt) return;
        InnApi.Signal signal = peer.pendingLocal();
        if (signal == null) return;
        signalInFlight = true;
        nextSignalAt = now + 450;
        api.signalLink(key, matchId, signal).whenComplete((unused, error) -> submit(() -> {
            signalInFlight = false;
            if (!closed && !terminal && error == null) peer.markPublished();
        }));
    }

    private boolean direct() {
        return peer != null && peer.connected();
    }

    private void checkDirectLiveness(long now) {
        if (!direct()) {
            directSince = 0;
            return;
        }
        if (directSince == 0) directSince = now;
        // An open data channel can outlive a broken network route.
        if (now - Math.max(directSince, lastDirectPacketAt) >= DIRECT_TIMEOUT_MS) peer.close();
    }

    private void transmit(Packet packet) {
        if (direct() && peer.send(packet.message())) {
            relayPending = null;
        } else {
            relayPending = packet;
        }
    }

    private void flushRelay(long now) {
        if (relayPending == null || relayInFlight || now < nextRelayAt) return;
        Packet packet = relayPending;
        relayPending = null;
        relayInFlight = true;
        api.relayLink(key, matchId, packet.sequence(), packet.message()).whenComplete((unused, error) -> submit(() -> {
            relayInFlight = false;
            if (closed || terminal || error == null) return;
            if (linkEnded(error) && !direct()) {
                finish("파티 연결이 종료되었습니다.");
            } else if (relayPending == null && !direct()) {
                relayPending = packet;
                nextRelayAt = now() + 250;
            }
        }));
    }

    private void receive(String message) {
        if (closed || terminal || link == null || !link.paired() || message.length() > MAX_MESSAGE_LENGTH) return;
        Wire wire;
        try {
            wire = gson.fromJson(message, Wire.class);
        } catch (JsonParseException invalid) {
            return;
        }
        if (wire == null) return;
        if ("leave".equals(wire.type())) {
            finish("동료가 원정을 종료했습니다.");
        } else if (host && "input".equals(wire.type()) && wire.commands() != null &&
            !wire.commands().isEmpty() && wire.commands().size() <= MAX_COMMANDS) {
            long now = now();
            for (Command command : wire.commands()) {
                if (command != null && command.action() != null && command.sequence() > 0 &&
                    command.sequence() <= wire.sequence()) {
                    battle.input(link.partnerId(), command.sequence(), command.action(), now);
                }
            }
            advanceHost(now, false);
        } else if (!host && "state".equals(wire.type()) && wire.sequence() > lastSnapshotSequence &&
            validSnapshot(wire.state())) {
            lastSnapshotSequence = wire.sequence();
            acceptSnapshot(wire.state());
        }
    }

    private boolean validSnapshot(InnApi.DungeonState state) {
        return state != null && matchId.equals(state.id()) && link.selfId().equals(state.selfId()) &&
            (frame == null || state.revision() >= frame.state().revision() &&
                state.theme().equals(frame.state().theme()) && state.friendlyFire() == frame.state().friendlyFire()) &&
            state.map() != null && !state.map().isEmpty() && state.map().getFirst() != null &&
            !state.map().getFirst().isEmpty() &&
            state.map().stream().allMatch(row -> row != null && row.length() == state.map().getFirst().length()) &&
            state.actors() != null && state.actors().stream().allMatch(actor ->
                actor != null && actor.id() != null && actor.name() != null && actor.kind() != null &&
                    Double.isFinite(actor.energy()) && actor.rations() >= 0) &&
            state.actors().stream().anyMatch(actor -> state.selfId().equals(actor.id())) &&
            state.drops() != null && state.drops().stream().allMatch(drop ->
                drop != null && drop.id() != null && drop.item() != null) &&
            state.messages().size() <= 16 && state.messages().stream().allMatch(message ->
                message != null && message.actorId() != null && message.name() != null && message.text() != null) &&
            state.attackEvents().size() <= 16 && state.attackEvents().stream().allMatch(event ->
                event != null && event.id() > 0 && event.at() >= 0 && event.actorId() != null && event.amount() >= 0 &&
                    ("attack".equals(event.action()) || "shoot".equals(event.action())) &&
                    validAttackResult(event.result()) && mapCell(state, event.x(), event.y())) &&
            state.projectiles().size() <= 8 && state.projectiles().stream().allMatch(projectile ->
                projectile != null && projectile.id() > 0 && projectile.ownerId() != null && projectile.stepAt() >= 0 &&
                    mapCell(state, projectile.x(), projectile.y()) &&
                    mapCell(state, projectile.nextX(), projectile.nextY()) &&
                    Math.max(Math.abs(projectile.nextX() - projectile.x()), Math.abs(projectile.nextY() - projectile.y())) == 1) &&
            ("active".equals(state.status()) || "won".equals(state.status()) || "lost".equals(state.status()));
    }

    private static boolean validAttackResult(String result) {
        return result != null && switch (result) {
            case "hit", "out_of_reach", "blocked", "target_gone", "miss", "ally_blocked" -> true;
            default -> false;
        };
    }

    private static boolean mapCell(InnApi.DungeonState state, int x, int y) {
        return y >= 0 && y < state.map().size() && x >= 0 && x < state.map().get(y).length();
    }

    private void acceptSnapshot(InnApi.DungeonState next) {
        lastSnapshotAt = now();
        unackedCommands.removeIf(command -> command.sequence() <= next.commandAck());
        if (relayPending != null && relayPending.sequence() <= next.commandAck()) relayPending = null;
        long receipt = System.nanoTime();
        long hostTime = next.wallNow();
        if (frame != null) hostTime = Math.max(hostTime, frame.wallTime());
        InnApi.DungeonState changed = frame != null && next.revision() == frame.state().revision() ? frame.state() : next;
        publish(changed, hostTime, receipt, "");
    }

    private void finish(String notice) {
        terminal = true;
        if (frame == null) {
            publish(null, 0, 0, notice);
        } else {
            InnApi.DungeonState state = frame.state();
            if ("active".equals(state.status()) || "won".equals(state.status())) {
                long time = frame.serverTime();
                state = new InnApi.DungeonState(state.id(), state.selfId(), time, state.map(), state.actors(),
                    null, "closed", false, state.revision(), state.drops(), state.commandAck(), false, state.theme(),
                    frame.wallTime(), 0, state.pauseReadyAt(), state.messages(), state.friendlyFire(), state.attackEvents(), List.of());
            }
            publish(state, frame.hostAtReceipt(), frame.receivedAt(), notice);
        }
        if (peer != null) peer.close();
    }

    private void publish(InnApi.DungeonState state, long hostTime, long receipt, String notice) {
        if (closed) return;
        Connection connection = practicePlayer != null ? Connection.LOCAL :
            direct() ? Connection.DIRECT : Connection.RELAY;
        frame = new Frame(state, hostTime, receipt, notice, connection);
        listener.accept(this, frame);
    }

    private Packet packet(Wire wire) {
        return new Packet(wire.sequence(), gson.toJson(wire));
    }

    private String selfId() {
        return practicePlayer == null ? link.selfId() : practicePlayer.id();
    }

    private static InnApi.Person person(String id, String name) {
        return new InnApi.Person(id, name, 0, 0, false);
    }

    private static long seed() {
        return ThreadLocalRandom.current().nextLong();
    }

    private long now() {
        return epoch + elapsedSince(startedAt);
    }

    private static long elapsedSince(long nanoTime) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanoTime);
    }

    private static boolean linkEnded(Throwable error) {
        if (error instanceof CompletionException) error = error.getCause();
        return error instanceof InnApi.Problem problem && (problem.status == 404 || problem.status == 410);
    }

    private void submit(Runnable task) {
        try {
            network.execute(task);
        } catch (RejectedExecutionException ignored) {
            // A native callback or network reply can arrive during shutdown.
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        submit(() -> {
            if (battle != null) battle.close(now());
            if (peer != null) {
                if (direct()) peer.send(gson.toJson(new Wire("leave", 0, null, null)));
                peer.close();
            }
            if (link != null) api.leaveLink(key, matchId);
        });
        network.shutdown();
    }
}
