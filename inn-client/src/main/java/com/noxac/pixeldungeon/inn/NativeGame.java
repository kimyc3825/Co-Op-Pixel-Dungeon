// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.watabou.utils.PlatformSupport;

final class NativeGame extends ShatteredPixelDungeon {
    private final InnApi api;

    NativeGame(PlatformSupport platform, InnApi api) {
        super(platform, NativeInnScene.class);
        this.api = api;
    }

    @Override
    public void create() {
        super.create();
        NativeClient.start(api);
    }

    @Override
    protected void step() {
        if (requestedReset && sceneClass == TitleScene.class && NativeClient.inSolo()) {
            NativeClient.returnToInn();
        }
        super.step();
    }

    @Override
    public void finish() {
        if (NativeClient.inSolo()) NativeClient.returnToInn();
        else super.finish();
    }

    @Override
    public void destroy() {
        NativeClient.shutdown();
        super.destroy();
    }
}
