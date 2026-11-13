// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnPartyMember;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;

import java.util.Arrays;
import java.util.function.Consumer;

/** Original-engine party link. The host runs actors and the guest sends intents. */
public final class InnOriginalParty {
    private static final String DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final int MAX_CELLS = 16000;
    private static final int MAX_PACKET = 20000;
    private static final float UPDATE_INTERVAL = 0.12f;

    private static volatile InnOriginalParty current;
    public final boolean host;
    public final long seed;
    public final boolean friendlyFire;
    public final InnAdventure.Profile peer;
    private final Consumer<String> send;
    private volatile Command inbound;
    private volatile Snapshot incoming;
    private volatile boolean closed;
    private boolean finished;
    private volatile int floor;
    private Level level;
    private InnPartyMember member;
    private boolean[] visible;
    private boolean[] explored;
    private int[] rememberedTerrain;
    private int ack;
    private int revision;
    private float sendIn;
    private int nextSequence;
    private Command pending;

    private InnOriginalParty(boolean host, InnAdventure.Profile peer, long seed,
                             boolean friendlyFire, Consumer<String> send) {
        this.host = host;
        this.peer = peer;
        this.seed = seed;
        this.friendlyFire = friendlyFire;
        this.send = send;
    }

    public static void configure(boolean host, String name, String job, String hair, String outfit,
                                 String style, String accessory, long seed, boolean friendlyFire,
                                 Consumer<String> sender) {
        current = new InnOriginalParty(host, InnAdventure.makeProfile(name, job, hair, outfit, style, accessory),
                seed, friendlyFire, sender);
    }

    public static InnOriginalParty current() { return current; }

    static void clear() { current = null; }

    public static void close() {
        InnOriginalParty link = current;
        if (link != null) link.closed = true;
    }

    public static void receive(String payload) {
        InnOriginalParty link = current;
        if (link == null || payload == null || payload.length() > MAX_PACKET || link.closed) return;
        String[] fields = payload.split("\\|", -1);
        try {
            if (link.host && fields.length == 6 && fields[0].equals("C")) {
                int floor = Integer.parseInt(fields[1]);
                int seq = Integer.parseInt(fields[2]);
                String kind = fields[3];
                int first = Integer.parseInt(fields[4]);
                int second = Integer.parseInt(fields[5]);
                if (floor == link.floor && seq > 0 && (kind.equals("M") || kind.equals("A") || kind.equals("T"))) {
                    Command previous = link.inbound;
                    if (previous == null || seq > previous.seq) {
                        link.inbound = new Command(floor, seq, kind, first, second);
                    }
                }
            } else if (!link.host && fields.length == 15 && fields[0].equals("S")) {
                Snapshot state = Snapshot.parse(fields);
                Snapshot previous = link.incoming;
                if (state != null && (previous == null || state.revision > previous.revision)) link.incoming = state;
            }
        } catch (NumberFormatException ignored) {
            // A malformed peer packet does not affect the current run.
        }
    }

    /** Called during the original floor transition, before registering its actors. */
    public static void enterFloor(Level level, int entrance) {
        InnOriginalParty link = current;
        if (link == null || !link.host) return;
        link.floor++;
        link.level = level;
        link.visible = new boolean[level.length()];
        link.explored = new boolean[level.length()];
        link.rememberedTerrain = new int[level.length()];
        link.sendIn = 0;
        for (Mob mob : level.mobs) {
            if (mob instanceof InnPartyMember) {
                link.member = (InnPartyMember) mob;
                return;
            }
        }
        if (link.member != null && !link.member.isAlive()) return;
        for (int offset : PathFinder.NEIGHBOURS8) {
            int cell = entrance + offset;
            if (canSpawn(level, cell)) {
                link.placeMember(level, cell);
                return;
            }
        }
        PathFinder.buildDistanceMap(entrance, level.passable);
        int nearest = -1;
        for (int cell = 0; cell < level.length(); cell++) {
            int distance = PathFinder.distance[cell];
            if (distance > 0 && distance < Integer.MAX_VALUE
                    && (nearest < 0 || distance < PathFinder.distance[nearest]) && canSpawn(level, cell)) nearest = cell;
        }
        if (nearest >= 0) link.placeMember(level, nearest);
    }

