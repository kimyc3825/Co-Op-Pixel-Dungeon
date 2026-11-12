// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** The match's only simulation, run on the host session's executor. */
final class HostBattle {
    private static final int RAT_MOVE_COOLDOWN_MS = 380;
    private static final int AWARENESS = 4;
    // One unit restores each ms after the inactivity delay, with no fractional time rounding.
    private static final int ENERGY_CAP_UNITS = BattleRules.ENERGY_CAP * BattleRules.REGEN_POINT_MS;
    private static final List<String> MAP = List.of(
        "#############",
        "#...........#",
        "#.....#.....#",
        "#.....#.....#",
        "#...........#",
        "#..##.......#",
        "#...........#",
        "#.....#.....#",
        "#############"
    );
    private static final int WIDTH = MAP.getFirst().length();
    private static final int[][] DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
    };

    private enum Kind {
        PLAYER("player", 4), GUIDE("guide", 3), ENEMY("enemy", 2);

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

        Actor(String id, String name, Kind kind, int x, int y, int hp, long now) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.x = x;
            this.y = y;
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
                actionSeq, lastAction, targetId, actionAt, energy, energyAt, moveReadyAt, attackReadyAt, rations);
        }
    }

    private record Turn(Actor actor, InnApi.Action action, long at) {}
    private record Cell(int x, int y, InnApi.Action first) {}

    private final String id;
    private final List<Actor> actors;
    private final List<InnApi.Drop> drops = new ArrayList<>(2);
    private final long seed;
    private long battleAt;
    private long observedAt;
    private String status = "active";
    private boolean inCombat;
    private int revision = 1;

    HostBattle(String id, InnApi.Person host, InnApi.Person guestOrNull, long now, long seed) {
        this.id = id;
        this.seed = seed;
        this.battleAt = now;
        this.observedAt = now;
        actors = List.of(
            new Actor(host.id(), host.name(), Kind.PLAYER, 2, 2, 24, now),
            guestOrNull == null ? new Actor("guide", "길잡이", Kind.GUIDE, 3, 2, 24, now)
                : new Actor(guestOrNull.id(), guestOrNull.name(), Kind.PLAYER, 3, 2, 24, now),
            new Actor("rat-1", "하수도 쥐", Kind.ENEMY, 9, 2, 12, now),
            new Actor("rat-2", "하수도 쥐", Kind.ENEMY, 10, 6, 12, now)
        );
    }

    int revision() {
        return revision;
    }

    void advance(long now) {
        observedAt = Math.max(observedAt, now);
        while ("active".equals(status) || "won".equals(status)) {
            Turn turn = nextTurn(observedAt);
            if (turn == null) break;
            turn.actor.queued = null;
            battleAt = turn.at;
            resolve(turn.actor, turn.action, turn.at);
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
        if (actor.hp == 0 || normalized == null || !accepts(normalized)) return;
        boolean queued = readyAt(actor, normalized) > observedAt;
        if (!actionAllowed(actor, normalized, queued)) return;
        if (queued) {
            if ("move".equals(normalized.type()) && actor.queued != null && "attack".equals(actor.queued.type())) {
                return;
            }
            actor.queued = normalized;
            battleAt = observedAt;
            return;
        }
        resolve(actor, normalized, observedAt);
        advance(observedAt);
    }

    InnApi.DungeonState snapshot(String selfId, long now) {
        Actor self = find(selfId);
        if (self == null || self.kind != Kind.PLAYER) throw new IllegalArgumentException("Unknown player");
        long snapshotTime = Math.max(observedAt, now);
        return new InnApi.DungeonState(id, selfId, snapshotTime, MAP,
            actors.stream().map(actor -> actor.snapshot(snapshotTime)).toList(), self.queued, status, false, revision,
            List.copyOf(drops), self.commandAck, inCombat);
    }

    void close(long now) {
        if ("closed".equals(status)) return;
        observedAt = Math.max(observedAt, now);
        status = "closed";
        inCombat = false;
        clearQueue();
        revision++;
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
        } else if ("eat".equals(action.type())) {
            return new InnApi.Action("eat", 0, 0, null);
        }
        return null;
    }

    private Actor find(String actorId) {
        for (Actor actor : actors) {
            if (actor.id.equals(actorId)) return actor;
        }
        return null;
    }

    private static boolean walkable(int x, int y) {
        return y >= 0 && y < MAP.size() && x >= 0 && x < WIDTH && MAP.get(y).charAt(x) == '.';
    }

    private static int distance(int x, int y, Actor target) {
        return Math.max(Math.abs(x - target.x), Math.abs(y - target.y));
    }

    private static boolean stepAllowed(int x, int y, int dx, int dy) {
        return walkable(x + dx, y + dy) &&
            (dx == 0 || dy == 0 || (walkable(x + dx, y) && walkable(x, y + dy)));
    }

    private static boolean inReach(int x, int y, Actor target) {
        return distance(x, y, target) == 1 && stepAllowed(x, y, target.x - x, target.y - y);
    }

    private boolean canMove(int x, int y, int dx, int dy) {
        if (!stepAllowed(x, y, dx, dy)) return false;
        for (Actor actor : actors) {
            if (actor.hp > 0 && actor.x == x + dx && actor.y == y + dy) return false;
        }
        return true;
    }

    private static boolean opponents(Actor a, Actor b) {
        return (a.kind == Kind.ENEMY) != (b.kind == Kind.ENEMY);
    }

    private boolean accepts(InnApi.Action action) {
        return "active".equals(status) || ("won".equals(status) && !"attack".equals(action.type()));
    }

    private boolean actionAllowed(Actor actor, InnApi.Action action, boolean queued) {
        if ("move".equals(action.type())) {
            return queued ? stepAllowed(actor.x, actor.y, action.dx(), action.dy())
                : canMove(actor.x, actor.y, action.dx(), action.dy());
        }
        if ("eat".equals(action.type())) return actor.rations > 0 && actor.hp < actor.maxHp;
        Actor target = find(action.target());
        return target != null && target.hp > 0 && opponents(actor, target) && inReach(actor.x, actor.y, target);
    }

    private int costUnits(Actor actor, InnApi.Action action) {
        return actor.kind == Kind.ENEMY ? 0 : BattleRules.cost(action.type(), inCombat) * BattleRules.REGEN_POINT_MS;
    }

    private long readyAt(Actor actor, InnApi.Action action) {
        long gate = switch (action.type()) {
            case "move" -> actor.moveReadyAt;
            case "attack" -> actor.attackReadyAt;
            default -> 0;
        };
        int missing = costUnits(actor, action) - actor.energyUnits;
        return missing <= 0 ? gate : Math.max(gate, actor.energyAt + missing);
    }

    private void resolve(Actor actor, InnApi.Action action, long at) {
        if (!actionAllowed(actor, action, false)) return;
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
                if (actor.kind == Kind.PLAYER) pickUp(actor);
            }
            case "attack" -> {
                Actor target = find(action.target());
                target.hp = Math.max(0, target.hp - actor.kind.damage);
                if (target.hp == 0) {
                    target.queued = null;
                    if (target.kind == Kind.ENEMY) drop(target);
                }
                actor.targetId = target.id;
                actor.attackReadyAt = at + BattleRules.ATTACK_COOLDOWN_MS;
            }
            case "eat" -> {
                actor.rations--;
                actor.hp = Math.min(actor.maxHp, actor.hp + BattleRules.RATION_HEAL);
                actor.targetId = null;
            }
            default -> throw new IllegalArgumentException("Unknown battle action");
        }
        battleAt = at;
        actor.actionAt = at;
        actor.actionSeq++;
        actor.lastAction = action.type();
        updateOutcome();
        inCombat = "active".equals(status) && engaged();
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
            }
        }
    }

    private boolean engaged() {
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
        } else if (actors.stream().noneMatch(actor -> actor.kind == Kind.ENEMY && actor.hp > 0)) {
            status = "won";
        }
        if (!"active".equals(status)) clearQueue();
    }

    private void clearQueue() {
        for (Actor actor : actors) actor.queued = null;
    }

    private Turn nextTurn(long now) {
        Turn next = null;
        // Stable actor order resolves simultaneous deadlines consistently.
        for (Actor actor : actors) {
            if (actor.hp == 0) continue;
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
        List<Cell> cells = new ArrayList<>();
        boolean[] visited = new boolean[WIDTH * MAP.size()];
        cells.add(new Cell(actor.x, actor.y, null));
        visited[actor.y * WIDTH + actor.x] = true;
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
                int key = y * WIDTH + x;
                if (visited[key]) continue;
                visited[key] = true;
                InnApi.Action first = cell.first == null ? new InnApi.Action("move", dx, dy, null) : cell.first;
                cells.add(new Cell(x, y, first));
            }
        }
        return null;
    }
}
