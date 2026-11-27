// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.InnOriginalParty;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.InnPartySprite;
import com.watabou.utils.Random;

/**
 * The network-controlled companion in the host's original actor queue. It is a player for the party turn
 * clock: it only gets a turn when the guest's command is ready, and pays the normal turn time for it.
 */
public final class InnPartyMember extends DirectableAlly {
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
        if (party != null && paralysed > 0) {
            // A command sent while the member cannot move is acknowledged and dropped, not replayed later.
            party.dropPending();
            waitOneTurn();
            return true;
        }
        // Without a usable command the member spends its turn, so it never stalls the queue.
        if (party == null || !party.act(this)) waitOneTurn();
        return true;
    }

    /** Spends one turn without acting, like the original rest. */
    public void waitOneTurn() {
        spendConstant(TICK);
    }

    /**
     * Applies one guest command. Returns false if it was rejected without spending time; the command is
     * acknowledged either way.
     */
    public boolean execute(String kind, int first, int second, boolean friendlyFire) {
        if (!isAlive() || sprite == null) return false;
        Level level = Dungeon.level;
        if (kind.equals("W")) {
            waitOneTurn();
            sprite.showStatus(CharSprite.DEFAULT, Messages.get(Hero.class, "wait"));
            return true;
        }
        if (kind.equals("A")) {
            Actor target = Actor.findById(first);
            return target instanceof Char && strike((Char) target, friendlyFire);
        }
        int cell;
        if (kind.equals("M")) {
            if (Math.abs((long) first) > 1 || Math.abs((long) second) > 1) return false;
            cell = pos + first + second * level.width();
            if (!level.insideMap(cell) || !level.adjacent(pos, cell)) return false;
        } else {
            if (!level.insideMap(first) || first == pos) return false;
            Char target = Actor.findChar(first);
            if (target != null) return strike(target, friendlyFire);
            boolean[] known = InnOriginalParty.current().explored();
            boolean[] passable = level.passable.clone();
            for (int i = 0; i < passable.length; i++) passable[i] &= known[i];
            cell = level.adjacent(pos, first) ? first : Dungeon.findStep(this, first, passable, known, true);
            if (cell < 0) return false;
        }

        if (rooted || !level.passable[cell] || level.pit[cell] || Actor.findChar(cell) != null) return false;
        sprite.move(pos, cell);
        move(cell);
        spend(1 / speed());
        return true;
    }

    private boolean strike(Char target, boolean friendlyFire) {
        if (target == this || !target.isAlive() || !canAttack(target) || isCharmedBy(target)
                || (target.alignment != Alignment.ENEMY && !(friendlyFire && target == Dungeon.hero))) return false;
        int targetCell = target.pos;
        attack(target);
        sprite.attack(targetCell, () -> sprite.idle());
        spend(attackDelay());
        return true;
    }

    @Override
    public boolean heroShouldInteract() {
        InnOriginalParty party = InnOriginalParty.current();
        return party == null || !party.friendlyFire;
    }

    @Override
    public int attackSkill(Char target) { return 10 + Dungeon.hero.lvl; }

    @Override
    public int defenseSkill(Char target) { return 5 + Dungeon.hero.lvl; }

    @Override
    public int damageRoll() { return Random.NormalIntRange(2, 5 + Dungeon.hero.lvl); }
}
