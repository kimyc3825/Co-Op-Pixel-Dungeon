// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.html;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.AmuletScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.RankingsScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.StartScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.SurfaceScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.watabou.noosa.tweeners.Delayer;

final class WebInnGame extends ShatteredPixelDungeon {
    private boolean returning;

    WebInnGame() {
        super(new WebPlatformSupport(), StartScene.class);
    }

    @Override
    protected void step() {
        if (returning) return;
        if (requestedReset && (sceneClass == TitleScene.class ||
                (sceneClass == RankingsScene.class && (scene instanceof AmuletScene || scene instanceof SurfaceScene)))) {
            returnToInn();
            return;
        }
        super.step();
    }

    @Override
    public String runExitLabel() {
        return "저장하고 여관으로";
    }

    @Override
    public boolean handleRunDeath() {
        if (!(scene instanceof GameScene)) return false;
        scene.add(new Delayer(1.5f) {
            @Override
            protected void onComplete() {
                returnToInn();
            }
        });
        return true;
    }

    @Override
    public void finish() {
        returnToInn();
    }

    void returnToInn() {
        if (returning) return;
        returning = true;
        pause();
        WebJS.exit();
    }
}
