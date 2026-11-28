// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.InnOriginalParty;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.BlobImmunity;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Levitation;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Wraith;
import com.shatteredpixel.shatteredpixeldungeon.items.Dewdrop;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfExperience;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHaste;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfInvisibility;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfLevitation;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfMindVision;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfPurity;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.Runestone;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Cudgel;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dagger;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Gloves;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Rapier;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WornShortsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.InnPartySprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;
import com.watabou.utils.Random;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The network-controlled companion in the host's original actor queue. It is a player for the party turn
 * clock: it only gets a turn when the guest's command is ready, and pays the normal turn time for it.
 *
 * The companion owns its own equipment (one melee weapon, one armor) and a bag. Items run through the original
 * item classes, so weapon and armor stats, enchantments, glyphs, curses and upgrades work as for the hero.
 * Effects the original engine only defines for the hero (most scrolls, rings, wands, artifacts) are not
 * available to the companion; it can hand those items to the host instead.
 */
public final class InnPartyMember extends DirectableAlly {
    /** Bag slots, like the original backpack. */
    public static final int BAG_SIZE = 20;
    public static final int SLOT_WEAPON = 0;
    public static final int SLOT_ARMOR = 1;
    /** Targets of a read scroll of upgrade, the second value of a use command. */
    public static final int UPGRADE_WEAPON = 1;
    public static final int UPGRADE_ARMOR = 2;

    private MeleeWeapon weapon;
    private Armor armor;
    private final ArrayList<Item> bag = new ArrayList<>();

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

    // ---- equipment ----

    public MeleeWeapon weapon() { return weapon; }

    public Armor armor() { return armor; }

    /** The bag, read-only. */
    public List<Item> bag() { return Collections.unmodifiableList(bag); }

    /** The original starting weapon of the class, cloth armor and a ration, as {@code HeroClass.initHero} gives. */
    public void equipStarterKit(HeroClass heroClass) {
        if (weapon != null || armor != null || !bag.isEmpty()) return;
        switch (heroClass) {
            // The companion cannot zap, so the mage's staff comes without the imbued wand.
            case MAGE: weapon = new MagesStaff(); break;
            case ROGUE: weapon = new Dagger(); break;
            case HUNTRESS: weapon = new Gloves(); break;
            case DUELIST: weapon = new Rapier(); break;
            case CLERIC: weapon = new Cudgel(); break;
            default: weapon = new WornShortsword(); break;
        }
        // Known like the hero's kit, without marking anything in the host's journal.
        weapon.levelKnown = weapon.cursedKnown = true;
        armor = new ClothArmor();
        armor.levelKnown = armor.cursedKnown = true;
        bag.add(new Food());
    }

    /** Items the companion can carry: the kinds it can use or hand over without hero-only effects on pickup. */
    public static boolean carriable(Item item) {
        return item instanceof MeleeWeapon || item instanceof MissileWeapon || item instanceof Armor
                || item instanceof Potion || item instanceof Scroll || item instanceof Food
                || item instanceof Runestone || item instanceof Plant.Seed;
    }

    /** Follows the host's level like the hero's health does: 20 + 5 per level, keeping the current share. */
    private void matchHostLevel() {
        Hero hero = Dungeon.hero;
        if (hero == null) return;
        int target = 20 + 5 * (hero.lvl - 1);
        if (target != HT && HT > 0) {
            HP = Math.max(HP > 0 ? 1 : 0, Math.round(HP * (float) target / HT));
            HT = target;
        }
    }

    private int hostStrength() {
        return Dungeon.hero == null ? 10 : Dungeon.hero.STR();
    }

    @Override
    public int attackSkill(Char target) {
        float accuracy = 10 + Dungeon.hero.lvl;
        if (weapon != null) {
            accuracy *= weapon.accuracyFactor(this, target);
            int encumbrance = weapon.STRReq() - hostStrength();
            if (encumbrance > 0) accuracy /= (float) Math.pow(1.5, encumbrance);
        }
        return Math.round(accuracy);
    }

    @Override
    public int defenseSkill(Char target) {
        float evasion = 5 + Dungeon.hero.lvl;
        if (armor != null) {
            evasion = armor.evasionFactor(this, evasion);
            int encumbrance = armor.STRReq() - hostStrength();
            if (encumbrance > 0) evasion /= (float) Math.pow(1.5, encumbrance);
        }
        return Math.round(evasion);
    }

