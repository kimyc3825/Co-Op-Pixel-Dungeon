// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** The server origin, the wire constants of server/PROTOCOL.md and the values exchanged with it. */
final class InnApi {
    /** Wire version sent in every `hello` (PROTOCOL.md §11). */
    static final int WIRE_VERSION = 1;
    /** Party payload format: the turn-clock packets of InnOriginalParty. Matching pairs only equal values. */
    static final int DUNGEON_PROTOCOL = 13;
    static final String INN_PATH = "/api/v5/inn";
    static final String LINK_PATH = "/api/v5/link";
    static final String SEWERS = "sewers";
    /** Builds that still carry the placeholder origin in inn-origin.txt have no server to talk to. */
    static final String UNCONFIGURED_TLD = ".invalid";
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    record Appearance(String job, String hairColor, String outfitColor, String hairStyle, String accessory) {}
    record Person(String id, String name, int x, int y, boolean queued, Appearance appearance) {
        Person {
            appearance = AvatarProfile.normalize(appearance);
        }

        Person(String id, String name, int x, int y, boolean queued) {
            this(id, name, x, y, queued, null);
        }

        Person at(int nextX, int nextY) {
            return new Person(id, name, nextX, nextY, queued, appearance);
        }
    }
    record Lesson(String title, String text) {}
    record Npc(String id, String name, String role, int x, int y, String dialogue, List<Lesson> lessons) {
        Npc {
            if (lessons == null) lessons = List.of();
        }

        Npc(String id, String name, String role, int x, int y, String dialogue) {
            this(id, name, role, x, y, dialogue, List.of());
        }
    }
    record Event(long id, String playerId, String name, String kind, String text, long time, String npcId) {
        Event(long id, String playerId, String name, String kind, String text, long time) {
            this(id, playerId, name, kind, text, time, null);
        }
    }
    record Match(String id, String role, String partnerId, String partnerName) {}
    record Preparation(String id, int hp, int food) {
        static int health(Preparation value) { return value == null ? 0 : value.hp(); }
        static int rations(Preparation value) { return value == null ? 0 : value.food(); }

        String description() {
            if (hp == 0 && food == 0) return "아무 일도 없었습니다.";
            return "최대 체력 " + delta(hp) + " · 식량 " + delta(food);
        }

        private static String delta(int value) {
            return value > 0 ? "+" + value : value < 0 ? "−" + -value : "±0";
        }
    }

    /** The `link` frame of the party link (PROTOCOL.md §7.1). */
    record Link(String id, String role, String selfId, String selfName, Appearance selfAppearance,
                Preparation selfPreparation, String partnerId, String partnerName, Appearance partnerAppearance,
                Preparation partnerPreparation, long seed, boolean friendlyFire, String theme, int dungeonProtocol,
                boolean paired, boolean partnerConnected, boolean partnerReady) {
        boolean host() { return "host".equals(role); }
    }

    record State(String selfId, List<Person> people, List<String> map, List<Event> events,
                 long after, long now, boolean queued, Match match, List<Npc> npcs, Preparation preparation,
                 String layoutId, InnLayout.Gate expeditionGate) {
        State {
            if (npcs == null) npcs = List.of();
        }

        State(String selfId, List<Person> people, List<String> map, List<Event> events,
              long after, long now, boolean queued, Match match) {
            this(selfId, people, map, events, after, now, queued, match, List.of(), null, null, null);
        }

        Person self() {
            return people.stream().filter(person -> person.id().equals(selfId)).findFirst().orElseThrow();
        }

        State withSelfPosition(int x, int y) {
            return withPeople(people.stream().map(person -> person.id().equals(selfId) ? person.at(x, y) : person).toList());
        }

        State withPeople(List<Person> updated) {
            return new State(selfId, updated, map, events, after, now, queued, match, npcs, preparation, layoutId, expeditionGate);
        }

        State withEvents(List<Event> updated, long time) {
            long last = updated.isEmpty() ? 0 : updated.getLast().id();
            return new State(selfId, people, map, updated, last, time, queued, match, npcs, preparation, layoutId, expeditionGate);
        }

        State withQueue(boolean nextQueued, Match nextMatch) {
            return new State(selfId, people, map, events, after, now, nextQueued, nextMatch, npcs, preparation, layoutId, expeditionGate);
        }

        State withPreparation(Preparation next) {
            return new State(selfId, people, map, events, after, now, queued, match, npcs, next, layoutId, expeditionGate);
        }

        boolean walkable(int x, int y) {
            return InnLayout.walkable(map, x, y);
        }

        boolean canStep(int x, int y, int nextX, int nextY) {
            int dx = Math.abs(nextX - x);
            int dy = Math.abs(nextY - y);
            return Math.max(dx, dy) == 1 && walkable(nextX, nextY) &&
                (dx == 0 || dy == 0 || walkable(nextX, y) || walkable(x, nextY));
        }
    }

