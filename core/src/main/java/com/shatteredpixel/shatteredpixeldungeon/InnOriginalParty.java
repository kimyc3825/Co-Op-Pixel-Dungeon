// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.TurnClock;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnPartyMember;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Original-engine party link. The host runs actors and the guest sends intents.
 *
 * Guest command: {@code C|floor|seq|kind|first|second}, kind M (step dx,dy), A (attack actor id),
 * T (step toward or attack a cell), W (wait one turn, both values 0), and for the companion's own items
 * E (equip bag index), R (unequip slot {@link InnPartyMember#SLOT_WEAPON} or {@link InnPartyMember#SLOT_ARMOR}),
 * U (use bag index; second is the upgrade target for a scroll of upgrade, else 0), H (throw bag index at cell),
 * D (drop bag index), G (give bag index to the host).
 * The guest may have several commands in flight; the host queues them and carries them out one per companion turn
 * in sequence order, acknowledging each (ack is the last sequence carried out or dropped).
 * Host snapshot: {@code S|revision|floor|depth|branch|width|height|ack|turn|player|tiles|actors|loot|result
 * |texture|grace|gear|hud|trail}, where turn says whose input the world is waiting for with a countdown
 * ({@link TurnClock#WAIT_NONE}, {@link TurnClock#WAIT_HOST}, {@link TurnClock#WAIT_GUEST}), grace is the
 * remaining countdown in tenths of a second, gear lists the companion's items as {@code slot,image,quantity,
 * flags,title} records (slot {@code w}, {@code a} or a bag index; title escaped, see {@link #escape}) and hud is
 * {@code level,gold,strength} of the party. Trail lists the recent steps of the shown actors as
 * {@code seq,id,cell} records, oldest first, so the guest can replay every step in order like the inn does; each
 * step is repeated in {@link #TRAIL_SNAPSHOTS} snapshots and the guest skips sequences it has already seen.
 */
public final class InnOriginalParty {
    private static final String DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final int MAX_CELLS = 16000;
    private static final int MAX_PACKET = 20000;
    /** The host sends at most this often (about 10 frames/s). */
    private static final float MIN_SNAPSHOT_INTERVAL = 0.1f;
    /** Snapshot heartbeat while nothing changes; it also corrects the guest's local countdown. */
    private static final float HEARTBEAT_INTERVAL = 1f;
    /** The transport is reliable and ordered; the guest only resends pending commands as a safety net. */
    private static final float RETRANSMIT_INTERVAL = 1f;
    /**
     * Answers to guest commands may go out this soon after the previous snapshot. Together with the regular
     * snapshots this stays at most 20 packets a second, under the party link's 25 a second.
     */
    private static final float MIN_ANSWER_INTERVAL = 0.05f;
    /** Commands the host keeps queued; a well-behaved guest never has more than a few in flight. */
    static final int MAX_QUEUED = 8;
    static final int MAX_DEPTH = 30;
    static final int MAX_BRANCH = 9;
    static final int MAX_FLOOR = 100000;
    private static final int COMMAND_FIELDS = 6;
    private static final int SNAPSHOT_FIELDS = 19;
    /**
     * Snapshots a recorded step is repeated in. A snapshot can be replaced by a newer one on the way (the web
     * client sends only the newest, and the guest reads only the newest), so a single copy could be lost.
     */
    static final int TRAIL_SNAPSHOTS = 4;
    /** Most steps sent in one snapshot. */
    static final int MAX_TRAIL = 96;
    /** Most steps the host keeps between snapshots, including those of actors the guest cannot see. */
    private static final int MAX_RECORDED = 512;
    /** Upper bound of the grace field, in tenths of a second. */
    public static final int MAX_GRACE_TENTHS = Math.round(com.shatteredpixel.shatteredpixeldungeon.actors.TurnPolicy.TURN_GRACE_SECONDS * 10);
    public static final String DETECTION_RECORD = "^";
    /** Gear flags: what the guest may do with an item. */
    public static final int FLAG_WEAPON = 1, FLAG_ARMOR = 2, FLAG_DRINK = 4, FLAG_THROW = 8, FLAG_UPGRADE = 16,
            FLAG_CURSED = 32, FLAG_UPGRADABLE = 64;
    static final int MAX_TITLE = 60;
    static final int MAX_IMAGE = 512;

    private static volatile InnOriginalParty current;
    public final boolean host;
    public final long seed;
    public final boolean friendlyFire;
    public final InnAdventure.Profile peer;
    private final Consumer<String> send;
    /** Host: queued guest commands of the current floor, in sequence order. Guarded by this. */
    private final ArrayDeque<Command> inbound = new ArrayDeque<>();
    private int lastQueued;
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
    private volatile int revision;
    /** Host: steps recorded on the actor thread as {seq, actor id, cell, first snapshot}, oldest first. Guarded by itself. */
    private final ArrayDeque<int[]> moves = new ArrayDeque<>();
    private int moveSequence;
    /** Host: ids of the actors in the last snapshot body; only their steps are sent. */
    private final HashSet<Integer> shownIds = new HashSet<>();
    private float sendIn;
    // Host: time since the last snapshot, whether the guest's last command must be answered, last content.
    private float sinceSent = HEARTBEAT_INTERVAL;
    private float sinceChecked = MIN_SNAPSHOT_INTERVAL;
    private volatile boolean answerNow = true;
    private String lastBody;
    private int nextSequence;
    /** Guest: commands sent and not acknowledged yet, oldest first. */
    private final ArrayDeque<Command> pending = new ArrayDeque<>();

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
        // Resends of queued or answered commands are ignored; so is anything past a full queue.
        if (seq <= Math.max(ack, lastQueued) || inbound.size() >= MAX_QUEUED) return;
        inbound.addLast(new Command(floor, seq, kind, first, second));
        lastQueued = seq;
    }

    static boolean validCommand(String kind, int first, int second) {
        switch (kind) {
            case "M": return first >= -1 && first <= 1 && second >= -1 && second <= 1 && (first != 0 || second != 0);
            case "A": return first > 0 && second == 0;
            case "T": return first >= 0 && first < MAX_CELLS && second == 0;
            case "W": return first == 0 && second == 0;
            case "E": case "D": case "G": return validIndex(first) && second == 0;
            case "R": return (first == InnPartyMember.SLOT_WEAPON || first == InnPartyMember.SLOT_ARMOR) && second == 0;
            case "U": return validIndex(first) && (second == 0 || second == InnPartyMember.UPGRADE_WEAPON
                    || second == InnPartyMember.UPGRADE_ARMOR);
            case "H": return validIndex(first) && second >= 0 && second < MAX_CELLS;
            default: return false;
        }
    }

    private static boolean validIndex(int index) {
        return index >= 0 && index < InnPartyMember.BAG_SIZE;
    }

    /** Called during the original floor transition, before registering its actors. */
    public static void enterFloor(Level level, int entrance) {
        InnOriginalParty link = current;
        if (link == null || !link.host) return;
        synchronized (link) {
            link.floor++;
            link.inbound.clear();
        }
        link.level = level;
        link.visible = new boolean[level.length()];
        link.explored = new boolean[level.length()];
        link.rememberedTerrain = new int[level.length()];
        link.answerNow = true;
        link.lastBody = null;
        synchronized (link.moves) {
            link.moves.clear();
        }
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
        if (member == null) {
            member = new InnPartyMember();
            member.equipStarterKit(peer.heroClass());
        }
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
    public synchronized boolean hasPendingCommand() {
        return acceptsCommands() && nextCommand() != null;
    }

    /** The oldest queued command of the current floor, dropping stale ones; null if there is none. */
    private Command nextCommand() {
        while (!inbound.isEmpty()) {
            Command command = inbound.peekFirst();
            if (command.floor == floor && command.seq > ack) return command;
            inbound.pollFirst();
        }
        return null;
    }

    /**
     * Consumes the guest's oldest queued command on the actor thread. Returns false if there was none; a rejected
     * command is still acknowledged so the guest stops resending it.
     */
    public boolean act(InnPartyMember player) {
        Command command;
        synchronized (this) {
            command = acceptsCommands() ? nextCommand() : null;
            if (command == null) return false;
            inbound.pollFirst();
            ack = command.seq;
        }
        // Answer the guest at the next opportunity so its following step is not held up.
        answerNow = true;
        if (command.kind.equals("T") && (!level.insideMap(command.first)
                || (!explored[command.first] && !level.adjacent(player.pos, command.first)))) {
            return true;
        }
        player.execute(command.kind, command.first, command.second, friendlyFire);
        return true;
    }

    /**
     * Acknowledges and drops the queued commands the member cannot carry out (e.g. while paralysed), so held
     * movement does not replay once it can move again.
     */
    public synchronized void dropPending() {
        Command last = null;
        for (Command command; (command = nextCommand()) != null; inbound.pollFirst()) last = command;
        if (last != null) {
            ack = last.seq;
            answerNow = true;
        }
    }

    /**
     * Host, render thread: sends a snapshot while the actor thread is idle, when its content changed, after a
     * guest command was answered, or as a heartbeat; never more often than {@link #MIN_SNAPSHOT_INTERVAL}, or
     * {@link #MIN_ANSWER_INTERVAL} for an answer.
     * The remaining grace is not content: the guest counts it down locally between snapshots.
     */
    public void updateHost(float elapsed) {
        if (member == null || level != Dungeon.level) return;
        sinceSent += elapsed;
        sinceChecked += elapsed;
        if (sinceSent < (answerNow ? MIN_ANSWER_INTERVAL : MIN_SNAPSHOT_INTERVAL) || closed
                || level.length() > MAX_CELLS || !Actor.parked()) return;
        boolean due = answerNow || sinceSent >= HEARTBEAT_INTERVAL;
        // Content is compared at most every MIN_SNAPSHOT_INTERVAL, not on every frame.
        if (!due && sinceChecked < MIN_SNAPSHOT_INTERVAL) return;
        sinceChecked = 0;
        String body = snapshotBody();
        String extra = gearRecords(member) + "|" + hudRecord();
        String content = body + "|" + extra;
        if (!due && content.equals(lastBody)) return;
        answerNow = false;
        lastBody = content;
        sinceSent = 0;
        int next = revision + 1;
        String packet = "S|" + next + "|" + body + "|" + graceTenths() + "|" + extra + "|" + trailRecord(next);
        revision = next;
        if (packet.length() <= MAX_PACKET) send.accept(packet);
    }

    /**
     * Host, actor thread: remembers a step of a character (called from {@link Char#move}) so the guest can replay
     * it cell by cell. Positions changed without a step, such as teleports and knockback, are not recorded and
     * show as a jump.
     */
    public static void recordMove(Char ch) {
        InnOriginalParty link = current;
        if (link == null || !link.host || link.level == null || link.level != Dungeon.level) return;
        link.addMove(ch.id(), ch.pos);
    }

    /** Records a step for the next snapshot. */
    void addMove(int id, int cell) {
        synchronized (moves) {
            moves.addLast(new int[]{++moveSequence, id, cell, revision + 1});
            while (moves.size() > MAX_RECORDED) moves.pollFirst();
        }
    }

    /** The trail field of snapshot {@code next}: the newest steps of shown actors from the last few snapshots. */
    String trailRecord(int next) {
        List<int[]> shown = new ArrayList<>();
        synchronized (moves) {
            while (!moves.isEmpty() && moves.peekFirst()[3] <= next - TRAIL_SNAPSHOTS) moves.pollFirst();
            for (int[] move : moves) if (shownIds.contains(move[1])) shown.add(move);
        }
        StringBuilder result = new StringBuilder();
        for (int[] move : shown.subList(Math.max(0, shown.size() - MAX_TRAIL), shown.size())) {
            if (result.length() > 0) result.append(';');
            result.append(move[0]).append(',').append(move[1]).append(',').append(move[2]);
        }
        return result.toString();
    }

    /** Parses trail records as {seq, actor id, cell}, oldest first, skipping invalid ones. */
    public static List<int[]> parseTrail(String trail) {
        List<int[]> result = new ArrayList<>();
        if (trail.isEmpty()) return result;
        for (String row : trail.split(";")) {
            String[] columns = row.split(",", -1);
            if (columns.length != 3 || result.size() >= MAX_TRAIL) continue;
            try {
                int seq = Integer.parseInt(columns[0]);
                int id = Integer.parseInt(columns[1]);
                int cell = Integer.parseInt(columns[2]);
                if (seq < 1 || id < 1 || cell < 0 || cell >= MAX_CELLS) continue;
                result.add(new int[]{seq, id, cell});
            } catch (NumberFormatException ignored) {
                // Skip invalid trail records.
            }
        }
        return result;
    }

    public Snapshot latest() { return incoming; }

    public boolean[] explored() { return explored; }

    /**
     * Sends a command to the host without waiting for earlier ones to be answered. Returns its sequence, or 0
     * when none was sent.
     */
    public int command(String kind, int first, int second) {
        Snapshot state = incoming;
        if (state == null || closed || !state.result.equals("play")) return 0;
        prunePending(state);
        Command command = new Command(state.floor, ++nextSequence, kind, first, second);
        if (pending.isEmpty()) sendIn = RETRANSMIT_INTERVAL;
        pending.addLast(command);
        send.accept(command.encode());
        return command.seq;
    }

    private void prunePending(Snapshot state) {
        while (!pending.isEmpty() && (pending.peekFirst().floor != state.floor || pending.peekFirst().seq <= state.ack)) {
            pending.pollFirst();
            sendIn = RETRANSMIT_INTERVAL;
        }
    }

    /** Guest: resends the unanswered commands, in order, until the host acknowledges them. */
    public void updateGuest(float elapsed) {
        Snapshot state = incoming;
        if (state == null || closed) return;
        prunePending(state);
        if (pending.isEmpty()) return;
        sendIn -= elapsed;
        if (sendIn <= 0) {
            for (Command command : pending) send.accept(command.encode());
            sendIn = RETRANSMIT_INTERVAL;
        }
    }

    /** Guest: commands sent for the current floor that the host has not answered yet. */
    public int inFlight() {
        Snapshot state = incoming;
        if (state == null) return 0;
        int count = 0;
        for (Command command : pending) if (command.floor == state.floor && command.seq > state.ack) count++;
        return count;
    }

    public boolean awaitingAction() {
        return inFlight() > 0;
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
        shownIds.clear();
        appendActor(actors, Dungeon.hero, Dungeon.hero.heroClass.name(), 1, Dungeon.hero.pos);
        appendActor(actors, member, peer.heroClass().name(), 1, member.isAlive() ? member.pos : level.entrance());
        shownIds.add(Dungeon.hero.id());
        shownIds.add(member.id());
        for (Mob mob : level.mobs.toArray(new Mob[0])) {
            if (mob != member && mob.isAlive() && visible[mob.pos] && mob.sprite != null) {
                appendActor(actors, mob, mob.sprite.getClass().getName(),
                        mob.alignment == Char.Alignment.ENEMY ? 2 : 0, mob.pos);
                shownIds.add(mob.id());
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

    /** The companion's equipment and bag, see the class comment. */
    static String gearRecords(InnPartyMember member) {
        StringBuilder result = new StringBuilder();
        if (member.weapon() != null) appendGear(result, "w", member.weapon(), true);
        if (member.armor() != null) appendGear(result, "a", member.armor(), true);
        List<Item> bag = member.bag();
        for (int i = 0; i < bag.size(); i++) appendGear(result, Integer.toString(i), bag.get(i), false);
        return result.toString();
    }

    private static void appendGear(StringBuilder result, String slot, Item item, boolean equipped) {
        int flags = 0;
        if (!equipped) {
            if (item instanceof MeleeWeapon) flags |= FLAG_WEAPON;
            if (item instanceof Armor) flags |= FLAG_ARMOR;
            if (InnPartyMember.drinkable(item)) flags |= FLAG_DRINK;
            if (item instanceof Potion) flags |= FLAG_THROW;
            if (InnPartyMember.readableUpgrade(item)) flags |= FLAG_UPGRADE;
        } else if (item.isUpgradable()) {
            flags |= FLAG_UPGRADABLE;
        }
        if (item.cursed && item.cursedKnown) flags |= FLAG_CURSED;
        int image = item.image();
        if (image < 0 || image >= MAX_IMAGE) image = 0;
        String title = item.title();
        if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE);
        if (result.length() > 0) result.append(';');
        result.append(slot).append(',').append(image).append(',').append(item.quantity()).append(',')
                .append(flags).append(',').append(escape(title));
    }

    private static String hudRecord() {
        return Dungeon.hero.lvl + "," + Math.max(0, Dungeon.gold) + "," + Dungeon.hero.STR();
    }

    /** Escapes the record separators of the snapshot ({@code % | ; ,}) as percent codes. */
    static String escape(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '%': result.append("%25"); break;
                case '|': result.append("%7C"); break;
                case ';': result.append("%3B"); break;
                case ',': result.append("%2C"); break;
                default: result.append(c);
            }
        }
        return result.toString();
    }

    static String unescape(String text) {
        return text.replace("%2C", ",").replace("%3B", ";").replace("%7C", "|").replace("%25", "%");
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

    /** One item of the companion as the guest sees it. */
    public static final class Gear {
        /** {@link #GEAR_WEAPON} or {@link #GEAR_ARMOR} for an equipped item, otherwise the bag index. */
        public final int slot;
        public final int image, quantity, flags;
        public final String title;

        Gear(int slot, int image, int quantity, int flags, String title) {
            this.slot = slot;
            this.image = image;
            this.quantity = quantity;
            this.flags = flags;
            this.title = title;
        }

        public boolean equipped() { return slot < 0; }

        public boolean has(int flag) { return (flags & flag) != 0; }
    }

    public static final int GEAR_WEAPON = -1;
    public static final int GEAR_ARMOR = -2;

    /** Parses gear records, skipping invalid ones. */
    static List<Gear> parseGear(String records) {
        List<Gear> result = new ArrayList<>();
        if (records.isEmpty()) return result;
        for (String row : records.split(";")) {
            String[] columns = row.split(",", -1);
            if (columns.length != 5) continue;
            try {
                int slot = columns[0].equals("w") ? GEAR_WEAPON : columns[0].equals("a") ? GEAR_ARMOR
                        : Integer.parseInt(columns[0]);
                int image = Integer.parseInt(columns[1]);
                int quantity = Integer.parseInt(columns[2]);
                int flags = Integer.parseInt(columns[3]);
                String title = unescape(columns[4]);
                if (slot < GEAR_ARMOR || slot >= InnPartyMember.BAG_SIZE || image < 0 || image >= MAX_IMAGE
                        || quantity < 1 || flags < 0 || flags > 127 || title.isEmpty() || title.length() > MAX_TITLE) continue;
                result.add(new Gear(slot, image, quantity, flags, title));
            } catch (NumberFormatException ignored) {
                // Skip invalid gear records.
            }
        }
        return result;
    }

    public static final class Snapshot {
        public final int revision, floor, depth, branch, width, height, ack, player;
        /** The party's level, gold and strength, all from the host hero. */
        public final int level, gold, strength;
        public final List<Gear> gear;
        /** Whose input the world waits for with a countdown, see {@link TurnClock#waitingOn()}. */
        public final int turn;
        /** Remaining countdown in tenths of a second, 0 when {@link #turn} is none. */
        public final int grace;
        public final int[] tiles;
        public final String actors, loot, result, texture;
        /** Recent steps of the shown actors, see {@link #parseTrail}. */
        public final String trail;

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
            gear = parseGear(fields[16]);
            String[] hud = fields[17].split(",", -1);
            level = Integer.parseInt(hud[0]);
            gold = Integer.parseInt(hud[1]);
            strength = Integer.parseInt(hud[2]);
            trail = fields[18];
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
            String[] hud = fields[17].split(",", -1);
            if (hud.length != 3) return null;
            int level = Integer.parseInt(hud[0]);
            int gold = Integer.parseInt(hud[1]);
            int strength = Integer.parseInt(hud[2]);
            if (level < 1 || level > 99 || gold < 0 || strength < 0 || strength > 999) return null;
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
