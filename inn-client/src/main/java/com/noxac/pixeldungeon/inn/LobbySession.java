// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The inn socket (PROTOCOL.md §4–§5, §9): presence, movement, chat, NPC notices, preparation and
 * matchmaking, all pushed by the server. Every field below is confined to the "inn-network" thread.
 *
 * <p>The client's intent (in a dungeon or not, its profile, the matches it has finished) is kept
 * apart from what the server last said. A `hello` fixes presence and profile when it is sent, and a
 * takeover can carry an old match into the `welcome`; every `welcome` is therefore compared with the
 * intent and corrected with `away`, `enter` or `profile` (§5.1, §9).
 */
final class LobbySession implements AutoCloseable {
    /**
     * @param stopped the client gave up connecting (placeholder build, refused by the server, an
     *                update is needed); {@code networkStatus} says why.
     */
    record Snapshot(InnApi.State state, List<InnApi.Event> history, boolean online,
                    String networkStatus, String matchStatus, String notice, boolean stopped) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    static final long MOVE_INTERVAL = Duration.ofMillis(150).toNanos();
    private static final long[] BACKOFF_MS = {500, 1_000, 2_000, 4_000, 8_000};
    private static final long REQUEST_TIMEOUT_MS = 8_000;
    private static final long NOTICE_MS = 5_000;
    private static final int MAX_HISTORY = 60;
    static final String CONNECTING = "여관에 접속하는 중…";
    private static final String RECONNECTING = "여관 서버에 다시 연결하는 중…";
    /** JavaScript's String.prototype.trim() set: what the server strips from names (§4.1). */
    private static final Pattern JS_TRIM = Pattern.compile(
        "^[\\t\\n\\u000B\\f\\r\\u2028\\u2029\\uFEFF\\p{Zs}]+|[\\t\\n\\u000B\\f\\r\\u2028\\u2029\\uFEFF\\p{Zs}]+$");
    private static final Pattern FORBIDDEN = Pattern.compile("[\\p{C}\\p{Zl}\\p{Zp}]");

    private record Pending(BiConsumer<Boolean, String> done, ScheduledFuture<?> timeout) {}