    private static boolean canSpawn(Level level, int cell) {
        return level.insideMap(cell) && level.passable[cell] && !level.avoid[cell]
                && level.findMob(cell) == null && level.traps.get(cell) == null;
    }

    private void placeMember(Level level, int cell) {
        if (member == null) member = new InnPartyMember();
        member.pos = cell;
        level.mobs.add(member);
    }

    public boolean isClosed() { return closed; }

    public static void finish() {
        InnOriginalParty link = current;
        if (link != null && link.host) link.finished = true;
    }

    /** All authoritative input is consumed by the original actor scheduler. */
    public void act(InnPartyMember player) {
        Command command = inbound;
        if (closed || finished || command == null || command.floor != floor || command.seq <= ack) {
            player.waitForInput();
            return;
        }
        if (command.kind.equals("T") && (!level.insideMap(command.first)
                || (!explored[command.first] && !level.adjacent(player.pos, command.first)))) {
            ack = command.seq;
            player.waitForInput();
            return;
        }
        if (player.execute(command.kind, command.first, command.second, friendlyFire)) {
            ack = command.seq;
        } else {
            player.waitForInput();
        }
    }

    public void updateHost(float elapsed, boolean paused) {
        if (member == null || level != Dungeon.level) return;
        if (!paused) member.regenerate(elapsed);
        sendIn -= elapsed;
        if (sendIn > 0 || closed || level.length() > MAX_CELLS || Actor.processing()) return;
        sendIn = UPDATE_INTERVAL;
        String packet = snapshot();
        if (packet.length() <= MAX_PACKET) send.accept(packet);
    }

    public Snapshot latest() { return incoming; }

    public boolean[] explored() { return explored; }

    public void command(String kind, int first, int second) {
        Snapshot state = incoming;
        if (state == null || closed || !state.result.equals("play")) return;
        pending = new Command(state.floor, ++nextSequence, kind, first, second);
        send.accept(pending.encode());
        sendIn = UPDATE_INTERVAL;
    }

    public void updateGuest(float elapsed) {
        Snapshot state = incoming;
        if (pending == null || state == null || closed) return;
        if (pending.floor != state.floor || pending.seq <= state.ack) {
            pending = null;
            return;
        }
        sendIn -= elapsed;
        if (sendIn <= 0) {
            send.accept(pending.encode());
            sendIn = UPDATE_INTERVAL;
        }
    }

    public boolean awaitingAction() {
        Snapshot state = incoming;
        return pending != null && state != null && pending.floor == state.floor && pending.seq > state.ack;
    }

    private String snapshot() {
        Arrays.fill(visible, false);
        if (member.isAlive()) level.updateFieldOfView(member, visible);
        int[] tiles = new int[level.length()];
        for (int cell = 0; cell < tiles.length; cell++) {
            explored[cell] |= visible[cell];
            if (!explored[cell]) continue;
            if (visible[cell]) {
                int terrain = level.map[cell];
                if (terrain == Terrain.SECRET_DOOR) terrain = Terrain.WALL;
                if (terrain == Terrain.SECRET_TRAP) terrain = Terrain.EMPTY;
                rememberedTerrain[cell] = terrain;
            }
            tiles[cell] = 1 + rememberedTerrain[cell] + (visible[cell] ? 0 : 64);
        }

        StringBuilder actors = new StringBuilder();
        appendActor(actors, Dungeon.hero, Dungeon.hero.heroClass.name(), 1, Dungeon.hero.pos);
        appendActor(actors, member, peer.heroClass().name(), 1, member.isAlive() ? member.pos : level.entrance());
        for (Mob mob : level.mobs.toArray(new Mob[0])) {
            if (mob != member && mob.isAlive() && visible[mob.pos] && mob.sprite != null) {
                appendActor(actors, mob, mob.sprite.getClass().getName(),
                        mob.alignment == Char.Alignment.ENEMY ? 2 : 0, mob.pos);
            }
        }
        StringBuilder loot = new StringBuilder();
        for (Heap heap : level.heaps.valueList()) {
            if (heap.hidden || !visible[heap.pos] || heap.peek() == null) continue;
            if (loot.length() > 0) loot.append(';');
            loot.append(heap.pos).append(',').append(heapImage(heap));
        }

        String result = finished || !Dungeon.hero.isAlive() ? "end" : "play";
        return "S|" + ++revision + "|" + floor + "|" + Dungeon.depth + "|" + Dungeon.branch + "|"
                + level.width() + "|" + level.height() + "|" + ack + "|" + Math.round(member.energy() * 10)
                + "|" + member.id() + "|" + encodeTiles(tiles) + "|" + actors + "|" + loot
                + "|" + result + "|" + level.tilesTex();
    }

