// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.shatteredpixel.shatteredpixeldungeon.InnAdventure;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopPlatformSupport;
import com.shatteredpixel.shatteredpixeldungeon.scenes.AmuletScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.RankingsScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.SurfaceScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.watabou.noosa.tweeners.Delayer;
import com.watabou.utils.PlatformSupport;

final class NativeGame extends ShatteredPixelDungeon {
    private final InnApi api;

    NativeGame(PlatformSupport platform, InnApi api) {
        super(platform, NativeInnScene.class);
        this.api = api;
    }

    @Override
    public void create() {
        if (platform instanceof DesktopPlatformSupport desktop) desktop.enableWindowImeOnTextInputOnly();
        super.create();
        NativeClient.start(api);
        if (!NativeClient.profileConfirmed()) switchNoFade(NativeAvatarScene.class);
    }

    @Override
    protected void step() {
        if (requestedReset && NativeClient.inSolo()) {
            if (sceneClass == TitleScene.class) {
                NativeClient.returnToInn();
            } else if (sceneClass == RankingsScene.class && (scene instanceof AmuletScene || scene instanceof SurfaceScene)) {
                NativeClient.returnToInn("원본 던전을 클리어하고 여관으로 돌아왔습니다.");
            }
        }
        super.step();
    }

    @Override
    public boolean handleRunDeath() {
        if (!NativeClient.inSolo() || !(scene instanceof GameScene)) return false;
        scene.add(new Delayer(1.5f) {
            @Override
            protected void onComplete() {
                NativeClient.returnDefeated();
            }
        });
        return true;
    }

    @Override
    public String runExitLabel() {
        if (!NativeClient.inSolo()) return null;
        return InnAdventure.atb() ? "여관으로 철수" : "저장하고 여관으로";
    }

    @Override
    public void finish() {
        if (NativeClient.inSolo()) NativeClient.returnToInn();
        else super.finish();
    }

    @Override
    public void destroy() {
        try {
            NativeClient.shutdown();
            super.destroy();
        } finally {
            if (platform instanceof DesktopPlatformSupport desktop) desktop.restoreWindowIme();
        }
    }
}
