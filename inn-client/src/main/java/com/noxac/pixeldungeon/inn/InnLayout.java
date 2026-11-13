// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

record InnLayout(String id, List<String> map, List<InnApi.Npc> npcs, List<Spawn> spawns, Gate expeditionGate) {
    private static final int MAX_SIZE = 128;
    private static final Pattern TILES = Pattern.compile("[#.BTND]+");
    private static final Pattern NPC_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

    record Spawn(int x, int y) {}

    record Gate(int left, int top, int right, int bottom) {
        boolean contains(int x, int y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }

        boolean near(int x, int y) {
            return x >= left - 1 && x <= right && y >= Math.max(top, bottom - 2) && y <= bottom;
        }
    }

    static InnLayout load() {
        try (InputStream source = InnLayout.class.getResourceAsStream("/inn/inn-layout.json")) {
            if (source == null) throw new IOException("Missing inn-layout.json");
            InnLayout layout = new Gson().fromJson(new InputStreamReader(source, StandardCharsets.UTF_8), InnLayout.class);
            if (layout == null || !layout.valid()) throw new IOException("Invalid inn-layout.json");
            return new InnLayout(layout.geometryId(), List.copyOf(layout.map), List.copyOf(layout.npcs),
                List.copyOf(layout.spawns), layout.expeditionGate);
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Could not load the inn layout", error);
        }
    }

    Map<String, Object> joinDescriptor() {
        Spawn spawn = spawns.get(ThreadLocalRandom.current().nextInt(spawns.size()));
        return Map.of("id", id, "x", spawn.x(), "y", spawn.y());
    }

    static boolean walkable(List<String> map, int x, int y) {
        return inMap(map, x, y) && (map.get(y).charAt(x) == '.' || map.get(y).charAt(x) == 'D');
    }

    private static boolean inMap(List<String> map, int x, int y) {
        return y >= 0 && y < map.size() && x >= 0 && x < map.get(y).length();
    }

    private boolean valid() {
        if (map == null || map.isEmpty() || map.size() > MAX_SIZE || map.getFirst() == null ||
            map.getFirst().length() > MAX_SIZE || map.stream().anyMatch(row ->
                row == null || row.length() != map.getFirst().length() || !TILES.matcher(row).matches()) ||
            npcs == null || spawns == null || spawns.isEmpty() || spawns.stream().anyMatch(spawn ->
                spawn == null || !walkable(map, spawn.x(), spawn.y()))) return false;
        var ids = new HashSet<String>();
        for (InnApi.Npc npc : npcs) {
            if (npc == null || npc.id() == null || !NPC_ID.matcher(npc.id()).matches() || !ids.add(npc.id()) ||
                npc.name() == null || npc.role() == null || npc.dialogue() == null ||
                !inMap(map, npc.x(), npc.y()) || map.get(npc.y()).charAt(npc.x()) != 'N') return false;
        }
        return expeditionGate == null || (inMap(map, expeditionGate.left(), expeditionGate.top()) &&
            inMap(map, expeditionGate.right(), expeditionGate.bottom()) &&
            expeditionGate.contains(expeditionGate.left(), expeditionGate.top()));
    }

    private String geometryId() throws NoSuchAlgorithmException {
        String points = npcs.stream().map(npc -> npc.id() + " " + npc.x() + " " + npc.y())
            .sorted().collect(Collectors.joining("\n"));
        String geometry = "inn-layout-v1\n" + String.join("\n", map) + "\n\n" + points + "\n";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(geometry.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