    private static int heapImage(Heap heap) {
        switch (heap.type) {
            case CHEST: return ItemSpriteSheet.CHEST;
            case LOCKED_CHEST: return ItemSpriteSheet.LOCKED_CHEST;
            case CRYSTAL_CHEST: return ItemSpriteSheet.CRYSTAL_CHEST;
            case TOMB: return ItemSpriteSheet.TOMB;
            case SKELETON: return ItemSpriteSheet.BONES;
            case REMAINS: return ItemSpriteSheet.REMAINS;
            default: return heap.peek().image();
        }
    }

    private static void appendActor(StringBuilder result, Char ch, String sprite, int kind, int cell) {
        if (result.length() > 0) result.append(';');
        result.append(ch.id()).append(',').append(cell).append(',').append(Math.max(0, ch.HP)).append(',')
                .append(ch.HT).append(',').append(kind).append(',').append(sprite);
    }

    static String encodeTiles(int[] tiles) {
        StringBuilder text = new StringBuilder();
        for (int start = 0; start < tiles.length;) {
            int count = 1;
            int value = tiles[start];
            while (start + count < tiles.length && tiles[start + count] == value && count < 64) count++;
            text.append(DIGITS.charAt(count - 1)).append(DIGITS.charAt(value >> 6)).append(DIGITS.charAt(value & 63));
            start += count;
        }
        return text.toString();
    }

    private static int[] decodeTiles(String encoded, int size) {
        if (encoded.length() % 3 != 0) return null;
        int[] tiles = new int[size];
        int position = 0;
        for (int i = 0; i < encoded.length(); i += 3) {
            int run = DIGITS.indexOf(encoded.charAt(i)) + 1;
            int high = DIGITS.indexOf(encoded.charAt(i + 1));
            int low = DIGITS.indexOf(encoded.charAt(i + 2));
            int value = high * 64 + low;
            if (run <= 0 || high < 0 || low < 0 || value > 104 || position + run > size) return null;
            Arrays.fill(tiles, position, position + run, value);
            position += run;
        }
        return position == size ? tiles : null;
    }

    private static final class Command {
        final int floor, seq, first, second;
        final String kind;

        Command(int floor, int seq, String kind, int first, int second) {
            this.floor = floor;
            this.seq = seq;
            this.kind = kind;
            this.first = first;
            this.second = second;
        }

        String encode() { return "C|" + floor + "|" + seq + "|" + kind + "|" + first + "|" + second; }
    }

    public static final class Snapshot {
        public final int revision, floor, depth, branch, width, height, ack, energy, player;
        public final int[] tiles;
        public final String actors, loot, result, texture;

        private Snapshot(String[] fields, int width, int height, int[] tiles) {
            revision = Integer.parseInt(fields[1]);
            floor = Integer.parseInt(fields[2]);
            depth = Integer.parseInt(fields[3]);
            branch = Integer.parseInt(fields[4]);
            this.width = width;
            this.height = height;
            ack = Integer.parseInt(fields[7]);
            energy = Integer.parseInt(fields[8]);
            player = Integer.parseInt(fields[9]);
            this.tiles = tiles;
            actors = fields[11];
            loot = fields[12];
            result = fields[13];
            texture = fields[14];
        }

        private static Snapshot parse(String[] fields) {
            int width = Integer.parseInt(fields[5]);
            int height = Integer.parseInt(fields[6]);
            if (width < 3 || width > 256 || height < 3 || height > 256 || width * height > MAX_CELLS) return null;
            if (!fields[14].startsWith("environment/tiles_") || !fields[14].endsWith(".png")
                    || fields[14].contains("..") || fields[14].substring(12).contains("/")) return null;
            int[] tiles = decodeTiles(fields[10], width * height);
            return tiles == null ? null : new Snapshot(fields, width, height, tiles);
        }
    }
}
