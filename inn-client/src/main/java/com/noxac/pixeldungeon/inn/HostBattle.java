// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

/** The match's only simulation, run on the host session's executor. */
final class HostBattle {
    private static final int RAT_MOVE_COOLDOWN_MS = 380;
    private static final int AWARENESS = 4;
    private static final int TRAINING_RANGE = 2;
    private static final int TRAINING_DUMMY_HP = 16;
    private static final int TRAINING_RATIONS = 3;
    private static final String RATION_RACK = "ration-rack";
    private static final String RECOVERY_KIT = "recovery-kit";
    private static final long PAUSE_LIMIT_MS = 60_000;
    private static final long PAUSE_COOLDOWN_MS = 60_000;
    private static final int CHAT_LIMIT = 160;
    private static final int CHAT_HISTORY = 16;
    private static final int ATTACK_HISTORY = 16;
    private static final Pattern CHAT_SEPARATORS = Pattern.compile("[\\p{C}\\p{Z}]+");
    private static final Pattern SHOOT_TARGET = Pattern.compile("([0-9]{1,3}),([0-9]{1,3})");
    // One unit restores each ms after the inactivity delay, with no fractional time rounding.
    private static final int ENERGY_CAP_UNITS = BattleRules.ENERGY_CAP * BattleRules.REGEN_POINT_MS;
    private static final int[][] DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
    };

    private enum Kind {
        PLAYER("player", 4), GUIDE("guide", 3), ENEMY("enemy", 2), DUMMY("dummy", 0);

        final String wireName;
        final int damage;

        Kind(String wireName, int damage) {
            this.wireName = wireName;
            this.damage = damage;
        }
    }

    private static final class Actor {
        final String id;
        final String name;
        final Kind kind;
        final InnApi.Appearance appearance;
        final int maxHp;
        int x;
        int y;
        int hp;
        long moveReadyAt;
        long attackReadyAt;
        int actionSeq;
        String lastAction;
        String targetId;
        long actionAt;
        InnApi.Action queued;
        long commandAck;
        int energyUnits = ENERGY_CAP_UNITS;
        long energyAt;
        int rations;
        int gold;
        final Set<String> potions = new LinkedHashSet<>(2);

        Actor(String id, String name, Kind kind, DungeonLayout.Cell start, int hp, long now,
              InnApi.Appearance appearance) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.appearance = appearance;
            x = start.x();
            y = start.y();
            this.hp = maxHp = hp;
            moveReadyAt = kind == Kind.ENEMY ? now + RAT_MOVE_COOLDOWN_MS : now;
            attackReadyAt = kind == Kind.ENEMY ? now + BattleRules.ATTACK_COOLDOWN_MS : now;
            energyAt = now;
            rations = kind == Kind.PLAYER ? 1 : 0;
        }

        int energyUnitsAt(long at) {
            long restored = Math.max(0, at - energyAt);
            return (int) Math.min(ENERGY_CAP_UNITS, energyUnits + restored);
        }

        InnApi.Combatant snapshot(long now) {
            double energy = energyUnitsAt(now) / (double) BattleRules.REGEN_POINT_MS;
            return new InnApi.Combatant(id, name, kind.wireName, x, y, hp, maxHp,
                actionSeq, lastAction, targetId, actionAt, energy, energyAt, moveReadyAt, attackReadyAt, rations,
                appearance, inventory());
        }

        private List<InnApi.InventoryItem> inventory() {
            if (kind != Kind.PLAYER) return List.of();
            List<InnApi.InventoryItem> items = new ArrayList<>(4);
            if (rations > 0) items.add(new InnApi.InventoryItem("ration", rations));
            if (gold > 0) items.add(new InnApi.InventoryItem("gold", gold));
            for (String potion : potions) items.add(new InnApi.InventoryItem(potion, 1));
            return List.copyOf(items);
        }
    }

    private record Turn(Actor actor, InnApi.Action action, long at) {}
    private record Cell(int x, int y, InnApi.Action first) {}

    private static final class Shot {
        final long id;
        final Actor owner;
        final List<DungeonLayout.Cell> path;
        int cellIndex;
        long stepAt;

        Shot(long id, Actor owner, List<DungeonLayout.Cell> path, long at) {
            this.id = id;
            this.owner = owner;
            this.path = path;
            stepAt = at + BattleRules.PROJECTILE_STEP_MS;
        }

        DungeonLayout.Cell current() { return path.get(cellIndex); }
        DungeonLayout.Cell next() { return path.get(cellIndex + 1); }

        InnApi.Projectile snapshot() {
            DungeonLayout.Cell from = current();
            DungeonLayout.Cell to = next();
            return new InnApi.Projectile(id, owner.id, from.x(), from.y(), to.x(), to.y(), stepAt);
        }
    }

    private final String id;
    private final DungeonLayout layout;
    private final List<Actor> actors;
    private final Actor trainingDummy;
    private final List<InnApi.TrainingTool> trainingTools;
    private final List<InnApi.Drop> drops = new ArrayList<>(2);
    private final Set<String> identifiedPotions = new LinkedHashSet<>(2);
    private final ArrayDeque<InnApi.PartyChat> messages = new ArrayDeque<>(CHAT_HISTORY);
    private final ArrayDeque<InnApi.BattleEvent> attackEvents = new ArrayDeque<>(ATTACK_HISTORY);
    private final List<Shot> projectiles = new ArrayList<>(2);
    private final long seed;
    private final boolean friendlyFire;
    private long battleAt;
    private long gameObservedAt;
    private long wallObservedAt;
    private long pausedMillis;
    private long pauseStartedAt;
    private long pausedUntil;
    private long pauseReadyAt;
    private long nextMessageId;
    private long nextEventId;
    private long nextProjectileId;
    private String status = "active";
    private boolean inCombat;
    private int revision = 1;

    HostBattle(String id, InnApi.Person host, InnApi.Person guestOrNull, long now, long seed) {
        this(id, host, guestOrNull, now, seed, DungeonLayout.SEWERS);
    }

    HostBattle(String id, InnApi.Person host, InnApi.Person guestOrNull, long now, long seed, String theme) {
        this(id, host, guestOrNull, now, seed, theme, false);
    }

    HostBattle(String id, InnApi.Person host, InnApi.Person guestOrNull, long now, long seed, String theme,
               boolean friendlyFire) {
        this(id, host, guestOrNull, now, seed, theme, friendlyFire, false);
    }

    HostBattle(String id, InnApi.Person host, InnApi.Person guestOrNull, long now, long seed, String theme,
               boolean friendlyFire, boolean solo) {
        this(id, host, guestOrNull, now, seed, theme, friendlyFire, solo, 0, 0, 0, 0);
    }

    HostBattle(String id, InnApi.Person host, InnApi.Person guestOrNull, long now, long seed, String theme,
               boolean friendlyFire, boolean solo, int hostHp, int hostFood, int guestHp, int guestFood) {
        this.id = id;
        this.seed = seed;
        this.friendlyFire = friendlyFire;
        layout = DungeonLayout.create(theme, seed);
        this.battleAt = now;
        this.gameObservedAt = now;
        this.wallObservedAt = now;
        String ratName = DungeonLayout.PRISON.equals(layout.theme()) ? "감옥 쥐" : "하수도 쥐";
        boolean practice = guestOrNull == null && !solo;
        List<Actor> participants = new ArrayList<>(5);
        Actor hostActor = player(host, layout.host(), now, practice ? 0 : hostHp, practice ? 0 : hostFood);
        participants.add(hostActor);
        if (guestOrNull != null) {
            Actor guestActor = player(guestOrNull, layout.partner(), now, guestHp, guestFood);
            hostActor.potions.addAll(BattleRules.POTIONS);
            guestActor.potions.addAll(BattleRules.POTIONS);
            participants.add(guestActor);
        } else if (practice) {
            participants.add(new Actor("guide", "길잡이", Kind.GUIDE, layout.partner(), 24, now, null));
        }
        participants.add(new Actor("rat-1", ratName, Kind.ENEMY, layout.enemy1(), 12, now, null));
        participants.add(new Actor("rat-2", ratName, Kind.ENEMY, layout.enemy2(), 12, now, null));
        if (practice) {
            DungeonLayout.Cell center = layout.partner();
            trainingDummy = new Actor("training-dummy", "연습용 허수아비", Kind.DUMMY,
                new DungeonLayout.Cell(center.x() + 1, center.y() - 1), TRAINING_DUMMY_HP, now, null);
            participants.add(trainingDummy);
            trainingTools = List.of(
                new InnApi.TrainingTool(RATION_RACK, center.x() - 1, center.y() + 1),
                new InnApi.TrainingTool(RECOVERY_KIT, center.x() + 1, center.y() + 1));
        } else {
            trainingDummy = null;
            trainingTools = List.of();
        }
        actors = List.copyOf(participants);
        if (trainingDummy != null) inCombat = engaged();
    }

    private static Actor player(InnApi.Person person, DungeonLayout.Cell start, long now, int hp, int food) {
        int startingHp = 24 + (Math.abs(hp) == 2 ? hp : 0);
        Actor player = new Actor(person.id(), person.name(), Kind.PLAYER, start, startingHp, now, person.appearance());
        if (Math.abs(food) == 1) player.rations += food;
        return player;
    }

    int revision() {
        return revision;
    }

    void advance(long now) {
        if ("closed".equals(status)) return;
        wallObservedAt = Math.max(wallObservedAt, now);
        if (pausedUntil > 0) {
            if (wallObservedAt < pausedUntil) return;
            resume(pausedUntil);
        }
        advanceGame(gameTime(wallObservedAt));
    }

    private long gameTime(long wallNow) {
        return (pausedUntil > 0 ? pauseStartedAt : wallNow) - pausedMillis;
    }

    private void advanceGame(long now) {
        gameObservedAt = now;
        while ("active".equals(status) || "won".equals(status)) {
            Turn turn = nextTurn(gameObservedAt);
            Shot shot = nextProjectile(gameObservedAt);
            if (turn != null && (shot == null || turn.at <= shot.stepAt)) {
                turn.actor.queued = null;
                battleAt = turn.at;
                resolve(turn.actor, turn.action, turn.at);
            } else if (shot != null) {
                battleAt = shot.stepAt;
                stepProjectile(shot);
            } else {
                break;
            }
            revision++;
        }
    }

    void input(String actorId, long sequence, InnApi.Action action, long now) {
        Actor actor = find(actorId);
        if (actor == null || actor.kind != Kind.PLAYER || sequence <= actor.commandAck) return;
        actor.commandAck = sequence;
        revision++;
        advance(now);

        // Even a rejected command is acknowledged so the sender can retire its retry.
        InnApi.Action normalized = normalize(action);
        if (normalized == null || "closed".equals(status)) return;
        if ("chat".equals(normalized.type())) {
            chat(actor, normalized.target());
            return;
        }
        if ("pause".equals(normalized.type())) {
            pause(normalized.target());
            return;
        }
        if (pausedUntil > 0 || actor.hp == 0 || !accepts(normalized)) return;
        boolean queued = readyAt(actor, normalized) > gameObservedAt;
        if (!actionAllowed(actor, normalized, queued, gameObservedAt)) return;
        if (queued) {
            if ("move".equals(normalized.type()) && actor.queued != null && offensive(actor.queued.type())) {
                return;
            }
            actor.queued = normalized;
            battleAt = gameObservedAt;
            return;
        }
        resolve(actor, normalized, gameObservedAt);
        advanceGame(gameObservedAt);
    }

    InnApi.DungeonState snapshot(String selfId, long now) {
        Actor self = find(selfId);
        if (self == null || self.kind != Kind.PLAYER) throw new IllegalArgumentException("Unknown player");
        long wallNow = Math.max(wallObservedAt, now);
        long snapshotTime = "closed".equals(status) ? gameObservedAt : gameTime(wallNow);
        return new InnApi.DungeonState(id, selfId, snapshotTime, layout.map(),
            actors.stream().map(actor -> actor.snapshot(snapshotTime)).toList(), self.queued, status, false, revision,
            List.copyOf(drops), self.commandAck, inCombat, layout.theme(), wallNow, pausedUntil, pauseReadyAt,
            List.copyOf(messages), friendlyFire, List.copyOf(attackEvents),
            projectiles.stream().map(Shot::snapshot).toList(), trainingTools,
            identifiedPotions.stream().map(item -> new InnApi.ItemKnowledge(item, potionEffect(item))).toList());
    }

    void close(long now) {
        if ("closed".equals(status)) return;
        wallObservedAt = Math.max(wallObservedAt, now);
        gameObservedAt = gameTime(wallObservedAt);
        pausedUntil = 0;
        status = "closed";
        inCombat = false;
        clearQueue();
        projectiles.clear();
        revision++;
    }

    private void pause(String intent) {
        if ("resume".equals(intent) && pausedUntil > 0) {
            resume(wallObservedAt);
        } else if ("begin".equals(intent) && pausedUntil == 0 && wallObservedAt >= pauseReadyAt &&
            ("active".equals(status) || "won".equals(status))) {
            pauseStartedAt = wallObservedAt;
            pausedUntil = wallObservedAt + PAUSE_LIMIT_MS;
        }
    }

    private void resume(long wallAt) {
        // With a late tick, only the portion after the exact deadline advances the simulation.
        pausedMillis += wallAt - pauseStartedAt;
        pausedUntil = 0;
        pauseReadyAt = wallAt + PAUSE_COOLDOWN_MS;
        revision++;
    }

    private void chat(Actor actor, String text) {
        if (messages.size() == CHAT_HISTORY) messages.removeFirst();
        messages.addLast(new InnApi.PartyChat(++nextMessageId, actor.id, actor.name, text, wallObservedAt));
    }

    private static InnApi.Action normalize(InnApi.Action action) {
        if (action == null) return null;
        if ("move".equals(action.type())) {
            int dx = action.dx();
            int dy = action.dy();
            if (dx >= -1 && dx <= 1 && dy >= -1 && dy <= 1 && (dx != 0 || dy != 0)) {
                return new InnApi.Action("move", dx, dy, null);
            }
        } else if ("attack".equals(action.type()) && action.target() != null) {
            return new InnApi.Action("attack", 0, 0, action.target());
        } else if ("shoot".equals(action.type()) && action.target() != null &&
            SHOOT_TARGET.matcher(action.target()).matches()) {
            return new InnApi.Action("shoot", 0, 0, action.target());
        } else if ("eat".equals(action.type())) {
            return new InnApi.Action("eat", 0, 0, null);
        } else if ("use-item".equals(action.type()) && BattleRules.isPotion(action.target())) {
            return new InnApi.Action("use-item", 0, 0, action.target());
        } else if ("pause".equals(action.type()) && ("begin".equals(action.target()) || "resume".equals(action.target()))) {
            return new InnApi.Action("pause", 0, 0, action.target());
        } else if ("chat".equals(action.type()) && action.target() != null) {
            String text = CHAT_SEPARATORS.matcher(action.target()).replaceAll(" ").strip();
            if (text.isEmpty()) return null;
            int end = text.offsetByCodePoints(0, Math.min(CHAT_LIMIT, text.codePointCount(0, text.length())));
            return new InnApi.Action("chat", 0, 0, text.substring(0, end).stripTrailing());
        }
        return null;
    }

    private Actor find(String actorId) {
        for (Actor actor : actors) {
            if (actor.id.equals(actorId)) return actor;
        }
        return null;
    }

    private static int distance(int x, int y, Actor target) {
        return Math.max(Math.abs(x - target.x), Math.abs(y - target.y));
    }

    private boolean stepAllowed(int x, int y, int dx, int dy) {
        return layout.walkable(x + dx, y + dy) &&
            (dx == 0 || dy == 0 || layout.walkable(x + dx, y) || layout.walkable(x, y + dy));
    }

    private boolean inReach(int x, int y, Actor target) {
        return distance(x, y, target) == 1 && layout.walkable(target.x, target.y) &&
            (x == target.x || y == target.y || (layout.walkable(target.x, y) && layout.walkable(x, target.y)));
    }

    private boolean canMove(int x, int y, int dx, int dy) {
        if (!stepAllowed(x, y, dx, dy)) return false;
        for (Actor actor : actors) {
            if (actor.hp > 0 && actor.x == x + dx && actor.y == y + dy) return false;
        }
        return true;
    }

    private static boolean opponents(Actor a, Actor b) {
        if (b.kind == Kind.DUMMY) return a.kind == Kind.PLAYER;
        if (a.kind == Kind.DUMMY) return false;
        return (a.kind == Kind.ENEMY) != (b.kind == Kind.ENEMY);
    }

    private static boolean offensive(String type) {
        return "attack".equals(type) || "shoot".equals(type);
    }

    private boolean accepts(InnApi.Action action) {
        return "active".equals(status) || ("won".equals(status) && !offensive(action.type()));
    }

    private boolean actionAllowed(Actor actor, InnApi.Action action, boolean queued, long at) {
        if ("move".equals(action.type())) {
            return queued ? stepAllowed(actor.x, actor.y, action.dx(), action.dy())
                : canMove(actor.x, actor.y, action.dx(), action.dy());
        }
        if ("eat".equals(action.type())) return actor.rations > 0 && actor.hp < actor.maxHp;
        if ("use-item".equals(action.type())) return actor.potions.contains(action.target());
        if ("shoot".equals(action.type())) {
            DungeonLayout.Cell aim = aimCell(action.target());
            int distance = Math.max(Math.abs(aim.x() - actor.x), Math.abs(aim.y() - actor.y));
            boolean inBounds = aim.y() < layout.map().size() && aim.x() < layout.map().get(aim.y()).length();
            if (inBounds && distance >= 1 && distance <= BattleRules.SHOOT_RANGE) return true;
            attackEvent("shoot", "out_of_reach", actor, null, inBounds ? aim : position(actor), 0, at);
            return false;
        }
        Actor target = find(action.target());
        String result = attackFailure(actor, target);
        if (result == null) return true;
        attackEvent("attack", result, actor, target, position(target == null ? actor : target), 0, at);
        return false;
    }

    private String attackFailure(Actor actor, Actor target) {
        if (target == null || target.hp == 0) return "target_gone";
        if (actor == target || (!opponents(actor, target) && !(friendlyFire && actor.kind == Kind.PLAYER))) {
            return "ally_blocked";
        }
        if (distance(actor.x, actor.y, target) != 1) return "out_of_reach";
        return inReach(actor.x, actor.y, target) ? null : "blocked";
    }

    private static DungeonLayout.Cell aimCell(String target) {
        int separator = target.indexOf(',');
        return new DungeonLayout.Cell(Integer.parseInt(target.substring(0, separator)),
            Integer.parseInt(target.substring(separator + 1)));
    }

    private static DungeonLayout.Cell position(Actor actor) {
        return new DungeonLayout.Cell(actor.x, actor.y);
    }

    private void attackEvent(String action, String result, Actor actor, Actor target,
                             DungeonLayout.Cell cell, int amount, long at) {
        if (attackEvents.size() == ATTACK_HISTORY) attackEvents.removeFirst();
        attackEvents.addLast(new InnApi.BattleEvent(++nextEventId, action, result, actor.id,
            target == null ? null : target.id, cell.x(), cell.y(), amount, at));
    }

    private int costUnits(Actor actor, InnApi.Action action) {
        return actor.kind == Kind.ENEMY ? 0 : BattleRules.cost(action.type(), inCombat) * BattleRules.REGEN_POINT_MS;
    }

    private long readyAt(Actor actor, InnApi.Action action) {
        long gate = switch (action.type()) {
            case "move" -> actor.moveReadyAt;
            case "attack", "shoot" -> actor.attackReadyAt;
            default -> 0;
        };
        int missing = costUnits(actor, action) - actor.energyUnits;
        return missing <= 0 ? gate : Math.max(gate, actor.energyAt + missing);
    }

    private void resolve(Actor actor, InnApi.Action action, long at) {
        if (!actionAllowed(actor, action, false, at)) return;
        int cost = costUnits(actor, action);
        if (cost > 0) {
            actor.energyUnits = actor.energyUnitsAt(at) - cost;
            actor.energyAt = at + BattleRules.REGEN_DELAY_MS;
        }

        switch (action.type()) {
            case "move" -> {
                actor.x += action.dx();
                actor.y += action.dy();
                actor.targetId = null;
                int cooldown = actor.kind == Kind.ENEMY ? RAT_MOVE_COOLDOWN_MS : BattleRules.MOVE_COOLDOWN_MS;
                actor.moveReadyAt = at + cooldown;
                if (actor.kind == Kind.PLAYER) {
                    pickUp(actor);
                    useTrainingTool(actor, at);
                }
            }
            case "attack" -> {
                Actor target = find(action.target());
                int amount = damage(target, actor.kind.damage);
                attackEvent("attack", "hit", actor, target, position(target), amount, at);
                actor.targetId = target.id;
                actor.attackReadyAt = at + BattleRules.ATTACK_COOLDOWN_MS;
            }
            case "shoot" -> {
                DungeonLayout.Cell aim = aimCell(action.target());
                projectiles.add(new Shot(++nextProjectileId, actor, projectilePath(actor, aim), at));
                actor.targetId = null;
                actor.attackReadyAt = at + BattleRules.ATTACK_COOLDOWN_MS;
            }
            case "eat" -> {
                actor.rations--;
                actor.hp = Math.min(actor.maxHp, actor.hp + BattleRules.RATION_HEAL);
                actor.targetId = null;
            }
            case "use-item" -> usePotion(actor, action.target());
            default -> throw new IllegalArgumentException("Unknown battle action");
        }
        battleAt = at;
        actor.actionAt = at;
        actor.actionSeq++;
        actor.lastAction = action.type();
        updateBattleState();
    }

    private String potionEffect(String item) {
        boolean violetHeals = seed % 2 == 0;
        boolean heals = BattleRules.VIOLET_POTION.equals(item) ? violetHeals : !violetHeals;
        return heals ? "healing" : "energy";
    }

    private void usePotion(Actor actor, String item) {
        actor.potions.remove(item);
        identifiedPotions.add(item);
        if ("healing".equals(potionEffect(item))) {
            actor.hp = Math.min(actor.maxHp, actor.hp + BattleRules.POTION_HEAL);
        } else {
            actor.energyUnits = Math.min(ENERGY_CAP_UNITS, actor.energyUnits + BattleRules.POTION_ENERGY * BattleRules.REGEN_POINT_MS);
        }
        actor.targetId = null;
    }

    private void updateBattleState() {
        updateOutcome();
        inCombat = "active".equals(status) && engaged();
    }

    private int damage(Actor target, int strength) {
        int amount = Math.min(target.hp, strength);
        target.hp -= amount;
        if (target.hp == 0) {
            if (target.kind == Kind.DUMMY) {
                target.hp = target.maxHp;
            } else {
                target.queued = null;
                if (target.kind == Kind.ENEMY) drop(target);
            }
        }
        return amount;
    }

    private static List<DungeonLayout.Cell> projectilePath(Actor origin, DungeonLayout.Cell aim) {
        List<DungeonLayout.Cell> path = new ArrayList<>(BattleRules.SHOOT_RANGE + 1);
        int x = origin.x;
        int y = origin.y;
        int dx = Math.abs(aim.x() - x);
        int dy = -Math.abs(aim.y() - y);
        int sx = Integer.compare(aim.x(), x);
        int sy = Integer.compare(aim.y(), y);
        int error = dx + dy;
        path.add(position(origin));
        while (x != aim.x() || y != aim.y()) {
            int doubled = 2 * error;
            if (doubled >= dy) {
                error += dy;
                x += sx;
            }
            if (doubled <= dx) {
                error += dx;
                y += sy;
            }
            path.add(new DungeonLayout.Cell(x, y));
        }
        return path;
    }

    private Shot nextProjectile(long now) {
        Shot next = null;
        // Insertion order is launch ID order and breaks equal flight deadlines.
        for (Shot shot : projectiles) {
            if (shot.stepAt <= now && (next == null || shot.stepAt < next.stepAt)) next = shot;
        }
        return next;
    }

    private void stepProjectile(Shot shot) {
        DungeonLayout.Cell from = shot.current();
        DungeonLayout.Cell to = shot.next();
        if (!stepAllowed(from.x(), from.y(), to.x() - from.x(), to.y() - from.y())) {
            attackEvent("shoot", "blocked", shot.owner, null, to, 0, shot.stepAt);
            projectiles.remove(shot);
            return;
        }

        for (Actor target : actors) {
            if (target == shot.owner || target.hp == 0 || target.x != to.x() || target.y != to.y()) continue;
            boolean hurts = friendlyFire || opponents(shot.owner, target);
            int amount = hurts ? damage(target, BattleRules.SHOOT_DAMAGE) : 0;
            attackEvent("shoot", hurts ? "hit" : "ally_blocked", shot.owner, target, to, amount, shot.stepAt);
            projectiles.remove(shot);
            updateBattleState();
            return;
        }

        shot.cellIndex++;
        if (shot.cellIndex + 1 == shot.path.size()) {
            attackEvent("shoot", "miss", shot.owner, null, to, 0, shot.stepAt);
            projectiles.remove(shot);
        } else {
            shot.stepAt += BattleRules.PROJECTILE_STEP_MS;
        }
    }

    private void drop(Actor enemy) {
        // A rat's loot depends on the run seed and its identity, regardless of kill order.
        SplittableRandom random = new SplittableRandom(seed ^ enemy.id.hashCode());
        boolean gold = random.nextBoolean();
        drops.add(new InnApi.Drop(id + ":" + enemy.id, gold ? "gold" : "ration", enemy.x, enemy.y,
            gold ? random.nextInt(3, 8) : 1, null));
    }

    private void pickUp(Actor player) {
        for (int i = 0; i < drops.size(); i++) {
            InnApi.Drop drop = drops.get(i);
            if (drop.pickedBy() == null && drop.x() == player.x && drop.y() == player.y) {
                drops.set(i, new InnApi.Drop(drop.id(), drop.item(), drop.x(), drop.y(), drop.amount(), player.id));
                if ("ration".equals(drop.item())) player.rations += drop.amount();
                else if ("gold".equals(drop.item())) player.gold += drop.amount();
            }
        }
    }

    private void useTrainingTool(Actor player, long at) {
        for (InnApi.TrainingTool tool : trainingTools) {
            if (tool.x() != player.x || tool.y() != player.y) continue;
            switch (tool.id()) {
                case RATION_RACK -> player.rations = Math.max(player.rations, TRAINING_RATIONS);
                case RECOVERY_KIT -> {
                    player.hp = player.maxHp;
                    player.energyUnits = ENERGY_CAP_UNITS;
                    player.energyAt = at;
                }
            }
            return;
        }
    }

    private boolean engaged() {
        if (trainingDummy != null) {
            Actor player = actors.getFirst();
            if (player.hp > 0 && distance(player.x, player.y, trainingDummy) <= TRAINING_RANGE) return true;
        }
        for (Actor enemy : actors) {
            if (enemy.kind != Kind.ENEMY || enemy.hp == 0) continue;
            for (Actor partyMember : actors) {
                if (opponents(enemy, partyMember) && awareOf(enemy, partyMember)) return true;
            }
        }
        return false;
    }

    private static boolean awareOf(Actor observer, Actor target) {
        return target.hp > 0 && distance(observer.x, observer.y, target) <= AWARENESS;
    }

    private void updateOutcome() {
        if (!"active".equals(status)) return;
        if (actors.stream().noneMatch(actor -> actor.kind == Kind.PLAYER && actor.hp > 0)) {
            status = "lost";
        } else if (trainingDummy == null && actors.stream().noneMatch(actor -> actor.kind == Kind.ENEMY && actor.hp > 0)) {
            status = "won";
        }
        if (!"active".equals(status)) {
            clearQueue();
            projectiles.clear();
        }
    }

    private void clearQueue() {
        for (Actor actor : actors) actor.queued = null;
    }

    private Turn nextTurn(long now) {
        Turn next = null;
        // Stable actor order resolves simultaneous deadlines consistently.
        for (Actor actor : actors) {
            if (actor.hp == 0 || actor.kind == Kind.DUMMY) continue;
            if (actor.kind != Kind.PLAYER && !"active".equals(status)) continue;
            InnApi.Action action = actor.kind == Kind.PLAYER ? actor.queued : botAction(actor);
            if (action == null) continue;
            long at = Math.max(battleAt, readyAt(actor, action));
            if (at <= now && (next == null || at < next.at)) next = new Turn(actor, action, at);
        }
        return next;
    }

    private InnApi.Action botAction(Actor actor) {
        List<Actor> close = new ArrayList<>();
        for (Actor target : actors) {
            if (opponents(actor, target) && awareOf(actor, target)) {
                if (inReach(actor.x, actor.y, target)) return new InnApi.Action("attack", 0, 0, target.id);
                close.add(target);
            }
        }
        if (!close.isEmpty()) {
            InnApi.Action action = approach(actor, close);
            if (action != null) return action;
        }
        Actor host = actors.getFirst();
        if (actor.kind == Kind.GUIDE && host.hp > 0 && distance(actor.x, actor.y, host) > 1) {
            return approach(actor, List.of(host));
        }
        return null;
    }

    private InnApi.Action approach(Actor actor, List<Actor> targets) {
        int width = layout.map().getFirst().length();
        List<Cell> cells = new ArrayList<>();
        boolean[] visited = new boolean[width * layout.map().size()];
        cells.add(new Cell(actor.x, actor.y, null));
        visited[actor.y * width + actor.x] = true;
        for (int index = 0; index < cells.size(); index++) {
            Cell cell = cells.get(index);
            if (cell.first != null && targets.stream().anyMatch(target -> inReach(cell.x, cell.y, target))) {
                return cell.first;
            }
            for (int[] direction : DIRECTIONS) {
                int dx = direction[0];
                int dy = direction[1];
                if (!canMove(cell.x, cell.y, dx, dy)) continue;
                int x = cell.x + dx;
                int y = cell.y + dy;
                int key = y * width + x;
                if (visited[key]) continue;
                visited[key] = true;
                InnApi.Action first = cell.first == null ? new InnApi.Action("move", dx, dy, null) : cell.first;
                cells.add(new Cell(x, y, first));
            }
        }
        return null;
    }
}
