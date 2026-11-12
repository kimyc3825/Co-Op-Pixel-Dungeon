// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

final class AvatarProfile {
    record Option(String id, String label, String swatch) {}
    record Catalog(int frameWidth, int frameHeight, int frames, List<Option> jobs, List<Option> hairColors,
                   List<Option> outfitColors, List<Option> hairStyles, List<Option> accessories) {}
    record Saved(String name, InnApi.Appearance appearance) {}

    private static final Gson GSON = new Gson();
    private static final Catalog CATALOG = readCatalog();

    private AvatarProfile() {}

    static Catalog catalog() { return CATALOG; }

    static InnApi.Appearance normalize(InnApi.Appearance value) {
        return new InnApi.Appearance(
            option(CATALOG.jobs(), value == null ? null : value.job()),
            option(CATALOG.hairColors(), value == null ? null : value.hairColor()),
            option(CATALOG.outfitColors(), value == null ? null : value.outfitColor()),
            option(CATALOG.hairStyles(), value == null ? null : value.hairStyle()),
            option(CATALOG.accessories(), value == null ? null : value.accessory()));
    }

    private static String option(List<Option> options, String id) {
        if (id != null && options.stream().anyMatch(option -> id.equals(option.id()))) return id;
        return options.getFirst().id();
    }

    static Saved restore(String encoded) {
        try {
            Saved saved = GSON.fromJson(encoded, Saved.class);
            if (saved != null && saved.name() != null && LobbySession.validName(saved.name().strip())) {
                return new Saved(saved.name().strip(), normalize(saved.appearance()));
            }
        } catch (JsonParseException ignored) {
            // Reopen the editor if a stored profile cannot be decoded.
        }
        return null;
    }

    static String save(String name, InnApi.Appearance appearance) {
        return GSON.toJson(new Saved(name, appearance));
    }

    private static Catalog readCatalog() {
        var stream = Objects.requireNonNull(AvatarProfile.class.getResourceAsStream("/inn/avatars/catalog.json"));
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, Catalog.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the avatar catalogue", exception);
        }
    }
}
