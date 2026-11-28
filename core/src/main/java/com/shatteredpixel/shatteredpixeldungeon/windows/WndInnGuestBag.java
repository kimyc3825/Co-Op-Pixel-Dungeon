// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.windows;

import com.shatteredpixel.shatteredpixeldungeon.InnOriginalParty;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnPartyMember;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Game;

import java.util.ArrayList;
import java.util.List;

/**
 * The party guest's bag: the equipped weapon and armor, then the bag slots, laid out like the original
 * backpack. Choosing an item lists what the guest may do with it; the host carries the action out.
 */
public final class WndInnGuestBag extends Window {
	private static final int COLUMNS = 5;
	private static final int SLOT = 22;
	private static final int GAP = 1;
	private static final int NORMAL = 0x9953564D;
	private static final int EQUIPPED = 0x9991938C;
	private static final int CURSED = 0x99A13B3B;

	/** What the guest chose; implemented by the guest scene, which sends the command to the host. */
	public interface Actions {
		void equip(int index);
		void unequip(int slot);
		void use(int index, int target);
		void throwAt(int index);
		void drop(int index);
		void give(int index);
	}

	private final Actions actions;
	private final List<InnOriginalParty.Gear> gear;

	public WndInnGuestBag(List<InnOriginalParty.Gear> gear, int gold, Actions actions) {
		this.gear = gear;
		this.actions = actions;
		int width = COLUMNS * (SLOT + GAP) - GAP;

		RenderedTextBlock title = PixelScene.renderTextBlock("배낭 · 금화 " + gold, 9);
		title.hardlight(TITLE_COLOR);
		title.setPos(0, 0);
		add(title);
		float y = title.bottom() + 3;

		InnOriginalParty.Gear weapon = find(InnOriginalParty.GEAR_WEAPON);
		InnOriginalParty.Gear armor = find(InnOriginalParty.GEAR_ARMOR);
		addSlot(0, y, weapon, ItemSpriteSheet.WEAPON_HOLDER);
		addSlot(SLOT + GAP, y, armor, ItemSpriteSheet.ARMOR_HOLDER);
		y += SLOT + GAP;

		for (int i = 0; i < InnPartyMember.BAG_SIZE; i++) {
			int column = i % COLUMNS;
			int row = i / COLUMNS;
			addSlot(column * (SLOT + GAP), y + row * (SLOT + GAP), find(i), -1);
		}
		y += (InnPartyMember.BAG_SIZE / COLUMNS) * (SLOT + GAP);
		resize(width, (int) Math.ceil(y - GAP));
	}

	private InnOriginalParty.Gear find(int slot) {
		for (InnOriginalParty.Gear item : gear) if (item.slot == slot) return item;
		return null;
	}

	private void addSlot(float x, float y, InnOriginalParty.Gear item, int placeholder) {
		Slot slot = new Slot(item, placeholder);
		slot.setRect(x, y, SLOT, SLOT);
		add(slot);
	}

	private void choose(InnOriginalParty.Gear item) {
		List<String> labels = new ArrayList<>();
		List<Runnable> choices = new ArrayList<>();
		boolean cursedEquipped = item.equipped() && item.has(InnOriginalParty.FLAG_CURSED);
		if (item.equipped()) {
			if (!cursedEquipped) {
				labels.add("벗기");
				choices.add(() -> actions.unequip(item.slot == InnOriginalParty.GEAR_WEAPON
						? InnPartyMember.SLOT_WEAPON : InnPartyMember.SLOT_ARMOR));
			}
		} else {
			int index = item.slot;
			if (item.has(InnOriginalParty.FLAG_WEAPON) || item.has(InnOriginalParty.FLAG_ARMOR)) {
				labels.add("장착");
				choices.add(() -> actions.equip(index));
			}
			if (item.has(InnOriginalParty.FLAG_DRINK)) {
				labels.add("마시기");
				choices.add(() -> actions.use(index, 0));
			}
			if (item.has(InnOriginalParty.FLAG_UPGRADE)) {
				InnOriginalParty.Gear weapon = find(InnOriginalParty.GEAR_WEAPON);
				InnOriginalParty.Gear armor = find(InnOriginalParty.GEAR_ARMOR);
				if (weapon != null && weapon.has(InnOriginalParty.FLAG_UPGRADABLE)) {
					labels.add("무기 강화");
					choices.add(() -> actions.use(index, InnPartyMember.UPGRADE_WEAPON));
				}
				if (armor != null && armor.has(InnOriginalParty.FLAG_UPGRADABLE)) {
					labels.add("방어구 강화");
					choices.add(() -> actions.use(index, InnPartyMember.UPGRADE_ARMOR));
				}
			}
			if (item.has(InnOriginalParty.FLAG_THROW)) {
				labels.add("던지기");
				choices.add(() -> actions.throwAt(index));
			}
			labels.add("파티장에게 주기");
			choices.add(() -> actions.give(index));
			labels.add("버리기");
			choices.add(() -> actions.drop(index));
		}
		String message = cursedEquipped ? "저주받아 벗을 수 없습니다." : labels.isEmpty() ? "할 수 있는 일이 없습니다." : "";
		Game.scene().addToFront(new WndOptions(new ItemSprite(item.image), item.title, message,
				labels.toArray(new String[0])) {
			@Override
			protected void onSelect(int choice) {
				if (choice < 0 || choice >= choices.size()) return;
				WndInnGuestBag.this.hide();
				choices.get(choice).run();
			}
		});
	}

	private final class Slot extends Button {
		private final InnOriginalParty.Gear item;
		private ColorBlock bg;
		private ItemSprite sprite;
		private BitmapText quantity;

		Slot(InnOriginalParty.Gear item, int placeholder) {
			this.item = item;
			int color = item == null ? NORMAL : item.has(InnOriginalParty.FLAG_CURSED) ? CURSED
					: item.equipped() ? EQUIPPED : NORMAL;
			bg.color(color);
			if (item != null) {
				sprite = new ItemSprite(item.image);
				add(sprite);
				if (item.quantity > 1) {
					quantity = new BitmapText(Integer.toString(item.quantity), PixelScene.pixelFont);
					quantity.measure();
					add(quantity);
				}
			} else if (placeholder >= 0) {
				sprite = new ItemSprite(placeholder);
				sprite.alpha(0.4f);
				add(sprite);
			}
		}

		@Override
		protected void createChildren() {
			super.createChildren();
			bg = new ColorBlock(1, 1, NORMAL);
			add(bg);
		}

		@Override
		protected void layout() {
			super.layout();
			bg.x = x;
			bg.y = y;
			bg.size(width, height);
			if (sprite != null) {
				sprite.x = x + (width - sprite.width()) / 2f;
				sprite.y = y + (height - sprite.height()) / 2f;
				PixelScene.align(sprite);
			}
			if (quantity != null) {
				quantity.x = x + 1;
				quantity.y = y;
				PixelScene.align(quantity);
			}
		}

		@Override
		protected void onClick() {
			if (item != null) choose(item);
		}

		@Override
		protected String hoverText() {
			return item == null ? null : item.title;
		}
	}
}
