// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.sprites;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.shatteredpixel.shatteredpixeldungeon.InnAdventure;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Composes the inn profile into the original game's full armor and animation sheet. */
final class InnHeroAppearance {
    private static final String DIRECTORY = "sprites/inn-avatars/";
    private static final Map<HeroClass, SmartTexture> textures = new EnumMap<>(HeroClass.class);
    private static LayerLayout layout;
    private static InnAdventure.Profile cachedProfile;

    private InnHeroAppearance() {}

    static Object texture(HeroClass heroClass) {
        InnAdventure.Profile profile = InnAdventure.profile();
        if (profile == null) return heroClass.spritesheet();
        if (layout == null) {
            FileHandle metadata = Gdx.files.internal(DIRECTORY + "layers.txt");
            if (!metadata.exists()) return heroClass.spritesheet();
            layout = LayerLayout.read(metadata);
        }
        int[] layers = layout.rows(profile);
        if (layers == null) return heroClass.spritesheet();

        if (!profile.equals(cachedProfile)) {
            for (SmartTexture previous : textures.values()) {
                if (previous.bitmap != null) TextureCache.remove(previous.bitmap);
            }
            textures.clear();
            cachedProfile = profile;
        }
        SmartTexture texture = textures.get(heroClass);
        // The original scenes can clear TextureCache between floors or on a scale change.
        if (texture != null && texture.bitmap != null) return texture;

        FileHandle asset = Gdx.files.internal(DIRECTORY + heroClass.name().toLowerCase(Locale.ROOT) + ".png");
        if (!asset.exists()) return heroClass.spritesheet();
        SmartTexture original = TextureCache.get(heroClass.spritesheet());
        Pixmap composed = compose(asset, original.width, original.height, layers);
        texture = TextureCache.get(composed);
        textures.put(heroClass, texture);
        return texture;
    }

    private static Pixmap compose(FileHandle asset, int width, int height, int[] layers) {
        Pixmap atlas = new Pixmap(asset);
        Pixmap result = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        try {
            result.setBlending(Pixmap.Blending.SourceOver);
            for (int layer : layers) {
                int x = layer % layout.columns * width;
                int y = layer / layout.columns * height;
                result.drawPixmap(atlas, x, y, width, height, 0, 0, width, height);
            }
            return result;
        } catch (RuntimeException failure) {
            result.dispose();
            throw failure;
        } finally {
            atlas.dispose();
        }
    }

    private static final class LayerLayout {
        private final int columns;
        private final String[] outfits;
        private final String[] hairstyles;
        private final String[] hairColors;
        private final String[] accessories;

        private LayerLayout(int columns, String[] outfits, String[] hairstyles, String[] hairColors,
                            String[] accessories) {
            this.columns = columns;
            this.outfits = outfits;
            this.hairstyles = hairstyles;
            this.hairColors = hairColors;
            this.accessories = accessories;
        }

        static LayerLayout read(FileHandle file) {
            String[] lines = file.readString("UTF-8").split("\n");
            return new LayerLayout(Integer.parseInt(lines[0].trim()), lines[1].trim().split(","),
                lines[2].trim().split(","), lines[3].trim().split(","), lines[4].trim().split(","));
        }

        int[] rows(InnAdventure.Profile profile) {
            int outfit = index(outfits, profile.outfitColor());
            int style = index(hairstyles, profile.hairStyle());
            int color = index(hairColors, profile.hairColor());
            int accessory = index(accessories, profile.accessory());
            if (outfit == 0 && style == 0 && color == 0 && accessory == 0) return null;
            int hairRow = 1 + outfits.length + style * hairColors.length + color;
            int accessoryRow = 1 + outfits.length + hairstyles.length * hairColors.length + accessory;
            return new int[]{0, 1 + outfit, hairRow, accessoryRow};
        }

        private static int index(String[] options, String value) {
            for (int i = 0; i < options.length; i++) {
                if (options[i].equals(value)) return i;
            }
            return 0;
        }
    }
}