    @Override
    public int damageRoll() {
        if (weapon == null) return Random.NormalIntRange(1, 4);
        int damage = weapon.damageRoll(this);
        int excess = hostStrength() - weapon.STRReq();
        if (excess > 0) damage += Random.NormalIntRange(0, excess);
        return damage;
    }

    @Override
    public int drRoll() {
        int dr = super.drRoll();
        if (armor != null) {
            int max = armor.DRMax();
            int encumbrance = armor.STRReq() - hostStrength();
            if (encumbrance > 0) max = Math.max(armor.DRMin(), max - encumbrance);
            dr += Random.NormalIntRange(armor.DRMin(), max);
        }
        return dr;
    }

    @Override
    public float attackDelay() {
        float delay = super.attackDelay();
        return weapon == null ? delay : delay * weapon.delayFactor(this);
    }

    @Override
    public int attackProc(Char enemy, int damage) {
        damage = super.attackProc(enemy, damage);
        return weapon == null ? damage : weapon.proc(this, enemy, damage);
    }

    @Override
    public int defenseProc(Char enemy, int damage) {
        if (armor != null) damage = armor.proc(enemy, this, damage);
        return super.defenseProc(enemy, damage);
    }

    @Override
    protected boolean canAttack(Char enemy) {
        if (weapon != null && weapon.reachFactor(this) > 1) return weapon.canReach(this, enemy.pos);
        return super.canAttack(enemy);
    }

    // ---- turns ----

