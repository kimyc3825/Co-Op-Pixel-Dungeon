// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.TitleBackground;
import com.watabou.noosa.Camera;

public final class NativeAvatarScene extends PixelScene {
    @Override
    public void create() {
        super.create();
        add(new TitleBackground(Camera.main.width, Camera.main.height));
        add(new AvatarProfileWindow(NativeClient.nickname(), NativeClient.appearance(), true, (name, appearance) -> {
            NativeClient.saveProfile(name, appearance);
            ShatteredPixelDungeon.switchNoFade(NativeInnScene.class);
        }, () -> { }));
        fadeIn();
    }
}
