// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.shatteredpixel.shatteredpixeldungeon.InnAtb;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.ui.Component;

/** Compact action budget indicator for the original game HUD. */
public final class InnAtbMeter extends Component {
    private ColorBlock back;
    private ColorBlock track;
    private ColorBlock fill;
    private RenderedTextBlock text;
    private int lastValue = -1;

    public InnAtbMeter() {
        setSize(68, 14);
    }

    @Override
    protected void createChildren() {
        add(back = new ColorBlock(1, 1, 0xD51A211D));
        add(track = new ColorBlock(1, 1, 0xFF333E34));
        add(fill = new ColorBlock(1, 1, 0xFF91CBB0));
        add(text = PixelScene.renderTextBlock(6));
        text.hardlight(0xCBEED9);
    }

    @Override
    protected void layout() {
        back.x = x;
        back.y = y;
        back.size(width, height);
        track.x = fill.x = x + 2;
        track.y = fill.y = y + 10;
        track.size(width - 4, 2);
        text.setPos(x + 3, y + 1);
        refresh();
    }

    @Override
    public void update() {
        super.update();
        refresh();
    }

    private void refresh() {
        float energy = InnAtb.energy();
        fill.size((width - 4) * energy / InnAtb.CAPACITY, 2);
        int value = (int) (energy + 0.0001f);
        if (lastValue != value) {
            lastValue = value;
            text.text("ATB  " + value + "/" + (int) InnAtb.CAPACITY);
        }
    }
}
