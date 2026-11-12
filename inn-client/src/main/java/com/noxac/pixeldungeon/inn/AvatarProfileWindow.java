// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.MovieClip;
import com.watabou.noosa.TextInput;
import com.watabou.noosa.TextureFilm;

import java.util.List;
import java.util.function.BiConsumer;

final class AvatarProfileWindow extends Window {
    private static final int WIDTH = 226;
    private static final int HEIGHT = 146;
    private static final int OPTIONS_X = 68;
    private static final int OPTIONS_WIDTH = WIDTH - OPTIONS_X - 5;
    private static final int PREVIEW_X = 5;
    private static final int PREVIEW_WIDTH = 56;
    private static final int TEXT = 0xf9e5c8;
    private static final int GOLD = 0xefbb77;
    private static final int MUTED = 0xcab29d;

    private final AvatarProfile.Catalog catalog = AvatarProfile.catalog();
    private final boolean required;
    private final BiConsumer<String, InnApi.Appearance> save;
    private final Runnable closed;
    private final TextInput nickname;
    private final StyledButton[] jobs;
    private final Picker hairColor;
    private final Picker outfitColor;
    private final Picker hairStyle;
    private final Picker accessory;
    private final MovieClip preview = new MovieClip();
    private final RenderedTextBlock previewName;
    private final RenderedTextBlock error;
    private int job;
    private AvatarSprites previewStrip;

    AvatarProfileWindow(String initialName, InnApi.Appearance initial, boolean required,
                        BiConsumer<String, InnApi.Appearance> save, Runnable closed) {
        super(WIDTH, HEIGHT);
        this.required = required;
        this.save = save;
        this.closed = closed;
        InnApi.Appearance selected = AvatarProfile.normalize(initial);
        job = AvatarSprites.indexOf(catalog.jobs(), selected.job());

        text(required ? "모험가 생성" : "모험가 프로필", 11, GOLD, 5, 4, 155);
        block(PREVIEW_X, 24, PREVIEW_WIDTH, 100, 0xff221b1a);
        block(PREVIEW_X + 2, 88, PREVIEW_WIDTH - 4, 12, 0xff43312c);
        block(PREVIEW_X + 2, 88, PREVIEW_WIDTH - 4, 1, 0xff6e5040);
        text("미리보기", 6, MUTED, PREVIEW_X + 6, 30, PREVIEW_WIDTH - 12);
        preview.scale.set(3);
        preview.x = PREVIEW_X + (PREVIEW_WIDTH - AvatarSprites.FRAME_WIDTH * 3) / 2f;
        preview.y = 45;
        add(preview);
        previewName = text("", 7, GOLD, PREVIEW_X + 4, 108, PREVIEW_WIDTH - 8);
        error = text("", 6, 0xf4a593, 5, 132, WIDTH - 98);
        error.visible = false;

        text("닉네임", 7, MUTED, OPTIONS_X, 22, OPTIONS_WIDTH);
        nickname = new TextInput(Chrome.get(Chrome.Type.TOAST_WHITE), false, PixelScene.defaultZoom * 7) {
            @Override
            public void enterPressed() { apply(); }

            @Override
            public void onChanged() { error.visible = false; }
        };
        nickname.setMaxLength(20);
        nickname.setText(initialName == null ? "" : initialName);
        add(nickname);
        nickname.setRect(OPTIONS_X, 33, OPTIONS_WIDTH, 15);

        jobs = new StyledButton[catalog.jobs().size()];
        float jobWidth = (OPTIONS_WIDTH - 4) / 3f;
        for (int i = 0; i < jobs.length; i++) {
            int option = i;
            jobs[i] = button(catalog.jobs().get(i).label(), () -> selectJob(option));
            jobs[i].setRect(OPTIONS_X + i % 3 * (jobWidth + 2), 51 + i / 3 * 15, jobWidth, 13);
        }

        float pickerWidth = (OPTIONS_WIDTH - 5) / 2f;
        float secondX = OPTIONS_X + pickerWidth + 5;
        hairColor = new Picker("머리색", catalog.hairColors(), selected.hairColor(), OPTIONS_X, 81, pickerWidth);
        outfitColor = new Picker("옷 색", catalog.outfitColors(), selected.outfitColor(), secondX, 81, pickerWidth);
        hairStyle = new Picker("머리 모양", catalog.hairStyles(), selected.hairStyle(), OPTIONS_X, 103, pickerWidth);
        accessory = new Picker("장식", catalog.accessories(), selected.accessory(), secondX, 103, pickerWidth);

        if (!required) button("취소", this::hide).setRect(WIDTH - 87, 128, 39, 15);
        button(required ? "여관 입장" : "저장", this::apply).setRect(WIDTH - 46, 128, 41, 15);
        refreshPreview();
    }

