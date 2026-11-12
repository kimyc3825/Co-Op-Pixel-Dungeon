// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class InnApi {
    record Appearance(String job, String hairColor, String outfitColor, String hairStyle, String accessory) {}
    record Person(String id, String name, int x, int y, boolean queued, Appearance appearance) {
        Person {
            appearance = AvatarProfile.normalize(appearance);
        }

        Person(String id, String name, int x, int y, boolean queued) {
            this(id, name, x, y, queued, null);
        }
    }
    record Npc(String id, String name, String role, int x, int y, String dialogue) {}
    record Event(long id, String playerId, String name, String kind, String text, long time) {}
    record Match(String id, String role, String partnerName) {}
    record Preparation(String id, int hp, int food) {
        static int health(Preparation value) { return value == null ? 0 : value.hp(); }
        static int rations(Preparation value) { return value == null ? 0 : value.food(); }

        String description() {
            if (hp == 0 && food == 0) return "아무 일도 없었습니다.";
            return "최대 체력 " + delta(hp) + " · 식량 " + delta(food);
        }

        private static String delta(int value) { return value > 0 ? "+" + value : "−" + -value; }
    }
    record Action(String type, int dx, int dy, String target) {}
    record Combatant(String id, String name, String kind, int x, int y, int hp, int maxHp,
                     int actionSeq, String lastAction, String targetId, long actionAt,
                     double energy, long energyAt, long moveReadyAt, long attackReadyAt, int rations,
                     Appearance appearance) {
        Combatant {
            appearance = AvatarProfile.normalize(appearance);
        }

        Combatant(String id, String name, String kind, int x, int y, int hp, int maxHp,
                  int actionSeq, String lastAction, String targetId, long actionAt,
                  double energy, long energyAt, long moveReadyAt, long attackReadyAt, int rations) {
            this(id, name, kind, x, y, hp, maxHp, actionSeq, lastAction, targetId, actionAt,
                energy, energyAt, moveReadyAt, attackReadyAt, rations, null);
        }

        Combatant withAppearance(Appearance value) {
            return value == null || value.equals(appearance) ? this : new Combatant(id, name, kind, x, y, hp,
                maxHp, actionSeq, lastAction, targetId, actionAt, energy, energyAt, moveReadyAt, attackReadyAt,
                rations, value);
        }
    }
    record Drop(String id, String item, int x, int y, int amount, String pickedBy) {}
    record TrainingTool(String id, int x, int y) {}
    record PartyChat(long id, String actorId, String name, String text, long time) {}
    record BattleEvent(long id, String action, String result, String actorId, String targetId,
                       int x, int y, int amount, long at) {}
    record Projectile(long id, String ownerId, int x, int y, int nextX, int nextY, long stepAt) {}
    record DungeonState(String id, String selfId, long now, List<String> map, List<Combatant> actors,
                        Action queued, String status, boolean waitingForPartner, int revision,
                        List<Drop> drops, long commandAck, boolean inCombat, String theme,
                        long wallNow, long pausedUntil, long pauseReadyAt, List<PartyChat> messages,
                        boolean friendlyFire, List<BattleEvent> attackEvents, List<Projectile> projectiles,
                        List<TrainingTool> trainingTools) {
        DungeonState {
            theme = DungeonLayout.themeOrDefault(theme);
            if (wallNow == 0) wallNow = now;
            if (messages == null) messages = List.of();
            if (attackEvents == null) attackEvents = List.of();
            if (projectiles == null) projectiles = List.of();
            if (trainingTools == null) trainingTools = List.of();
        }

        DungeonState(String id, String selfId, long now, List<String> map, List<Combatant> actors,
                     Action queued, String status, boolean waitingForPartner, int revision,
                     List<Drop> drops, long commandAck, boolean inCombat, String theme,
                     long wallNow, long pausedUntil, long pauseReadyAt, List<PartyChat> messages,
                     boolean friendlyFire, List<BattleEvent> attackEvents, List<Projectile> projectiles) {
            this(id, selfId, now, map, actors, queued, status, waitingForPartner, revision,
                drops, commandAck, inCombat, theme, wallNow, pausedUntil, pauseReadyAt, messages,
                friendlyFire, attackEvents, projectiles, List.of());
        }

        DungeonState(String id, String selfId, long now, List<String> map, List<Combatant> actors,
                     Action queued, String status, boolean waitingForPartner, int revision,
                     List<Drop> drops, long commandAck, boolean inCombat, String theme,
                     long wallNow, long pausedUntil, long pauseReadyAt, List<PartyChat> messages) {
            this(id, selfId, now, map, actors, queued, status, waitingForPartner, revision,
                drops, commandAck, inCombat, theme, wallNow, pausedUntil, pauseReadyAt, messages,
                false, List.of(), List.of());
        }

        DungeonState(String id, String selfId, long now, List<String> map, List<Combatant> actors,
                     Action queued, String status, boolean waitingForPartner, int revision,
                     List<Drop> drops, long commandAck, boolean inCombat, String theme) {
            this(id, selfId, now, map, actors, queued, status, waitingForPartner, revision,
                drops, commandAck, inCombat, theme, now, 0, 0, List.of());
        }

        DungeonState(String id, String selfId, long now, List<String> map, List<Combatant> actors,
                     Action queued, String status, boolean waitingForPartner, int revision,
                     List<Drop> drops, long commandAck, boolean inCombat) {
            this(id, selfId, now, map, actors, queued, status, waitingForPartner, revision,
                drops, commandAck, inCombat, DungeonLayout.SEWERS);
        }

        DungeonState withActors(List<Combatant> updated) {
            return actors.equals(updated) ? this : new DungeonState(id, selfId, now, map, updated, queued, status,
                waitingForPartner, revision, drops, commandAck, inCombat, theme, wallNow, pausedUntil, pauseReadyAt,
                messages, friendlyFire, attackEvents, projectiles, trainingTools);
        }
    }
    record Signal(String type, String sdp) {}
    record LinkState(String id, String role, String selfId, String partnerId, String selfName,
                     String partnerName, boolean paired, boolean closed, Signal signal,
                     String message, long messageSeq, Appearance selfAppearance, Appearance partnerAppearance,
                     Preparation selfPreparation, Preparation partnerPreparation) {
        LinkState {
            if (selfAppearance != null) selfAppearance = AvatarProfile.normalize(selfAppearance);
            if (partnerAppearance != null) partnerAppearance = AvatarProfile.normalize(partnerAppearance);
        }
    }

    record State(String selfId, List<Person> people, List<String> map, List<Event> events,
                 long after, long now, boolean queued, Match match, List<Npc> npcs, Preparation preparation) {
        State {
            if (npcs == null) npcs = List.of();
        }

        State(String selfId, List<Person> people, List<String> map, List<Event> events,
              long after, long now, boolean queued, Match match) {
            this(selfId, people, map, events, after, now, queued, match, List.of(), null);
        }
        Person self() {
            return people.stream().filter(person -> person.id().equals(selfId)).findFirst().orElseThrow();
        }

        State withSelfPosition(int x, int y) {
            List<Person> updated = people.stream()
                .map(person -> person.id().equals(selfId)
                    ? new Person(selfId, person.name(), x, y, person.queued(), person.appearance()) : person)
                .toList();
            return new State(selfId, updated, map, events, after, now, queued, match, npcs, preparation);
        }

        boolean walkable(int x, int y) {
            return y >= 0 && y < map.size() && x >= 0 && x < map.get(y).length() &&
                (map.get(y).charAt(x) == '.' || map.get(y).charAt(x) == 'D');
        }

        boolean canStep(int x, int y, int nextX, int nextY) {
            int dx = Math.abs(nextX - x);
            int dy = Math.abs(nextY - y);
            return Math.max(dx, dy) == 1 && walkable(nextX, nextY) &&
                (dx == 0 || dy == 0 || walkable(nextX, y) || walkable(x, nextY));
        }
    }

    static final class Problem extends IOException {
        final int status;
        final String code;

        Problem(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
    }

    private record ErrorBody(String error) {}
    @FunctionalInterface
    private interface Decoder<T> {
        T decode(String value) throws IOException;
    }
    private static final String API = "/api/v2/inn/";
    private static final String LINK = "/api/v4/link/";
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final URI origin;

    InnApi(URI origin) {
        String scheme = origin.getScheme();
        String path = origin.getPath();
        if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null ||
            origin.getFragment() != null || (!path.isEmpty() && !path.equals("/")) ||
            (!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && isLoopback(origin)))) {
            throw new IllegalArgumentException("서버 주소에는 HTTPS 또는 로컬 개발 주소를 사용하세요.");
        }
        this.origin = origin;
    }

    static boolean isLoopback(URI uri) {
        return "127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()) ||
            "[::1]".equals(uri.getHost());
    }

    State enter(String name, String key, long after) throws IOException, InterruptedException {
        return decode(send("PUT", API + "session", key, Map.of("name", name, "after", after, "dungeonProtocol", 8)));
    }

    State enter(String name, Appearance appearance, String key, long after) throws IOException, InterruptedException {
        return decode(send("PUT", API + "session", key,
            Map.of("name", name, "appearance", appearance, "after", after, "dungeonProtocol", 8)));
    }

    State sync(String key, long after) throws IOException, InterruptedException {
        return decode(send("POST", API + "sync", key, Map.of("after", after)));
    }

    CompletableFuture<State> syncAsync(String key, long after) {
        return sendAsync(API + "sync", key, Map.of("after", after), this::decode);
    }

    void move(String key, int x, int y) throws IOException, InterruptedException {
        send("POST", API + "move", key, Map.of("x", x, "y", y));
    }

    void chat(String key, String message) throws IOException, InterruptedException {
        send("POST", API + "chat", key, Map.of("message", message));
    }

    void drink(String key) throws IOException, InterruptedException {
        send("POST", API + "drink", key, null);
    }

    void prepare(String key) throws IOException, InterruptedException {
        send("POST", API + "preparation", key, null);
    }

    void usePreparation(String key, String id) throws IOException, InterruptedException {
        send("DELETE", API + "preparation", key, Map.of("id", id));
    }

    void matchmaking(String key, boolean queued) throws IOException, InterruptedException {
        send("PUT", API + "match", key, Map.of("queued", queued));
    }

    void joinSeeking(String key, String targetId) throws IOException, InterruptedException {
        send("PUT", API + "match", key, Map.of("queued", true, "targetId", targetId));
    }

    CompletableFuture<LinkState> enterLink(String key, String matchId) {
        return sendAsync(LINK + "enter", key, Map.of("matchId", matchId), this::decodeLink);
    }

    CompletableFuture<LinkState> syncLink(String key, String id) {
        return sendAsync(LINK + "sync", key, Map.of("id", id), this::decodeLink);
    }

    CompletableFuture<Void> signalLink(String key, String id, Signal signal) {
        return sendAsync(LINK + "signal", key,
            Map.of("id", id, "type", signal.type(), "sdp", signal.sdp()), value -> null);
    }

    CompletableFuture<Void> relayLink(String key, String id, long sequence, String message) {
        return sendAsync(LINK + "relay", key, Map.of("id", id, "seq", sequence, "message", message), value -> null);
    }

    CompletableFuture<Void> leaveLink(String key, String id) {
        return sendAsync(LINK + "leave", key, Map.of("id", id), value -> null);
    }

    void leave(String key) throws IOException, InterruptedException {
        send("DELETE", API + "session", key, null);
    }

    private State decode(String value) throws IOException {
        try {
            State state = gson.fromJson(value, State.class);
            if (state != null && state.selfId() != null && state.people() != null &&
                state.events() != null && state.map() != null && !state.map().isEmpty() &&
                state.map().getFirst() != null && !state.map().getFirst().isEmpty() &&
                state.map().stream().allMatch(row -> row != null && row.length() == state.map().getFirst().length()) &&
                state.npcs().size() <= 16 && state.npcs().stream().allMatch(npc ->
                    npc != null && npc.id() != null && npc.name() != null && npc.role() != null && npc.dialogue() != null &&
                    npc.y() >= 0 && npc.y() < state.map().size() &&
                    npc.x() >= 0 && npc.x() < state.map().get(npc.y()).length() &&
                    state.map().get(npc.y()).charAt(npc.x()) == 'N') &&
                state.people().stream().allMatch(person -> person != null && person.id() != null &&
                    person.name() != null && state.walkable(person.x(), person.y())) &&
                state.people().stream().anyMatch(person -> person.id().equals(state.selfId()))) {
                return state;
            }
        } catch (JsonParseException ignored) {
            // Surface a protocol error without displaying response content.
        }
        throw new IOException("Invalid inn response");
    }

    private LinkState decodeLink(String value) throws IOException {
        try {
            LinkState state = gson.fromJson(value, LinkState.class);
            if (state != null && state.id() != null && state.selfId() != null && state.partnerId() != null &&
                state.selfName() != null && state.partnerName() != null &&
                ("host".equals(state.role()) || "guest".equals(state.role()))) {
                return state;
            }
        } catch (JsonParseException ignored) {
            // The hosting edge may return an invalid response.
        }
        throw new IOException("Invalid party link response");
    }

    private String send(String method, String path, String key, Object body) throws IOException, InterruptedException {
        return responseBody(http.send(request(method, path, key, body), HttpResponse.BodyHandlers.ofString()));
    }

    private <T> CompletableFuture<T> sendAsync(String path, String key, Object body, Decoder<T> decoder) {
        return http.sendAsync(request("POST", path, key, body), HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                try {
                    return decoder.decode(responseBody(response));
                } catch (IOException error) {
                    throw new CompletionException(error);
                }
            });
    }

    private HttpRequest request(String method, String path, String key, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + key);
        if (body != null) request.header("Content-Type", "application/json");
        return request.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
    }

    private String responseBody(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            ErrorBody error = null;
            try {
                error = gson.fromJson(response.body(), ErrorBody.class);
            } catch (JsonParseException ignored) {
                // The hosting edge may return an HTML error page.
            }
            throw new Problem(response.statusCode(), error != null && error.error() != null
                ? error.error() : "server_error");
        }
        return response.body();
    }
}
