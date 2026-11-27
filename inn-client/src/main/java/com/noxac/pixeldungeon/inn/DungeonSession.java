// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The party link of one match (PROTOCOL.md §6–§7): starts the original-engine party run and relays
 * InnOriginalParty packet strings in order, unchanged, in both directions. Every field below is
 * confined to the "party-link" thread.
 */
final class DungeonSession implements AutoCloseable {
    interface OriginalListener {
        /** Both players are connected: leave the inn view (step 1 of §7.4). */
        void paired(DungeonSession session);
        /** Start the dungeon; call {@link #originalConfigured()} once it can receive packets. */
        void start(DungeonSession session, InnApi.Link link, boolean host, long seed, boolean friendlyFire);
        void receive(DungeonSession session, String payload);
        void end(DungeonSession session, String notice);
    }

    /** From the match to `paired` (the web client uses the same limits). */
    private static final long ENTRY_TIMEOUT_MS = 20_000;
    /** From `paired` until both sides are ready. */
    private static final long START_TIMEOUT_MS = 150_000;
    /** The server ends a run 20 s after a socket drops; stop retrying a little earlier (§9). */
    private static final long RECONNECT_WINDOW_MS = 15_000;
    private static final long[] BACKOFF_MS = {500, 1_000, 2_000, 4_000, 8_000};
    static final int MAX_PAYLOAD_BYTES = 24 * 1024;
    private static final int MAX_BUFFERED = 512;