    private void selectJob(int index) {
        if (job == index) return;
        job = index;
        refreshPreview();
    }

    private InnApi.Appearance selected() {
        return new InnApi.Appearance(catalog.jobs().get(job).id(), hairColor.option().id(), outfitColor.option().id(),
            hairStyle.option().id(), accessory.option().id());
    }

    private void refreshPreview() {
        AvatarSprites next = AvatarSprites.acquire(selected(), Assets.Sprites.WARRIOR);
        Object texture = next.texture();
        preview.texture(texture);
        TextureFilm atlas = new TextureFilm(texture, AvatarSprites.FRAME_WIDTH, AvatarSprites.FRAME_HEIGHT);
        preview.play(new MovieClip.Animation(2, true).frames(atlas, 0, 0, 0, 1), true);
        if (previewStrip != null) previewStrip.close();
        previewStrip = next;
        previewName.text(catalog.jobs().get(job).label());
        previewName.setPos(PREVIEW_X + (PREVIEW_WIDTH - previewName.width()) / 2, 108);
        for (int i = 0; i < jobs.length; i++) jobs[i].textColor(i == job ? GOLD : TEXT);
    }

    private void apply() {
        String value = nickname.getText().strip();
        if (!LobbySession.validName(value)) {
            error.text("닉네임을 1~20자로 입력하세요.");
            error.visible = true;
            return;
        }
        save.accept(value, selected());
        hide();
    }

    @Override
    public void onBackPressed() {
        if (!required) super.onBackPressed();
    }

    @Override
    public void destroy() {
        super.destroy();
        if (previewStrip != null) previewStrip.close();
        closed.run();
    }

    private StyledButton button(String title, Runnable click) {
        StyledButton button = new StyledButton(Chrome.Type.GREY_BUTTON_TR, title, 7) {
            @Override
            protected void onClick() { click.run(); }
        };
        add(button);
        return button;
    }

    private RenderedTextBlock text(String value, int size, int color, float x, float y, int width) {
        RenderedTextBlock label = PixelScene.renderTextBlock(value, size);
        label.setHightlighting(false);
        label.hardlight(color);
        label.maxWidth(width);
        label.setPos(x, y);
        add(label);
        return label;
    }

    private ColorBlock block(float x, float y, float width, float height, int color) {
        ColorBlock block = new ColorBlock(width, height, color);
        block.x = x;
        block.y = y;
        add(block);
        return block;
    }

    private final class Picker {
        private final List<AvatarProfile.Option> choices;
        private final RenderedTextBlock caption;
        private final ColorBlock swatch;
        private final float x;
        private final float y;
        private final float width;
        private int index;

        Picker(String title, List<AvatarProfile.Option> choices, String initial, float x, float y, float width) {
            this.choices = choices;
            this.x = x;
            this.y = y;
            this.width = width;
            index = AvatarSprites.indexOf(choices, initial);
            text(title, 6, MUTED, x, y, (int) width);
            button("<", () -> change(-1)).setRect(x, y + 8, 13, 12);
            button(">", () -> change(1)).setRect(x + width - 13, y + 8, 13, 12);
            swatch = block(x + 14, y + 11, 5, 5, 0xffffffff);
            caption = text("", 6, TEXT, x + 15, y + 10, (int) width - 28);
            refreshCaption();
        }

        AvatarProfile.Option option() { return choices.get(index); }

        private void change(int offset) {
            index = Math.floorMod(index + offset, choices.size());
            refreshCaption();
            refreshPreview();
        }

        private void refreshCaption() {
            AvatarProfile.Option option = option();
            String color = option.swatch();
            swatch.visible = color != null && color.matches("#[0-9a-fA-F]{6}");
            if (swatch.visible) swatch.hardlight(Integer.parseUnsignedInt(color.substring(1), 16));
            caption.text(option.label());
            float swatchWidth = swatch.visible ? 8 : 0;
            float left = x + (width - caption.width() - swatchWidth) / 2;
            swatch.x = left;
            caption.setPos(left + swatchWidth, y + 11);
        }
    }
}
