// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.InnAtb;
import com.shatteredpixel.shatteredpixeldungeon.InnOriginalParty;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.sprites.InnPartySprite;
import com.watabou.utils.Random;

/** The network-controlled companion in the host's original actor queue. */
public final class InnPartyMember extends DirectableAlly {
    private volatile float energy = InnAtb.CAPACITY;
    private float refillDelay;
    private float attackAt;
    private float moveAt;

    {
        HP = HT = 20;
        attacksAutomatically = false;
    }

    @Override
    public String name() {
        InnOriginalParty party = InnOriginalParty.current();
        return party == null ? super.name() : party.peer.name();
    }

    @Override
    public InnPartySprite sprite() {
        InnOriginalParty party = InnOriginalParty.current();
        return new InnPartySprite(party == null ? null : party.peer);
    }

    @Override
    protected boolean act() {
        InnOriginalParty party = InnOriginalParty.current();
        if (party == null || paralysed > 0) waitForInput();
        else party.act(this);
        return true;
    }

    public void waitForInput() { spendConstant(0.08f); }

    public float energy() { return energy; }

    public synchronized void regenerate(float elapsed) {
        float available = Math.max(0, elapsed - refillDelay);
        refillDelay = Math.max(0, refillDelay - elapsed);
        energy = Math.min(InnAtb.CAPACITY, energy + available / 0.8f);
    }

    private synchronized void spendEnergy(int cost) {
        if (cost == 0) return;
        energy -= cost;
        refillDelay = 0.5f;
    }

    public boolean execute(String kind, int first, int second, boolean friendlyFire) {
        if (!isAlive() || sprite == null) return true;
        Level level = Dungeon.level;
        if (kind.equals("A")) {
            Actor target = Actor.findById(first);
            return !(target instanceof Char) || strike((Char) target, friendlyFire);
        }
        int cell;
        if (kind.equals("M")) {
            if (Math.abs((long) first) > 1 || Math.abs((long) second) > 1) return true;
            cell = pos + first + second * level.width();
            if (!level.insideMap(cell) || !level.adjacent(pos, cell)) return true;
        } else {
            if (!level.insideMap(first) || first == pos) return true;
            Char target = Actor.findChar(first);
            if (target != null) return strike(target, friendlyFire);
            boolean[] known = InnOriginalParty.current().explored();
            boolean[] passable = level.passable.clone();
            for (int i = 0; i < passable.length; i++) passable[i] &= known[i];
            cell = level.adjacent(pos, first) ? first : Dungeon.findStep(this, first, passable, known, true);
            if (cell < 0) return true;
        }

        if (rooted || !level.passable[cell] || level.pit[cell] || Actor.findChar(cell) != null) return true;
        int cost = Dungeon.hero.innEncounter().phase().costsMovement() ? 1 : 0;
        if (Actor.now() < moveAt || energy < cost || sprite.isMoving) return false;
        spendEnergy(cost);
        sprite.move(pos, cell);
        move(cell);
        moveAt = Actor.now() + 0.225f / speed();
        spendConstant(0.08f);
        return true;
    }

    private boolean strike(Char target, boolean friendlyFire) {
        if (target == this || !target.isAlive() || !canAttack(target) || isCharmedBy(target)
                || (target.alignment != Alignment.ENEMY && !(friendlyFire && target == Dungeon.hero))) return true;
        if (Actor.now() < attackAt || energy < 1 || sprite.isMoving) return false;
        spendEnergy(1);
        attackAt = Actor.now() + attackDelay();
        int targetCell = target.pos;
        attack(target);
        sprite.attack(targetCell, () -> sprite.idle());
        spendConstant(0.08f);
        return true;
    }

    @Override
    public boolean heroShouldInteract() {
        InnOriginalParty party = InnOriginalParty.current();
        return party == null || !party.friendlyFire;
    }

    @Override
    public void fixTime(float decrement) {
        super.fixTime(decrement);
        moveAt = Math.max(0, moveAt - decrement);
        attackAt = Math.max(0, attackAt - decrement);
    }

    @Override
    public int attackSkill(Char target) { return 10 + Dungeon.hero.lvl; }

    @Override
    public int defenseSkill(Char target) { return 5 + Dungeon.hero.lvl; }

    @Override
    public int damageRoll() { return Random.NormalIntRange(2, 5 + Dungeon.hero.lvl); }
}
