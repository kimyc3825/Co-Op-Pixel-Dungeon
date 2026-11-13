// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher;
import javax.swing.JOptionPane;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class InnLauncher {
    private InnLauncher() {}

    public static void main(String[] args) {
        InnApi api;
        try {
            api = new InnApi(origin());
        } catch (IOException | IllegalArgumentException error) {
            JOptionPane.showMessageDialog(null, "여관 서버 주소를 확인하세요.",
                "Co-Op-Pixel-Dungeon", JOptionPane.ERROR_MESSAGE);
            return;
        }
        DesktopLauncher.launchInn(platform -> new NativeGame(platform, api));
    }

    private static URI origin() throws IOException {
        String override = System.getProperty("noxac.inn.url", System.getenv("NOXAC_INN_URL"));
        if (override != null && !override.isBlank()) return URI.create(override.strip());
        try (InputStream resource = InnLauncher.class.getResourceAsStream("/inn-origin.txt")) {
            if (resource == null) throw new IOException("Missing inn-origin.txt");
            return URI.create(new String(resource.readAllBytes(), StandardCharsets.UTF_8).strip());
        }
    }
}
