// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.watabou.gltextures.TextureCache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns a reference to the assembled animation strip while a figure displays it. */
final class AvatarSprites implements AutoCloseable {
    static final int FRAME_WIDTH = 12;
    static final int FRAME_HEIGHT = 15;
    private static final String DIRECTORY = "inn/avatars/";
    // Avatar textures are created and released on the render thread.
    private static final Map<InnApi.Appearance, Strip> strips = new HashMap<>();

    private static final class Strip {
        final Pixmap pixels;
        int users;

        Strip(Pixmap pixels) { this.pixels = pixels; }
    }

    private final InnApi.Appearance appearance;
    private final Strip strip;
    private final Object texture;
    private boolean closed;

    private AvatarSprites(InnApi.Appearance appearance, Strip strip, Object texture) {
        this.appearance = appearance;
        this.strip = strip;
        this.texture = texture;
    }

    static AvatarSprites acquire(InnApi.Appearance appearance, String fallback) {
        InnApi.Appearance normalized = AvatarProfile.normalize(appearance);
        Strip strip = strips.get(normalized);
        if (strip == null) {
            FileHandle file = Gdx.files.internal(DIRECTORY + normalized.job() + ".png");
            if (!file.exists()) return new AvatarSprites(normalized, null, fallback);
            strip = new Strip(compose(file, normalized));
            strips.put(normalized, strip);
        }
        strip.users++;
        return new AvatarSprites(normalized, strip, TextureCache.get(strip.pixels));
    }

    private static Pixmap compose(FileHandle file, InnApi.Appearance appearance) {
        AvatarProfile.Catalog catalog = AvatarProfile.catalog();
        int outfitCount = catalog.outfitColors().size();
        int hairColorCount = catalog.hairColors().size();
        int hairStyleCount = catalog.hairStyles().size();
        int outfit = 1 + indexOf(catalog.outfitColors(), appearance.outfitColor());
        int hair = 1 + outfitCount + indexOf(catalog.hairStyles(), appearance.hairStyle()) * hairColorCount +
            indexOf(catalog.hairColors(), appearance.hairColor());
        int accessory = 1 + outfitCount + hairStyleCount * hairColorCount + indexOf(catalog.accessories(), appearance.accessory());
        int width = FRAME_WIDTH * catalog.frames();
        Pixmap source = new Pixmap(file);
        Pixmap output = new Pixmap(width, FRAME_HEIGHT, Pixmap.Format.RGBA8888);
        try {
            output.setBlending(Pixmap.Blending.SourceOver);
            for (int row : new int[]{0, outfit, hair, accessory}) {
                output.drawPixmap(source, 0, row * FRAME_HEIGHT, width, FRAME_HEIGHT, 0, 0, width, FRAME_HEIGHT);
            }
            return output;
        } catch (RuntimeException failure) {
            output.dispose();
            throw failure;
        } finally {
            source.dispose();
        }
    }

    static int indexOf(List<AvatarProfile.Option> choices, String id) {
        for (int i = 0; i < choices.size(); i++) if (choices.get(i).id().equals(id)) return i;
        return 0;
    }

    Object texture() { return texture; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (strip != null && --strip.users == 0) {
            strips.remove(appearance);
            TextureCache.remove(strip.pixels);
        }
    }
}