    private static final InnLayout LAYOUT = InnLayout.load();
    final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final URI origin;

    InnApi(URI origin) {
        String scheme = origin.getScheme();
        String path = origin.getPath() == null ? "" : origin.getPath();
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

    static InnLayout layout() { return LAYOUT; }

    /** False while the origin is the reserved placeholder shipped before the first deploy. */
    boolean configured() {
        return !origin.getHost().toLowerCase(Locale.ROOT).endsWith(UNCONFIGURED_TLD);
    }

    String host() {
        return origin.getPort() < 0 ? origin.getHost() : origin.getHost() + ":" + origin.getPort();
    }

    /** `wss://` for an `https://` origin, `ws://` for a loopback development origin. */
    URI socketUri(String path, Map<String, String> query) {
        String scheme = "https".equalsIgnoreCase(origin.getScheme()) ? "wss" : "ws";
        String parameters = query.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
        return URI.create(scheme + "://" + host() + path + (parameters.isEmpty() ? "" : "?" + parameters));
    }

    InnChannel open(String path, Map<String, String> query, ScheduledExecutorService executor, InnChannel.Handler handler) {
        return InnChannel.open(http, socketUri(path, query), executor, handler);
    }

    // ---- frame decoding (unknown fields are ignored everywhere) ----

    static String string(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        return value instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : null;
    }

    static Long integer(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) return null;
        double number = primitive.getAsDouble();
        if (number != Math.rint(number) || Math.abs(number) > 9_007_199_254_740_991d) return null;
        return primitive.getAsLong();
    }

    static Boolean bool(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        return value instanceof JsonPrimitive primitive && primitive.isBoolean() ? primitive.getAsBoolean() : null;
    }

    static JsonObject object(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    Person person(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        String id = string(object, "id");
        String name = string(object, "name");
        Long x = integer(object, "x");
        Long y = integer(object, "y");
        if (id == null || name == null || x == null || y == null) return null;
        return new Person(id, name, x.intValue(), y.intValue(), Boolean.TRUE.equals(bool(object, "queued")),
            appearance(object.get("appearance")));
    }

    Appearance appearance(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        try {
            return gson.fromJson(value, Appearance.class);
        } catch (JsonParseException invalid) {
            return null;
        }
    }

    Event event(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        Long id = integer(object, "id");
        Long time = integer(object, "time");
        String kind = string(object, "kind");
        String playerId = string(object, "playerId");
        String name = string(object, "name");
        if (id == null || id <= 0 || time == null || kind == null || playerId == null || name == null) return null;
        return new Event(id, playerId, name, kind, string(object, "text"), time, string(object, "npcId"));
    }

    Preparation preparation(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        String id = string(object, "id");
        Long hp = integer(object, "hp");
        Long food = integer(object, "food");
        if (id == null || hp == null || food == null) return null;
        return new Preparation(id, hp.intValue(), food.intValue());
    }

    Match match(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        String id = string(object, "id");
        String role = string(object, "role");
        String partnerId = string(object, "partnerId");
        String partnerName = string(object, "partnerName");
        if (id == null || !UUID.matcher(id).matches() || !("host".equals(role) || "guest".equals(role)) ||
            partnerId == null || partnerName == null) return null;
        return new Match(id, role, partnerId, partnerName);
    }

    /** Validates a `link` frame; null when it cannot be used to start the run. */
    Link link(JsonObject frame) {
        String id = string(frame, "id");
        String role = string(frame, "role");
        String selfId = string(frame, "selfId");
        String selfName = string(frame, "selfName");
        String partnerId = string(frame, "partnerId");
        String partnerName = string(frame, "partnerName");
        Long seed = integer(frame, "seed");
        Boolean friendlyFire = bool(frame, "friendlyFire");
        Long protocol = integer(frame, "dungeonProtocol");
        if (id == null || !("host".equals(role) || "guest".equals(role)) || selfId == null || selfName == null ||
            partnerId == null || partnerName == null || seed == null || seed < 0 || seed > 0xFFFF_FFFFL ||
            friendlyFire == null || protocol == null) return null;
        String theme = string(frame, "theme");
        Appearance self = appearance(frame.get("selfAppearance"));
        Appearance partner = appearance(frame.get("partnerAppearance"));
        return new Link(id, role, selfId, selfName, self == null ? null : AvatarProfile.normalize(self),
            preparation(frame.get("selfPreparation")), partnerId, partnerName,
            partner == null ? null : AvatarProfile.normalize(partner), preparation(frame.get("partnerPreparation")),
            seed, friendlyFire, theme == null ? SEWERS : theme, protocol.intValue(),
            Boolean.TRUE.equals(bool(frame, "paired")), Boolean.TRUE.equals(bool(frame, "partnerConnected")),
            Boolean.TRUE.equals(bool(frame, "partnerReady")));
    }

    static JsonObject frame(String type) {
        JsonObject frame = new JsonObject();
        frame.addProperty("t", type);
        return frame;
    }
}
