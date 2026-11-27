// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.TurnClock;
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

/**
 * Original-engine party link. The host runs actors and the guest sends intents.
 *
 * Guest command: {@code C|floor|seq|kind|first|second}, kind M (step dx,dy), A (attack actor id),
 * T (step toward or attack a cell), W (wait one turn, both values 0).
 * Host snapshot: {@code S|revision|floor|depth|branch|width|height|ack|turn|player|tiles|actors|loot|result
 * |texture|grace}, where turn says whose input the world is waiting for with a countdown
 * ({@link TurnClock#WAIT_NONE}, {@link TurnClock#WAIT_HOST}, {@link TurnClock#WAIT_GUEST}) and grace is the
 * remaining countdown in tenths of a second.
 */
public final class InnOriginalParty {
    private static final String DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final int MAX_CELLS = 16000;
    private static final int MAX_PACKET = 20000;
    /** The host sends at most this often (about 10 frames/s). */
    private static final float MIN_SNAPSHOT_INTERVAL = 0.1f;
    /** Snapshot heartbeat while nothing changes; it also corrects the guest's local countdown. */
    private static final float HEARTBEAT_INTERVAL = 1f;
    /** The transport is reliable and ordered; the guest only resends a pending command as a safety net. */
    private static final float RETRANSMIT_INTERVAL = 1f;
    static final int MAX_DEPTH = 30;
    static final int MAX_BRANCH = 9;
    static final int MAX_FLOOR = 100000;
    private static final int COMMAND_FIELDS = 6;
    private static final int SNAPSHOT_FIELDS = 16;
    /** Upper bound of the grace field, in tenths of a second. */
    public static final int MAX_GRACE_TENTHS = Math.round(com.shatteredpixel.shatteredpixeldungeon.actors.TurnPolicy.TURN_GRACE_SECONDS * 10);
    public static final String DETECTION_RECORD = "^";

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
    private volatile int ack;
    private int revision;
    private float sendIn;
    // Host: time since the last snapshot, whether the guest's last command must be answered, last content.
    private float sinceSent = HEARTBEAT_INTERVAL;
    private float sinceChecked = MIN_SNAPSHOT_INTERVAL;
    private volatile boolean answerNow = true;
    private String lastBody;
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
            if (link.host && fields.length == COMMAND_FIELDS && fields[0].equals("C")) {
                int floor = Integer.parseInt(fields[1]);
                int seq = Integer.parseInt(fields[2]);
                String kind = fields[3];
                int first = Integer.parseInt(fields[4]);
                int second = Integer.parseInt(fields[5]);
                link.acceptCommand(floor, seq, kind, first, second);
            } else if (!link.host && fields.length == SNAPSHOT_FIELDS && fields[0].equals("S")) {
                Snapshot state = Snapshot.parse(fields);
                Snapshot previous = link.incoming;
                if (state != null && (previous == null || state.revision > previous.revision)) link.incoming = state;
            }
        } catch (NumberFormatException ignored) {
            // A malformed peer packet does not affect the current run.
        }
    }

    private synchronized void acceptCommand(int floor, int seq, String kind, int first, int second) {
        if (floor != this.floor || seq <= 0 || !validCommand(kind, first, second)) return;
        if (inbound == null || inbound.floor != floor || seq > inbound.seq) {
            inbound = new Command(floor, seq, kind, first, second);
        }
    }

    static boolean validCommand(String kind, int first, int second) {
        switch (kind) {
            case "M": return first >= -1 && first <= 1 && second >= -1 && second <= 1 && (first != 0 || second != 0);
            case "A": return first > 0 && second == 0;
            case "T": return first >= 0 && first < MAX_CELLS && second == 0;
            case "W": return first == 0 && second == 0;
            default: return false;
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
        link.answerNow = true;
        link.lastBody = null;
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

    public InnPartyMember activeMember(Level currentLevel) {
        return host && level == currentLevel && member != null && member.isAlive() ? member : null;
    }

    public static void finish() {
        InnOriginalParty link = current;
        if (link != null && link.host) link.finished = true;
    }

    /** False once the run is over or the link closed; the member then spends its turns. */
    public boolean acceptsCommands() {
        return !closed && !finished;
    }

    /** True while the guest has sent a command the member has not consumed yet. */
    public boolean hasPendingCommand() {
        Command command = inbound;
        return acceptsCommands() && command != null && command.floor == floor && command.seq > ack;
    }

    /**
     * Consumes the guest's pending command on the actor thread. Returns false if there was none; a rejected
     * command is still acknowledged so the guest stops resending it.
     */
    public boolean act(InnPartyMember player) {
        Command command = inbound;
        if (!acceptsCommands() || command == null || command.floor != floor || command.seq <= ack) return false;
        ack = command.seq;
        // Answer the guest at the next opportunity so its following step is not held up.
        answerNow = true;
        if (command.kind.equals("T") && (!level.insideMap(command.first)
                || (!explored[command.first] && !level.adjacent(player.pos, command.first)))) {
            return true;
        }
        player.execute(command.kind, command.first, command.second, friendlyFire);
        return true;
    }

    /** Acknowledges and drops a pending command the member cannot carry out (e.g. while paralysed). */
    public void dropPending() {
        Command command = inbound;
        if (command != null && command.floor == floor && command.seq > ack) {
            ack = command.seq;
            answerNow = true;
        }
    }

    /**
     * Host, render thread: sends a snapshot while the actor thread is idle, when its content changed, after a
     * guest command was answered, or as a heartbeat; never more often than {@link #MIN_SNAPSHOT_INTERVAL}.
     * The remaining grace is not content: the guest counts it down locally between snapshots.
     */
    public void updateHost(float elapsed) {
        if (member == null || level != Dungeon.level) return;
        sinceSent += elapsed;
        sinceChecked += elapsed;
        if (sinceSent < MIN_SNAPSHOT_INTERVAL || closed || level.length() > MAX_CELLS || !Actor.parked()) return;
        boolean due = answerNow || sinceSent >= HEARTBEAT_INTERVAL;
        // Content is compared at most every MIN_SNAPSHOT_INTERVAL, not on every frame.
        if (!due && sinceChecked < MIN_SNAPSHOT_INTERVAL) return;
        sinceChecked = 0;
        String body = snapshotBody();
        if (!due && body.equals(lastBody)) return;
        answerNow = false;
        lastBody = body;
        sinceSent = 0;
        String packet = "S|" + ++revision + "|" + body + "|" + graceTenths();
        if (packet.length() <= MAX_PACKET) send.accept(packet);
    }

    public Snapshot latest() { return incoming; }

    public boolean[] explored() { return explored; }

    /** Returns the sequence of the command sent to the host, or 0 when none was sent. */
    public int command(String kind, int first, int second) {
        Snapshot state = incoming;
        if (state == null || closed || !state.result.equals("play")) return 0;
        pending = new Command(state.floor, ++nextSequence, kind, first, second);
        sendGuest(state);
        return pending.seq;
    }

    private void sendGuest(Snapshot state) {
        if (pending == null || pending.floor != state.floor) return;
        send.accept(pending.encode());
        sendIn = RETRANSMIT_INTERVAL;
    }

    /** Guest: resends the pending command until the host acknowledges it. */
    public void updateGuest(float elapsed) {
        Snapshot state = incoming;
        if (state == null || closed) return;
        if (pending != null && (pending.floor != state.floor || pending.seq <= state.ack)) {
            pending = null;
        }
        if (pending == null) return;
        sendIn -= elapsed;
        if (sendIn <= 0) {
            sendGuest(state);
        }
    }

    public boolean awaitingAction() {
        Snapshot state = incoming;
        return pending != null && state != null && pending.floor == state.floor && pending.seq > state.ack;
    }

    private static int graceTenths() {
        if (TurnClock.waitingOn() == TurnClock.WAIT_NONE) return 0;
        return Math.max(0, Math.min(MAX_GRACE_TENTHS, (int) Math.ceil(TurnClock.graceRemaining() * 10 - 0.001f)));
    }

    // Number of snapshot bodies built, for the send-rate tests.
    int bodiesBuilt;

    /** Snapshot fields from floor to texture: everything but the revision and the remaining grace. */
    private String snapshotBody() {
        bodiesBuilt++;
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
        StringBuilder detections = new StringBuilder();
        appendActor(actors, Dungeon.hero, Dungeon.hero.heroClass.name(), 1, Dungeon.hero.pos);
        appendActor(actors, member, peer.heroClass().name(), 1, member.isAlive() ? member.pos : level.entrance());
        for (Mob mob : level.mobs.toArray(new Mob[0])) {
            if (mob != member && mob.isAlive() && visible[mob.pos] && mob.sprite != null) {
                appendActor(actors, mob, mob.sprite.getClass().getName(),
                        mob.alignment == Char.Alignment.ENEMY ? 2 : 0, mob.pos);
                if (mob.alignment == Char.Alignment.ENEMY && mob.sprite.isShowingAlert()) {
                    appendActorId(detections, mob);
                }
            }
        }
        if (detections.length() > 0) actors.append(';').append(DETECTION_RECORD).append(detections);
        StringBuilder loot = new StringBuilder();
        for (Heap heap : level.heaps.valueList()) {
            if (heap.hidden || !visible[heap.pos] || heap.peek() == null) continue;
            if (loot.length() > 0) loot.append(';');
            loot.append(heap.pos).append(',').append(heapImage(heap));
        }

        String result = finished || !Dungeon.hero.isAlive() ? "end" : "play";
        return floor + "|" + Dungeon.depth + "|" + Dungeon.branch + "|"
                + level.width() + "|" + level.height() + "|" + ack + "|" + TurnClock.waitingOn()
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

    private static void appendActorId(StringBuilder result, Char ch) {
        if (result.length() > 0) result.append(',');
        result.append(ch.id());
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
        public final int revision, floor, depth, branch, width, height, ack, player;
        /** Whose input the world waits for with a countdown, see {@link TurnClock#waitingOn()}. */
        public final int turn;
        /** Remaining countdown in tenths of a second, 0 when {@link #turn} is none. */
        public final int grace;
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
            turn = Integer.parseInt(fields[8]);
            player = Integer.parseInt(fields[9]);
            this.tiles = tiles;
            actors = fields[11];
            loot = fields[12];
            result = fields[13];
            texture = fields[14];
            grace = Integer.parseInt(fields[15]);
        }

        static Snapshot parse(String[] fields) {
            if (fields.length != SNAPSHOT_FIELDS || !fields[0].equals("S")) return null;
            int revision = Integer.parseInt(fields[1]);
            int floor = Integer.parseInt(fields[2]);
            int depth = Integer.parseInt(fields[3]);
            int branch = Integer.parseInt(fields[4]);
            int width = Integer.parseInt(fields[5]);
            int height = Integer.parseInt(fields[6]);
            int ack = Integer.parseInt(fields[7]);
            int turn = Integer.parseInt(fields[8]);
            int player = Integer.parseInt(fields[9]);
            int grace = Integer.parseInt(fields[15]);
            // The guest seeds its terrain variance from depth and branch, which loops over them: keep them small.
            if (revision < 1 || floor < 1 || floor > MAX_FLOOR || depth < 1 || depth > MAX_DEPTH
                    || branch < 0 || branch > MAX_BRANCH || ack < 0 || player < 1) return null;
            if (width < 3 || width > 256 || height < 3 || height > 256 || width * height > MAX_CELLS) return null;
            if (turn < TurnClock.WAIT_NONE || turn > TurnClock.WAIT_GUEST || grace < 0 || grace > MAX_GRACE_TENTHS
                    || turn == TurnClock.WAIT_NONE && grace != 0) return null;
            if (!fields[13].equals("play") && !fields[13].equals("end")) return null;
            if (!fields[14].startsWith("environment/tiles_") || !fields[14].endsWith(".png")
                    || fields[14].contains("..") || fields[14].substring(12).contains("/")) return null;
            int[] tiles = decodeTiles(fields[10], width * height);
            return tiles == null ? null : new Snapshot(fields, width, height, tiles);
        }
    }
}
