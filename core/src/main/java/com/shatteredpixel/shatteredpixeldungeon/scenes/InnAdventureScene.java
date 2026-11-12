// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.scenes;

import com.shatteredpixel.shatteredpixeldungeon.Badges;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.InnAdventure;
import com.shatteredpixel.shatteredpixeldungeon.journal.Journal;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.TitleBackground;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;

import java.util.ArrayList;

/** Enters the single-player dungeon with the class chosen at the inn. */
public final class InnAdventureScene extends PixelScene {
    @Override
    public void create() {
        super.create();
        InnAdventure.Profile profile = InnAdventure.profile();
        if (profile == null) {
            Game.switchScene(StartScene.class);
            return;
        }
        Badges.loadGlobal();
        Journal.loadGlobal();

        ArrayList<GamesInProgress.Info> saves = GamesInProgress.checkAll();
        saves.sort(GamesInProgress.lastPlayedComparator);
        for (GamesInProgress.Info save : saves) {
            if (save.heroClass == profile.heroClass() && !save.daily && !save.dailyReplay) {
                enter(save.slot, true);
                return;
            }
        }
        for (int slot = GamesInProgress.firstSlot(), end = slot + GamesInProgress.MAX_SLOTS; slot < end; slot++) {
            if (!GamesInProgress.gameExists(slot)) {
                enter(slot, false);
                return;
            }
        }
        chooseSavedAdventure(saves);
    }

    private void enter(int slot, boolean resume) {
        GamesInProgress.curSlot = slot;
        GamesInProgress.selectedClass = InnAdventure.profile().heroClass();
        GamesInProgress.randomizedClass = false;
        Dungeon.hero = null;
        Dungeon.daily = Dungeon.dailyReplay = false;
        ActionIndicator.clearAction();
        if (!resume) Dungeon.initSeed();
        InterlevelScene.mode = resume ? InterlevelScene.Mode.CONTINUE : InterlevelScene.Mode.DESCEND;
        Game.switchScene(InterlevelScene.class);
    }

    private void chooseSavedAdventure(ArrayList<GamesInProgress.Info> saves) {
        uiCamera.visible = false;
        add(new TitleBackground(Camera.main.width, Camera.main.height));
        String[] choices = new String[saves.size() + 1];
        for (int i = 0; i < saves.size(); i++) {
            GamesInProgress.Info save = saves.get(i);
            choices[i] = save.heroClass.title() + " · " + save.depth + "층 · Lv. " + save.level;
        }
        choices[saves.size()] = "여관으로";
        add(new WndOptions("저장된 원정", "저장 공간이 가득 찼습니다. 이어갈 원정을 고르세요.", choices) {
            @Override
            protected void onSelect(int index) {
                if (index == saves.size()) Game.instance.finish();
                else enter(saves.get(index).slot, true);
            }

            @Override
            public void onBackPressed() {
                Game.instance.finish();
            }
        });
        fadeIn();
    }
}