    @Override
    protected boolean act() {
        matchHostLevel();
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
        switch (kind) {
            case "W":
                waitOneTurn();
                sprite.showStatus(CharSprite.DEFAULT, Messages.get(Hero.class, "wait"));
                return true;
            case "A": {
                Actor target = Actor.findById(first);
                return target instanceof Char && strike((Char) target, friendlyFire);
            }
            case "E": return equip(first);
            case "R": return unequip(first);
            case "U": return use(first, second);
            case "H": return throwItem(first, second);
            case "D": return drop(first);
            case "G": return give(first);
            default: break;
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

        Heap container = level.heaps.get(cell);
        if (container != null && container.type != Heap.Type.HEAP && container.type != Heap.Type.FOR_SALE) {
            // Like the hero, the companion does not walk over a chest or remains: stepping into one opens it.
            return openContainer(container);
        }
        if (rooted || !level.passable[cell] || level.pit[cell] || Actor.findChar(cell) != null) return false;
        sprite.move(pos, cell);
        move(cell);
        spend(1 / speed());
        pickUpAt(cell);
        return true;
    }

    /**
     * Opens a chest, tomb or remains next to the companion, as {@code Hero.actOpenChest} and {@code Heap.open} do
     * for the hero. Locked and crystal chests need the host's keys, so they stay shut for the companion.
     */
    private boolean openContainer(Heap heap) {
        if (!Dungeon.level.adjacent(pos, heap.pos)) return false;
        if (heap.type == Heap.Type.LOCKED_CHEST || heap.type == Heap.Type.CRYSTAL_CHEST) {
            sprite.showStatus(CharSprite.NEUTRAL, "잠김");
            return false;
        }
        sprite.turnTo(pos, heap.pos);
        switch (heap.type) {
            case TOMB:
                Sample.INSTANCE.play(Assets.Sounds.TOMB);
                Wraith.spawnAround(pos);
                break;
            case SKELETON:
            case REMAINS:
                break;
            default:
                Sample.INSTANCE.play(Assets.Sounds.UNLOCK);
        }
        if (heap.haunted) {
            if (Wraith.spawnAt(heap.pos) == null) damage(HP / 2, heap);
            Sample.INSTANCE.play(Assets.Sounds.CURSED);
        }
        heap.type = Heap.Type.HEAP;
        if (heap.sprite != null) {
            heap.sprite.link();
            heap.sprite.drop();
        }
        spend(1f);
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

    // ---- items ----

    private Item bagItem(int index) {
        return index >= 0 && index < bag.size() ? bag.get(index) : null;
    }

    /** Puts an item into the bag, stacking it like the original backpack. False if there is no room. */
    boolean store(Item item) {
        if (item.stackable) {
            for (Item held : bag) {
                if (held.isSimilar(item)) {
                    held.quantity(held.quantity() + item.quantity());
                    return true;
                }
            }
        }
        if (bag.size() >= BAG_SIZE) return false;
        bag.add(item);
        return true;
    }

    /** Takes the top item of a plain heap the companion stepped on: gold to the party purse, dew to health. */
    private void pickUpAt(int cell) {
        Heap heap = Dungeon.level.heaps.get(cell);
        if (heap == null || heap.type != Heap.Type.HEAP || heap.hidden) return;
        Item item = heap.peek();
        if (item == null) return;
        if (item instanceof Gold) {
            heap.pickUp();
            Dungeon.gold += item.quantity();
            sprite.showStatus(CharSprite.NEUTRAL, "+" + item.quantity());
            Sample.INSTANCE.play(Assets.Sounds.GOLD, 1, 1, Random.Float(0.9f, 1.1f));
            return;
        }
        if (item instanceof Dewdrop) {
            if (HP >= HT) return;
            heap.pickUp();
            int healed = Math.min(HT - HP, Math.max(1, Math.round(HT * 0.05f)) * item.quantity());
            HP += healed;
            sprite.showStatus(CharSprite.POSITIVE, "+" + healed);
            Sample.INSTANCE.play(Assets.Sounds.DEWDROP);
            return;
        }
        if (!carriable(item) || !canStore(item)) return;
        heap.pickUp();
        store(item);
        Sample.INSTANCE.play(Assets.Sounds.ITEM);
        GLog.i(name() + ": " + item.title());
    }

    private boolean canStore(Item item) {
        if (bag.size() < BAG_SIZE) return true;
        if (!item.stackable) return false;
        for (Item held : bag) if (held.isSimilar(item)) return true;
        return false;
    }

    private boolean equip(int index) {
        Item item = bagItem(index);
        if (item instanceof MeleeWeapon) {
            if (weapon != null && weapon.cursed) return cursedRefusal(weapon);
            bag.remove(index);
            MeleeWeapon previous = weapon;
            weapon = (MeleeWeapon) item;
            if (previous != null) bag.add(index, previous);
            revealCurse(weapon);
        } else if (item instanceof Armor) {
            if (armor != null && armor.cursed) return cursedRefusal(armor);
            bag.remove(index);
            Armor previous = armor;
            armor = (Armor) item;
            if (previous != null) bag.add(index, previous);
            revealCurse(armor);
        } else {
            return false;
        }
        spend(1f);
        return true;
    }

    private boolean unequip(int slot) {
        if (bag.size() >= BAG_SIZE) return false;
        if (slot == SLOT_WEAPON && weapon != null) {
            if (weapon.cursed) return cursedRefusal(weapon);
            bag.add(weapon);
            weapon = null;
        } else if (slot == SLOT_ARMOR && armor != null) {
            if (armor.cursed) return cursedRefusal(armor);
            bag.add(armor);
            armor = null;
        } else {
            return false;
        }
        spend(1f);
        return true;
    }

    private void revealCurse(Item item) {
        if (item.cursed) {
            item.cursedKnown = true;
            sprite.showStatus(CharSprite.NEGATIVE, "저주");
            Sample.INSTANCE.play(Assets.Sounds.CURSED);
        }
    }

    private boolean cursedRefusal(Item item) {
        item.cursedKnown = true;
        sprite.showStatus(CharSprite.NEGATIVE, "저주받아 벗을 수 없음");
        return false;
    }

    /** True if the companion can drink this potion: its effect works on any character, or on the host. */
    public static boolean drinkable(Item item) {
        if (!(item instanceof Potion) || item instanceof com.shatteredpixel.shatteredpixeldungeon.items.potions.brews.Brew
                || item instanceof com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.Elixir
                || item instanceof com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.ExoticPotion) return false;
        return true;
    }

    /** A scroll of upgrade the party already knows: the only scroll the companion reads itself. */
    public static boolean readableUpgrade(Item item) {
        return item instanceof ScrollOfUpgrade && ((Scroll) item).isKnown();
    }

    private boolean use(int index, int target) {
        Item item = bagItem(index);
        if (item == null) return false;
        if (readableUpgrade(item)) return readUpgrade(index, target);
        if (target != 0 || !drinkable(item)) return false;
        Potion potion = (Potion) item;
        detachOne(index);
        Sample.INSTANCE.play(Assets.Sounds.DRINK);
        if (potion instanceof PotionOfHealing) {
            potion.identify(false);
            PotionOfHealing.cure(this);
            PotionOfHealing.heal(this);
        } else if (potion instanceof PotionOfHaste) {
            potion.identify(false);
            Buff.prolong(this, Haste.class, Haste.DURATION);
        } else if (potion instanceof PotionOfInvisibility) {
            potion.identify(false);
            Buff.prolong(this, Invisibility.class, Invisibility.DURATION);
        } else if (potion instanceof PotionOfLevitation) {
            potion.identify(false);
            Buff.prolong(this, Levitation.class, Levitation.DURATION);
        } else if (potion instanceof PotionOfPurity) {
            potion.identify(false);
            Buff.prolong(this, BlobImmunity.class, BlobImmunity.DURATION);
        } else if (potion instanceof PotionOfExperience) {
            // The original only defines these for the hero; the party shares the host's strength and level.
            potion.identify(false);
            Hero hero = Dungeon.hero;
            GLog.p(name() + ": " + potion.name() + " → " + hero.name());
            hero.sprite.showStatus(CharSprite.POSITIVE, Integer.toString(hero.maxExp()));
            hero.earnExp(hero.maxExp(), PotionOfExperience.class);
        } else if (potion instanceof PotionOfStrength || potion instanceof PotionOfMindVision) {
            GLog.p(name() + ": " + potion.name() + " → " + Dungeon.hero.name());
            potion.apply(Dungeon.hero);
        } else {
            // Every other plain potion spills where it is drunk, as the original default apply does.
            potion.shatter(pos);
        }
        spend(1f);
        return true;
    }

    private boolean readUpgrade(int index, int target) {
        Item upgraded = target == UPGRADE_WEAPON ? weapon : target == UPGRADE_ARMOR ? armor : null;
        if (upgraded == null || !upgraded.isUpgradable()) return false;
        detachOne(index);
        upgraded.upgrade();
        Sample.INSTANCE.play(Assets.Sounds.READ);
        sprite.showStatus(CharSprite.POSITIVE, upgraded.title());
        spend(1f);
        return true;
    }

    private boolean throwItem(int index, int cell) {
        Item item = bagItem(index);
        Level level = Dungeon.level;
        if (!(item instanceof Potion) || !level.insideMap(cell) || cell == pos
                || !InnOriginalParty.current().explored()[cell]) return false;
        Potion potion = (Potion) detachOne(index);
        int landing = new Ballistica(pos, cell, Ballistica.PROJECTILE).collisionPos;
        sprite.turnTo(pos, landing);
        potion.shatter(landing);
        spend(1f);
        return true;
    }

    private boolean drop(int index) {
        Item item = bagItem(index);
        if (item == null) return false;
        bag.remove(index);
        Heap heap = Dungeon.level.drop(item, pos);
        if (heap.sprite != null) heap.sprite.drop(pos);
        Sample.INSTANCE.play(Assets.Sounds.ITEM);
        spend(1f);
        return true;
    }

    /** Hands an item to the host's backpack, e.g. scrolls, food or anything only the hero can use. */
    private boolean give(int index) {
        Item item = bagItem(index);
        Hero hero = Dungeon.hero;
        if (item == null || hero == null || !hero.isAlive()) return false;
        bag.remove(index);
        if (!item.collect(hero.belongings.backpack)) {
            bag.add(index, item);
            return false;
        }
        GLog.p(name() + " → " + hero.name() + ": " + item.title());
        Sample.INSTANCE.play(Assets.Sounds.ITEM);
        spend(1f);
        return true;
    }

    /** Removes one item of a stack (or the whole item) from the bag and returns it. */
    private Item detachOne(int index) {
        Item item = bag.get(index);
        if (item.quantity() > 1) return item.split(1);
        bag.remove(index);
        return item;
    }

    @Override
    public boolean heroShouldInteract() {
        InnOriginalParty party = InnOriginalParty.current();
        return party == null || !party.friendlyFire;
    }

    // ---- saving ----

    private static final String WEAPON = "inn_weapon";
    private static final String ARMOR = "inn_armor";
    private static final String BAG = "inn_bag";

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        if (weapon != null) bundle.put(WEAPON, weapon);
        if (armor != null) bundle.put(ARMOR, armor);
        bundle.put(BAG, bag);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        weapon = bundle.contains(WEAPON) ? (MeleeWeapon) bundle.get(WEAPON) : null;
        armor = bundle.contains(ARMOR) ? (Armor) bundle.get(ARMOR) : null;
        bag.clear();
        if (bundle.contains(BAG)) {
            for (Bundlable item : bundle.getCollection(BAG)) {
                if (item instanceof Item && bag.size() < BAG_SIZE) bag.add((Item) item);
            }
        }
    }
}
