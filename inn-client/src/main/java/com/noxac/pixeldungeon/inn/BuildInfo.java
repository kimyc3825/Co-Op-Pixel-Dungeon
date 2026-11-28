// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.noosa.Scene;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The corner label drawn over every screen, inn and dungeon alike: this client's release (the
 * repository's VERSION file), the deploy the server reports and the latest round trip to the server.
 */
final class BuildInfo {
    /** Three ping intervals without an answer: no socket is measuring. */
    private static final long STALE_NANOS = TimeUnit.SECONDS.toNanos(16);
    private static final String CLIENT = readClientVersion();
    private record Measurement(long nanos, long at) {}
    /** Both values in one object so the render thread cannot pair a new latency with an old time. */
    private static volatile Measurement measurement;
    private static volatile String server;

    private RenderedTextBlock label;
    private Scene owner;
    private String shown;

    /** Called by {@link InnChannel} with the time from a `ping` to its `pong`, on any socket. */
    static void reportLatency(long nanos) {
        measurement = new Measurement(nanos, System.nanoTime());
    }

    /** Asks the server for its deploy once, off the render thread. */
    static void fetchServer(InnApi api) {
        Thread thread = new Thread(() -> server = api.deployedVersion(), "build-info");
        thread.setDaemon(true);
        thread.start();
    }

    static String text() {
        StringBuilder text = new StringBuilder("v").append(CLIENT);
        String deployed = server;
        if (deployed != null) text.append(" · 서버 ").append(deployed);
        Measurement current = measurement;
        boolean fresh = current != null && System.nanoTime() - current.at() < STALE_NANOS;
        text.append(" · 핑 ").append(fresh ? TimeUnit.NANOSECONDS.toMillis(current.nanos()) + "ms" : "—");
        return text.toString();
    }

    /** Render thread, after the scene: draws the label at the bottom left of the scene's UI camera. */
    void draw(Scene scene) {
        if (!(scene instanceof PixelScene) || PixelScene.uiCamera == null) return;
        String text = text();
        // A new scene has a new UI camera; build the label again for it.
        if (scene != owner || label == null) {
            if (label != null) label.destroy();
            owner = scene;
            label = new RenderedTextBlock(text, 6);
            label.camera = PixelScene.uiCamera;
            label.alpha(0.7f);
            shown = text;
        } else if (!text.equals(shown)) {
            label.text(text);
            label.alpha(0.7f);
            shown = text;
        }
        label.setPos(2, PixelScene.uiCamera.height - label.height() - 1);
        label.draw();
    }

    private static String readClientVersion() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/inn-version.txt")) {
            if (in == null) return "?";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException error) {
            return "?";
        }
    }
}