    private final InnApi api;
    private final InnLayout layout = InnApi.layout();
    private final Consumer<Snapshot> listener;
    private final ScheduledThreadPoolExecutor network = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "inn-network");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean moving = new AtomicBoolean();
    private final TreeMap<Long, InnApi.Event> history = new TreeMap<>();
    private final InnChannel.Closing closing = new InnChannel.Closing();
    private final Map<Long, Pending> requests = new HashMap<>();
    /** Every match this session has seen; returning from a dungeon marks them all finished. */
    private final Set<String> seenMatches = new HashSet<>();
    private final Set<String> finishedMatches = new HashSet<>();
    private volatile String key = newKey();
    private volatile boolean friendlyFire;
    private volatile long nextMoveAt;

    // ---- intent ----
    private String name;
    private InnApi.Appearance appearance;
    /** In a dungeon (solo or party): the server should not show this player. */
    private boolean away;
    /** Recruiting; a reconnect restores it with `queued:true`. */
    private boolean recruiting;
    private int x = -1;
    private int y = -1;

    // ---- the current socket ----
    private InnChannel channel;
    /** Bumped for every socket, so replies to an older socket's requests are ignored. */
    private int generation;
    /** The current socket has sent its `hello`; later frames may be pipelined behind it (§2). */
    private boolean helloSent;
    private boolean welcomed;
    /** `away` / `enter` sent on the current socket and not answered yet. */
    private int awayInFlight;
    private int enterInFlight;
    /** The profile the server was last given (in `hello` or `profile`). */
    private String sentName;
    private InnApi.Appearance sentAppearance;

    // ---- what the server said ----
    /** The room view; null while away, before the first welcome and after leaving. */
    private InnApi.State state;
    private boolean online;
    private Integer shard;

    private int attempt;
    private ScheduledFuture<?> reconnect;
    private long nextRef;
    private String lastError;
    private Long retryAfterSeconds;
    private boolean stopped;
    private int stopCode;
    private boolean closed;
    private String connectionMessage = CONNECTING;
    private String notice = "";
    private long noticeUntil;
    private ScheduledFuture<?> noticeExpiry;

    LobbySession(InnApi api, String name, InnApi.Appearance appearance, boolean friendlyFire, Consumer<Snapshot> listener) {
        this.api = api;
        this.name = Objects.requireNonNullElseGet(cleanName(name), LobbySession::guestName);
        this.appearance = appearance;
        this.friendlyFire = friendlyFire;
        this.listener = listener;
        submit(this::connect);
    }

    static String guestName() {
        return "모험가-" + HexFormat.of().withUpperCase().formatHex(randomBytes(2));
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** 32 random bytes, base64url without padding (PROTOCOL.md §3). */
    static String newKey() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    /**
     * The name as the server will store it: trimmed like JavaScript's trim() (which also removes
     * no-break and other Unicode spaces), 1–20 code points, no C, Zl or Zp characters. Null when the
     * server would refuse it.
     */
    static String cleanName(String raw) {
        if (raw == null) return null;
        String value = JS_TRIM.matcher(raw).replaceAll("");
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 20 || FORBIDDEN.matcher(value).find()) return null;
        return value;
    }

    static boolean validName(String name) {
        return cleanName(name) != null;
    }

    // ---- socket ----

    private void connect() {
        if (closed || stopped || channel != null) return;
        if (!api.configured()) {
            stop("이 빌드에는 여관 서버 주소가 설정되어 있지 않습니다. 혼자 하는 원정만 할 수 있습니다.", 0);
            publish();
            return;
        }
        if (reconnect != null) reconnect.cancel(false);
        reconnect = null;
        int current = ++generation;
        helloSent = false;
        welcomed = false;
        awayInFlight = 0;
        enterInFlight = 0;
        lastError = null;
        retryAfterSeconds = null;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("layout", layout.id());
        // Return to the same room when it has space (§4).
        if (shard != null) query.put("shard", Integer.toString(shard));
        channel = api.open(InnApi.INN_PATH, query, network, new InnChannel.Handler() {
            @Override
            public void opened(InnChannel source) {
                if (source == channel && current == generation) hello(source);
            }

            @Override
            public void frame(InnChannel source, JsonObject frame) {
                if (source == channel && current == generation) receive(frame);
            }

            @Override
            public void closed(InnChannel source, int code, String reason) {
                if (source == channel && current == generation) disconnected(source, code, reason);
            }
        });
    }

    private void hello(InnChannel source) {
        if (x < 0 || !InnLayout.walkable(layout.map(), x, y)) spawn();
        JsonObject hello = InnApi.frame("hello");
        hello.addProperty("v", InnApi.WIRE_VERSION);
        hello.addProperty("key", key);
        hello.addProperty("name", name);
        hello.add("appearance", api.gson.toJsonTree(appearance));
        hello.addProperty("dungeonProtocol", InnApi.DUNGEON_PROTOCOL);
        hello.addProperty("x", x);
        hello.addProperty("y", y);
        hello.addProperty("present", !away);
        hello.addProperty("queued", recruiting && !away);
        hello.addProperty("friendlyFire", friendlyFire);
        hello.addProperty("theme", InnApi.SEWERS);
        if (source.send(hello)) {
            helloSent = true;
            sentName = name;
            sentAppearance = appearance;
        }
    }

    private void spawn() {
        InnLayout.Spawn spawn = layout.randomSpawn();
        x = spawn.x();
        y = spawn.y();
    }

    private void disconnected(InnChannel source, int code, String reason) {
        dropChannel();
        if (closed) return;
        String error = lastError;
        switch (code) {
            case 4001 -> stop("새 버전이 나왔습니다. 클라이언트를 업데이트해 주세요.", code);
            case 4009 -> stop("다른 곳에서 같은 모험가로 여관에 접속했습니다.", code);
            // A refused hello or layout is refused again on every retry (PROTOCOL.md §8). A corrected
            // profile (updateProfile) tries again.
            case 4000 -> stop("여관 서버가 이 클라이언트의 요청을 거부했습니다 (" +
                (error != null ? error : reason) + "). 모험가 정보를 고치거나 클라이언트를 업데이트해 주세요.", code);
            // §8/§9: these waits are required; the jitter only ever lengthens them.
            case 4013 -> retry("여관이 가득 찼습니다. 자리가 나면 입장합니다.",
                atLeast(1_000 * (retryAfterSeconds != null && retryAfterSeconds > 0 ? Math.min(retryAfterSeconds, 600) : 30)));
            case 4029 -> retry("같은 곳에서 접속한 모험가가 많습니다. 잠시 뒤 다시 연결합니다.", atLeast(30_000));
            case 4008 -> retry("접속 요청이 많습니다. 잠시 뒤 다시 연결합니다.", atLeast(10_000));
            default -> retry(source.wasOpened() ? RECONNECTING : unreachable(reason), backoff());
        }
        publish();
    }

    private void dropChannel() {
        channel = null;
        generation++;
        helloSent = false;
        welcomed = false;
        online = false;
        failRequests("offline");
    }

    /** Closes the current socket on purpose and opens a new one, whose `hello` restates the intent. */
    private void restartSocket(String reason) {
        if (channel != null) channel.close(1000, reason);
        dropChannel();
        retry(RECONNECTING, backoff());
        publish();
    }

    private String unreachable(String reason) {
        String detail = switch (reason) {
            case "http_403" -> " (접속이 허용되지 않았습니다)";
            case "http_404", "http_426" -> " (여관 서버 주소를 확인하세요)";
            default -> "";
        };
        return "여관 서버(" + api.host() + ")에 연결할 수 없습니다" + detail + ". 다시 시도하는 중…";
    }

    private long backoff() {
        return jitter(BACKOFF_MS[Math.min(attempt++, BACKOFF_MS.length - 1)]);
    }

    private static long jitter(long base) {
        return (long) (base * (0.75 + ThreadLocalRandom.current().nextDouble() / 2));
    }

    /** A required wait plus up to 25 % jitter, never shorter. */
    private static long atLeast(long base) {
        return (long) (base * (1 + ThreadLocalRandom.current().nextDouble() / 4));
    }

    private void retry(String message, long delayMs) {
        offline(message);
        if (reconnect != null) reconnect.cancel(false);
        try {
            reconnect = network.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Closing.
        }
    }

    private void stop(String message, int code) {
        stopped = true;
        stopCode = code;
        offline(message);
    }

    // ---- server frames ----

    private void receive(JsonObject frame) {
        String type = InnApi.string(frame, "t");
        if ("ok".equals(type) || "error".equals(type)) {
            Long ref = InnApi.integer(frame, "ref");
            Pending pending = ref == null ? null : requests.remove(ref);
            if (pending != null) {
                pending.timeout().cancel(false);
                pending.done().accept("ok".equals(type), InnApi.string(frame, "code"));
                return;
            }
            if ("error".equals(type)) unsolicitedError(frame);
            return;
        }
        if ("welcome".equals(type)) {
            welcome(frame);
            return;
        }
        if ("preparation".equals(type)) {
            if (state != null) state = state.withPreparation(api.preparation(frame.get("preparation")));
            publish();
            return;
        }
        if (state == null || !online) return;
        switch (type) {
            case "person" -> upsert(api.person(frame.get("person")));
            case "gone" -> {
                String id = InnApi.string(frame, "id");
                if (id != null && !id.equals(state.selfId())) {
                    state = state.withPeople(state.people().stream().filter(person -> !person.id().equals(id)).toList());
                }
            }
            case "moved" -> {
                String id = InnApi.string(frame, "id");
                Long nextX = InnApi.integer(frame, "x");
                Long nextY = InnApi.integer(frame, "y");
                if (id == null || nextX == null || nextY == null || id.equals(state.selfId())) return;
                int cellX = nextX.intValue();
                int cellY = nextY.intValue();
                // Clients of the same layout drop positions that are walls on their own map (§5.3).
                state = state.withPeople(state.people().stream()
                    .filter(person -> !person.id().equals(id) || state.walkable(cellX, cellY))
                    .map(person -> person.id().equals(id) ? person.at(cellX, cellY) : person).toList());
            }
            case "you" -> {
                Long nextX = InnApi.integer(frame, "x");
                Long nextY = InnApi.integer(frame, "y");
                if (nextX == null || nextY == null || !state.walkable(nextX.intValue(), nextY.intValue())) return;
                x = nextX.intValue();
                y = nextY.intValue();
                state = state.withSelfPosition(x, y);
            }
            case "event" -> {
                InnApi.Event event = api.event(frame.get("event"));
                Long now = InnApi.integer(frame, "now");
                if (event == null) return;
                remember(List.of(event));
                state = state.withEvents(List.copyOf(history.values()), now == null ? state.now() : now);
            }
            case "matched" -> {
                InnApi.Match match = api.match(frame.get("match"));
                if (match == null) return;
                recruiting = false;
                seenMatches.add(match.id());
                state = state.withQueue(false, match);
            }
            default -> {
                return; // Unknown types are ignored (§2).
            }
        }
        publish();
    }

    private void unsolicitedError(JsonObject frame) {
        String code = InnApi.string(frame, "code");
        if (code == null) return;
        switch (code) {
            case "match_failed" -> {
                notice("동료와 연결하지 못했습니다. 다시 시도해 주세요.");
                publish();
            }
            case "inn_full" -> {
                lastError = code;
                retryAfterSeconds = InnApi.integer(frame, "retryAfter");
            }
            // The close that follows explains these.
            case "replaced", "rate_limited", "room_busy", "too_many_connections", "unsupported_version",
                 "hello_timeout", "invalid_json", "invalid_frame", "hello_required", "invalid_hello",
                 "invalid_layout" -> lastError = code;
            default -> { } // move_rejected (followed by `you`) and fire-and-forget notices.
        }
    }

    private void welcome(JsonObject frame) {
        Long shardValue = InnApi.integer(frame, "shard");
        Integer previousShard = shard;
        if (shardValue != null && shardValue >= 0 && shardValue < 64) shard = shardValue.intValue();
        welcomed = true;
        String selfId = InnApi.string(frame, "selfId");
        boolean present = !Boolean.FALSE.equals(InnApi.bool(frame, "present"));
        if (selfId == null || !layout.id().equals(InnApi.string(frame, "layoutId"))) {
            stop("여관 배치가 서버와 맞지 않습니다. 클라이언트를 업데이트해 주세요.", 4000);
            if (channel != null) channel.close(1000, "layout");
            dropChannel();
            publish();
            return;
        }
        if (away) {
            // In a dungeon. A welcome that still shows this player (its hello said present:true, or
            // an `enter` crossed with the departure) is corrected with `away`.
            attempt = 0;
            state = null;
            online = false;
            if (!stopped) connectionMessage = "";
            if (present && awayInFlight == 0) sendAway();
            reconcileProfile();
            publish();
            return;
        }
        if (!present) {
            // Back from a dungeon while a reconnect's hello(present:false) was on its way.
            attempt = 0;
            state = null;
            online = false;
            if (!stopped) connectionMessage = CONNECTING;
            if (enterInFlight == 0) sendEnter();
            reconcileProfile();
            publish();
            return;
        }
        List<InnApi.Person> people = new ArrayList<>();
        InnApi.Person self = null;
        JsonElement roster = frame.get("people");
        if (roster != null && roster.isJsonArray()) {
            for (JsonElement entry : roster.getAsJsonArray()) {
                InnApi.Person person = api.person(entry);
                if (person == null || people.stream().anyMatch(other -> other.id().equals(person.id()))) continue;
                if (person.id().equals(selfId)) {
                    self = person;
                    people.add(person);
                } else if (InnLayout.walkable(layout.map(), person.x(), person.y())) {
                    people.add(person);
                }
            }
        }
        if (self == null) {
            // The server always lists a present player; without it the room cannot be shown. The
            // back-off keeps growing, so this cannot become a fast reconnect loop.
            if (channel != null) channel.close(1000, "invalid_state");
            dropChannel();
            retry(RECONNECTING, backoff());
            publish();
            return;
        }
        attempt = 0;
        connectionMessage = "";
        x = self.x();
        y = self.y();
        // The history is memory-only on the server and may come back empty (§5.1): keep what this
        // client already showed for the same room and add what the server still remembers.
        if (previousShard != null && !previousShard.equals(shard)) history.clear();
        JsonElement events = frame.get("events");
        if (events != null && events.isJsonArray()) {
            List<InnApi.Event> parsed = new ArrayList<>();
            for (JsonElement entry : events.getAsJsonArray()) {
                InnApi.Event event = api.event(entry);
                if (event != null) parsed.add(event);
            }
            remember(parsed);
        }
        Long now = InnApi.integer(frame, "now");
        InnApi.Match match = api.match(frame.get("match"));
        boolean staleMatch = match != null && finishedMatches.contains(match.id());
        if (staleMatch) match = null;
        else if (match != null) seenMatches.add(match.id());
        boolean queued = Boolean.TRUE.equals(InnApi.bool(frame, "queued"));
        List<InnApi.Event> recent = List.copyOf(history.values());
        state = new InnApi.State(selfId, List.copyOf(people), layout.map(), recent,
            recent.isEmpty() ? 0 : recent.getLast().id(), now == null ? System.currentTimeMillis() : now,
            queued || recruiting && match == null, match, layout.npcs(), api.preparation(frame.get("preparation")),
            layout.id(), layout.expeditionGate());
        online = true;
        // A takeover can hand back a match this client already finished or gave up (§5.1): leave it
        // behind with `enter` at the current position; until then the server answers `busy`.
        if (staleMatch && enterInFlight == 0) sendEnter();
        reconcileProfile();
        publish();
    }

    /** A profile saved while the hello was on its way, or refused, is sent again. */
    private void reconcileProfile() {
        if (Objects.equals(name, sentName) && Objects.equals(appearance, sentAppearance)) return;
        sendProfile();
    }

    private void upsert(InnApi.Person person) {
        if (person == null) return;
        boolean self = person.id().equals(state.selfId());
        InnApi.Person previous = state.people().stream().filter(other -> other.id().equals(person.id())).findFirst().orElse(null);
        // This client predicts its own steps; keep the predicted position (§5.2).
        InnApi.Person updated = self && previous != null ? person.at(previous.x(), previous.y()) : person;
        List<InnApi.Person> people = new ArrayList<>(state.people());
        people.removeIf(other -> other.id().equals(person.id()));
        if (self || state.walkable(updated.x(), updated.y())) people.add(updated);
        state = state.withPeople(List.copyOf(people));
        if (self) {
            recruiting = person.queued();
            state = state.withQueue(person.queued(), person.queued() ? null : state.match());
        }
    }

    private void remember(List<InnApi.Event> events) {
        for (InnApi.Event event : events) history.put(event.id(), event);
        while (history.size() > MAX_HISTORY) history.pollFirstEntry();
    }

    // ---- requests ----

    /** Sends a request on the current socket once its hello is out (pipelining, §2). */
    private void request(JsonObject frame, BiConsumer<Boolean, String> done) {
        if (channel == null || !helloSent || !channel.isOpen()) {
            done.accept(false, "offline");
            return;
        }
        long ref = ++nextRef;
        frame.addProperty("ref", ref);
        if (!channel.send(frame)) {
            done.accept(false, "offline");
            return;
        }
        ScheduledFuture<?> timeout = network.schedule(() -> {
            Pending pending = requests.remove(ref);
            if (pending != null) pending.done().accept(false, "timeout");
        }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        requests.put(ref, new Pending(done, timeout));
    }

    private void failRequests(String code) {
        List<Pending> pending = List.copyOf(requests.values());
        requests.clear();
        for (Pending request : pending) {
            request.timeout().cancel(false);
            request.done().accept(false, code);
        }
    }

    /** A room action: only while shown in the room. Failures become a short notice. */
    private void action(JsonObject frame, Consumer<Boolean> result) {
        if (!online || away || state == null) {
            result.accept(false);
            return;
        }
        request(frame, (ok, code) -> {
            if (!ok) actionFailed(code);
            result.accept(ok);
        });
    }

    private void actionFailed(String code) {
        switch (code == null ? "" : code) {
            case "move_rejected" -> { }
            case "partner_unavailable" -> notice("이 모험가는 지금 참가할 수 없습니다.");
            case "protocol_mismatch" -> notice("이 모험가와는 게임 버전이 달라 함께할 수 없습니다.");
            case "busy" -> notice("이미 동료와 연결되었습니다.");
            case "activity_rate_limited" -> notice("잠시 뒤 다시 시도해 주세요.");
            // Chat: the room's shared budget is spent; the caller keeps the unsent text.
            case "room_busy" -> notice("여관이 붐벼서 지금은 말을 전할 수 없습니다. 잠시 뒤 다시 보내 주세요.");
            case "not_in_inn" -> notice("여관에 들어온 뒤에 할 수 있습니다.");
            // One preparation roll per entry (§4.4); not a fault of the connection.
            case "preparation_used" -> notice("이번 입장에서는 이미 원정 준비를 했습니다.");
            case "invalid_message" -> notice("대화는 한 줄, 160자까지 보낼 수 있습니다.");
            case "invalid_profile" -> notice("닉네임을 1~20자로 입력하세요.");
            case "offline", "timeout" -> notice(RECONNECTING);
            default -> notice("여관 요청을 처리하지 못했습니다.");
        }
        publish();
    }

    void updateProfile(String value, InnApi.Appearance avatar) {
        submit(() -> {
            String cleaned = cleanName(value);
            if (cleaned == null) {
                notice("닉네임을 1~20자로 입력하세요.");
                publish();
                return;
            }
            name = cleaned;
            appearance = avatar;
            if (stopped && stopCode == 4000) {
                // The server refused the previous hello; a corrected profile tries again.
                stopped = false;
                stopCode = 0;
                attempt = 0;
                connectionMessage = CONNECTING;
                connect();
                publish();
                return;
            }
            // Otherwise the next hello, or the next welcome's reconciliation, carries it.
            if (helloSent) sendProfile();
        });
    }

    private void sendProfile() {
        if (channel == null || !helloSent) return;
        JsonObject profile = InnApi.frame("profile");
        profile.addProperty("name", name);
        profile.add("appearance", api.gson.toJsonTree(appearance));
        sentName = name;
        sentAppearance = appearance;
        request(profile, (ok, code) -> {
            if (!ok && !"offline".equals(code)) actionFailed(code);
        });
    }

    void setFriendlyFire(boolean enabled) {
        friendlyFire = enabled;
    }

    boolean canRequestMove() {
        return !moving.get() && System.nanoTime() - nextMoveAt >= 0;
    }

    /**
     * Sends one predicted step. The server applies steps in order and answers a rejected one with
     * `you`, which corrects the position; the result reports only whether the step was sent.
     */
    boolean moveTo(int fromX, int fromY, int targetX, int targetY, Consumer<Boolean> result) {
        if (!canRequestMove() || !moving.compareAndSet(false, true)) return false;
        boolean scheduled = submit(() -> {
            boolean sent = false;
            try {
                long started = System.nanoTime();
                if (!online || away || state == null || channel == null || !welcomed) return;
                InnApi.Person current = state.self();
                if (current.x() != fromX || current.y() != fromY ||
                        !state.canStep(fromX, fromY, targetX, targetY)) return;
                JsonObject move = InnApi.frame("move");
                move.addProperty("x", targetX);
                move.addProperty("y", targetY);
                sent = channel.send(move);
                if (sent) {
                    nextMoveAt = started + MOVE_INTERVAL;
                    x = targetX;
                    y = targetY;
                    state = state.withSelfPosition(targetX, targetY);
                    publish();
                }
            } finally {
                moving.set(false);
                result.accept(sent);
            }
        });
        if (!scheduled) moving.set(false);
        return scheduled;
    }

    void chat(String message, Consumer<Boolean> result) {
        submit(() -> {
            JsonObject chat = InnApi.frame("chat");
            chat.addProperty("text", message);
            action(chat, result);
        });
    }

    void drink() {
        submit(() -> {
            if (!online || away || state == null) return;
            // The room's shared event budget being spent (`room_busy`) is not worth a notice here.
            request(InnApi.frame("drink"), (ok, code) -> {
                if (!ok && !"room_busy".equals(code)) actionFailed(code);
            });
        });
    }

    /** A notice for the others; the conversation itself is local, so failures are ignored. */
    void talkToNpc(String npcId) {
        submit(() -> {
            if (!online || away || state == null || channel == null) return;
            JsonObject interact = InnApi.frame("interact");
            interact.addProperty("npcId", npcId);
            channel.send(interact);
        });
    }

    void prepare(Runnable finished) {
        if (!submit(() -> action(InnApi.frame("prepare"), ok -> finished.run()))) finished.run();
    }

    /** Starts recruiting (`queue`) or stops (`cancel`). */
    void matchmaking(boolean queued) {
        submit(() -> {
            if (!queued) {
                recruiting = false;
                action(InnApi.frame("cancel"), ok -> { });
                return;
            }
            JsonObject queue = InnApi.frame("queue");
            queue.addProperty("friendlyFire", friendlyFire);
            queue.addProperty("theme", InnApi.SEWERS);
            action(queue, ok -> { });
        });
    }

    /** Joins a recruiting player; that player hosts. */
    void joinSeeking(String targetId) {
        submit(() -> {
            JsonObject join = InnApi.frame("join");
            join.addProperty("targetId", targetId);
            join.addProperty("friendlyFire", friendlyFire);
            join.addProperty("theme", InnApi.SEWERS);
            action(join, ok -> { });
        });
    }

    String sessionKey() {
        return key;
    }

    /**
     * Leaves the room view for a dungeon (`away`) and keeps the socket open for the return. A solo
     * expedition passes the preparation it applies so the server forgets it.
     */
    void enterDungeon(InnApi.Preparation consumed) {
        submit(() -> {
            if (consumed != null) consumePreparation(consumed);
            leaveRoom();
        });
    }

    void leaveRoom() {
        submit(() -> {
            if (away) return;
            away = true;
            recruiting = false;
            state = null;
            online = false;
            if (!stopped) connectionMessage = "";
            // Pipelined behind a hello still on its way, if need be; a socket still in its handshake
            // says present:false in its hello instead.
            sendAway();
            publish();
        });
    }

    private void sendAway() {
        if (channel == null || !helloSent) return;
        int current = generation;
        awayInFlight++;
        request(InnApi.frame("away"), (ok, code) -> {
            if (current != generation) return;
            awayInFlight--;
            // A refused `away` (rate limited) would leave the avatar standing in the room for the
            // whole run: start over with a socket whose hello says present:false.
            if (!ok && away && !"offline".equals(code)) restartSocket("retry_away");
        });
    }

    private void consumePreparation(InnApi.Preparation preparation) {
        JsonObject use = InnApi.frame("use_preparation");
        use.addProperty("id", preparation.id());
        int current = generation;
        request(use, (ok, code) -> {
            if (ok) return;
            // A preparation that could not be consumed must not carry over into another expedition:
            // continue under a new identity, which has none. A new key needs a new socket (§3).
            key = newKey();
            shard = null;
            if (current == generation && channel != null) channel.close(1000, "new_key");
            if (current == generation) dropChannel();
            attempt = 0;
            connect();
        });
    }

    /** Appears in the room again after a dungeon, at a fresh spawn point. */
    void returnFromDungeon(boolean normalExit) {
        submit(() -> {
            if (!normalExit) notice("던전을 정상적으로 열거나 종료하지 못했습니다.");
            // Whatever run brought this player out of the room is over now.
            finishedMatches.addAll(seenMatches);
            if (!away) {
                // A party that ended before both players left the room: `enter` in place clears the
                // match, which otherwise keeps this player `busy` (§4.5). While reconnecting, the
                // next welcome does it.
                if (state != null && state.match() != null) {
                    state = state.withQueue(state.queued(), null);
                    if (enterInFlight == 0) sendEnter();
                }
                publish();
                return;
            }
            away = false;
            spawn();
            if (!stopped) connectionMessage = CONNECTING;
            if (channel != null && helloSent) {
                sendEnter();
            } else if (channel == null && !stopped) {
                attempt = 0;
                connect();
            }
            // A socket still in its handshake says present:true in its hello.
            publish();
        });
    }

    private void sendEnter() {
        if (channel == null || !helloSent) return;
        if (x < 0 || !InnLayout.walkable(layout.map(), x, y)) spawn();
        JsonObject enter = InnApi.frame("enter");
        enter.addProperty("x", x);
        enter.addProperty("y", y);
        int current = generation;
        enterInFlight++;
        // The welcome that follows shows the room again.
        request(enter, (ok, code) -> {
            if (current != generation) return;
            enterInFlight--;
            // Still away (or still matched) on the server: a new socket's hello brings the player back.
            if (!ok && !away && !"offline".equals(code)) restartSocket("retry_enter");
        });
    }

    // ---- snapshots ----

    private void offline(String message) {
        online = false;
        connectionMessage = message;
    }

    /** Shows a notice for five seconds; a publish at the expiry removes it even in a quiet room. */
    private void notice(String message) {
        notice = message;
        noticeUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(NOTICE_MS);
        if (noticeExpiry != null) noticeExpiry.cancel(false);
        try {
            noticeExpiry = network.schedule(this::publish, NOTICE_MS + 20, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Closing.
        }
    }

    private String matchStatus() {
        if (state == null) return "";
        if (state.queued()) return "동료를 찾는 중…";
        if (state.match() == null) return "";
        return state.match().partnerName() + " 님과 매칭됨";
    }

    private void publish() {
        listener.accept(new Snapshot(state, List.copyOf(history.values()), online && state != null,
            connectionMessage, matchStatus(), System.nanoTime() < noticeUntil ? notice : "", stopped));
    }

    private boolean submit(Runnable task) {
        try {
            network.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            // A network reply can arrive while the window closes.
            return false;
        }
    }

    /** Leaves the inn: the socket closes with 1000 and the others see `gone`. */
    @Override
    public void close() {
        submit(() -> {
            closed = true;
            if (reconnect != null) reconnect.cancel(false);
            if (noticeExpiry != null) noticeExpiry.cancel(false);
            failRequests("offline");
            if (channel != null) closing.track(channel.close(1000, "bye"));
            channel = null;
            network.shutdown();
        });
    }

    /** Waits up to the timeout for {@link #close} to finish sending the close frame. */
    boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        if (!network.awaitTermination(timeout, unit)) return false;
        return closing.awaitDone(deadline);
    }
}