    private final InnApi api;
    private final String key;
    private final String matchId;
    private final String role;
    private final OriginalListener listener;
    private final ScheduledThreadPoolExecutor network = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "party-link");
        thread.setDaemon(true);
        return thread;
    });
    private final ArrayDeque<String> inbound = new ArrayDeque<>();
    private final InnChannel.Closing closing = new InnChannel.Closing();
    private volatile boolean closed;

    private InnChannel channel;
    /** The current channel received its `link`; until then only `hello` may be sent on it. */
    private boolean linked;
    private InnApi.Link link;
    private boolean paired;
    private boolean peerReady;
    private boolean launched;
    private boolean ready;
    private boolean terminal;
    private long deadline;
    private long lostAt;
    private int attempt;
    private String lastError;
    private ScheduledFuture<?> reconnect;

    /** @param role the role the inn's `matched` frame announced; the link must agree. */
    DungeonSession(InnApi api, String key, String matchId, String role, OriginalListener listener) {
        this.api = api;
        this.key = key;
        this.matchId = matchId;
        this.role = role;
        this.listener = listener;
        deadline = now() + ENTRY_TIMEOUT_MS;
        submit(this::connect);
        network.scheduleWithFixedDelay(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    /** The local dungeon has started and can receive packets: send `ready` (steps 2–3 of §7.4). */
    void originalConfigured() {
        submit(() -> {
            if (terminal || !launched || ready) return;
            ready = true;
            deadline = now() + START_TIMEOUT_MS;
            // Otherwise acceptLink sends it once the (re)connected channel is linked.
            if (linked) channel.send(InnApi.frame("ready"));
            while (!inbound.isEmpty()) listener.receive(this, inbound.removeFirst());
        });
    }

    /**
     * Sends one InnOriginalParty packet. Packets are delivered in the order given; none replaces
     * another. Before both sides are ready (step 4 of §7.4) and while reconnecting they are
     * dropped: the host resends snapshots and the guest its pending command until acknowledged.
     */
    void sendOriginal(String payload) {
        if (closed || payload == null || payload.isEmpty() || payload.length() > MAX_PAYLOAD_BYTES ||
            payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) return;
        submit(() -> {
            if (terminal || !ready || !peerReady || !linked) return;
            JsonObject data = InnApi.frame("data");
            data.addProperty("d", payload);
            channel.send(data);
        });
    }

    // ---- socket ----

    private void connect() {
        if (closed || terminal || channel != null) return;
        reconnect = null;
        lastError = null;
        linked = false;
        channel = api.open(InnApi.LINK_PATH, Map.of("id", matchId), network, new InnChannel.Handler() {
            @Override
            public void opened(InnChannel source) {
                if (source != channel) return;
                JsonObject hello = InnApi.frame("hello");
                hello.addProperty("v", InnApi.WIRE_VERSION);
                hello.addProperty("key", key);
                source.send(hello);
            }

            @Override
            public void frame(InnChannel source, JsonObject frame) {
                if (source == channel) receive(frame);
            }

            @Override
            public void closed(InnChannel source, int code, String reason) {
                if (source == channel) disconnected(code);
            }
        });
    }

    private void disconnected(int code) {
        channel = null;
        linked = false;
        if (closed || terminal) return;
        switch (code) {
            case 4004, 4010, 1000 -> finish(endedNotice(lastError));
            case 4009 -> finish("다른 곳에서 이 원정에 접속했습니다.");
            case 4000, 4001, 4003 -> finish("파티 연결 정보를 확인할 수 없습니다.");
            case 4008 -> finish("파티 연결 요청이 너무 많아 원정이 끝났습니다.");
            default -> {
                long time = now();
                if (lostAt == 0) lostAt = time;
                if (time - lostAt >= RECONNECT_WINDOW_MS) {
                    finish("파티 연결이 끊겼습니다.");
                    return;
                }
                long base = BACKOFF_MS[Math.min(attempt++, BACKOFF_MS.length - 1)];
                long delay = (long) (base * (0.75 + ThreadLocalRandom.current().nextDouble() / 2));
                try {
                    reconnect = network.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException ignored) {
                    // Closing.
                }
            }
        }
    }

    private void receive(JsonObject frame) {
        if (terminal) return;
        switch (InnApi.string(frame, "t")) {
            case "link" -> acceptLink(frame);
            case "paired" -> {
                if (link != null) paired();
            }
            case "peer" -> {
                if (link != null && Boolean.TRUE.equals(InnApi.bool(frame, "ready"))) peerReady();
            }
            case "data" -> {
                String payload = InnApi.string(frame, "d");
                if (link == null || payload == null || payload.isEmpty() || payload.length() > MAX_PAYLOAD_BYTES) return;
                if (ready) {
                    listener.receive(this, payload);
                } else if (inbound.size() < MAX_BUFFERED) {
                    inbound.addLast(payload);
                }
            }
            case "ended" -> finish(endedNotice(InnApi.string(frame, "reason")));
            case "error" -> lastError = InnApi.string(frame, "code");
            case null, default -> { } // Unknown types are ignored (§2).
        }
    }

    private void acceptLink(JsonObject frame) {
        InnApi.Link next = api.link(frame);
        if (next == null || !matchId.equals(next.id()) || (role != null && !role.equals(next.role())) ||
            next.dungeonProtocol() != InnApi.DUNGEON_PROTOCOL ||
            (link != null && (link.seed() != next.seed() || !link.role().equals(next.role())))) {
            finish("파티 연결 정보를 확인할 수 없습니다.");
            return;
        }
        lostAt = 0;
        attempt = 0;
        link = next;
        linked = true;
        // After a reconnect the server remembers readiness; repeating it is harmless (§9).
        if (ready) channel.send(InnApi.frame("ready"));
        if (next.paired()) paired();
        if (next.partnerReady()) peerReady();
    }

    private void paired() {
        if (paired) return;
        paired = true;
        deadline = now() + START_TIMEOUT_MS;
        listener.paired(this);
        // The guest loads first; the host starts once the guest reports ready.
        if (!link.host()) launch();
    }

    private void peerReady() {
        peerReady = true;
        if (link.host() && paired) launch();
    }

    private void launch() {
        if (launched) return;
        launched = true;
        deadline = now() + START_TIMEOUT_MS;
        listener.start(this, link, link.host(), link.seed(), link.friendlyFire());
    }

    private void tick() {
        if (closed || terminal) return;
        if (!(ready && peerReady) && now() >= deadline) {
            finish("동료와 던전 연결을 열지 못했습니다. 여관에서 다시 시도해 주세요.");
        }
    }

    private static String endedNotice(String reason) {
        return switch (reason == null ? "" : reason) {
            case "partner_left" -> "동료가 원정을 종료했습니다.";
            case "partner_lost" -> "동료와의 연결이 끊겼습니다.";
            case "pair_timeout" -> "동료와 던전 연결을 열지 못했습니다. 여관에서 다시 시도해 주세요.";
            case "rate_limited", "protocol_error" -> "파티 연결에 문제가 생겨 원정이 끝났습니다.";
            case "expired" -> "원정 시간이 다 되어 파티 연결이 끝났습니다.";
            default -> "파티 연결이 종료되었습니다.";
        };
    }

    private void finish(String notice) {
        if (terminal) return;
        terminal = true;
        // Tell the server, so the partner's run ends too when this side gives up first.
        leaveLink();
        listener.end(this, notice);
    }

    private void leaveLink() {
        if (reconnect != null) reconnect.cancel(false);
        InnChannel current = channel;
        boolean joined = linked;
        channel = null;
        linked = false;
        if (current == null) return;
        // Before its `link` the server may still be checking the hello and would refuse the frame.
        if (joined) current.send(InnApi.frame("leave"));
        closing.track(current.close(1000, "leave"));
    }

    private static long now() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private void submit(Runnable task) {
        try {
            network.execute(task);
        } catch (RejectedExecutionException ignored) {
            // A network reply can arrive during shutdown.
        }
    }

    /** Ends the run for both sides (`leave`) without notifying the listener. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        submit(() -> {
            terminal = true;
            leaveLink();
            network.shutdown();
        });
    }

    /** Waits up to the timeout for {@link #close} to finish sending `leave` and the close frame. */
    boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        if (!network.awaitTermination(timeout, unit)) return false;
        return closing.awaitDone(deadline);
    }
}
