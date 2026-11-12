// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.badlogic.gdx.graphics.Pixmap;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.gltextures.TextureCache;
import com.watabou.input.PointerEvent;
import com.watabou.input.ScrollEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.ScrollArea;
import com.watabou.utils.PointF;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class NativeInnBoard extends Group {
    private static final int GOLD = 0xefbb77;
    private static final int BLUE = 0x98d4eb;
    private static final int CREAM = 0xf9e5c8;
    private static final int TILE = 16;
    private static final int MAX_ZOOM_STEPS = 4;
    private static final long SPEECH_MILLIS = 4_500;
    private static final String SIGN = "inn/looking-sign.png";
    private static final String NPC_SPRITES = "inn/inn-npcs.png";
    private static final int[] FLOOR = {0x72503b, 0x79543e, 0x805840, 0x754b36};

    private final Consumer<InnMotion.Cell> goTo;
    private final Consumer<String> join;
    private final Consumer<InnApi.Npc> speak;
    private final Camera boardCamera;
    private final int viewportWidth;
    private final int viewportHeight;
    private final Group world = new Group();
    private final Group figures = new Group();
    private final Group speechBubbles = new Group();
    private final Map<String, Figure> guests = new HashMap<>();
    private final Map<String, Figure> npcs = new HashMap<>();

    private Image floor;
    private Pixmap floorKey;
    private List<String> map = List.of();
    private InnApi.State state;
    private Figure guide;
    private InnMotion.Cell lastPlayer;
    private long lastEvent;
    private int mapWidth;
    private int mapHeight;
    private int zoomStep = 1;
    private float fitZoom;
    private float wheelDelta;

    NativeInnBoard(float left, float top, float width, float height,
                   Consumer<InnMotion.Cell> goTo, Consumer<String> join) {
        this(left, top, width, height, goTo, join, npc -> {});
    }

    NativeInnBoard(float left, float top, float width, float height,
                   Consumer<InnMotion.Cell> goTo, Consumer<String> join, Consumer<InnApi.Npc> speak) {
        this.goTo = goTo;
        this.join = join;
        this.speak = speak;
        Camera main = Camera.main;
        float screenLeft = main.x + (left - main.scroll.x) * main.zoom;
        float screenTop = main.y + (top - main.scroll.y) * main.zoom;
        int x = (int) Math.ceil(screenLeft);
        int y = (int) Math.ceil(screenTop);
        viewportWidth = Math.max(1, (int) Math.floor(screenLeft + width * main.zoom) - x);
        viewportHeight = Math.max(1, (int) Math.floor(screenTop + height * main.zoom) - y);
        boardCamera = Camera.add(new Camera(x, y, viewportWidth, viewportHeight, 1));
        world.camera = boardCamera;
        add(world);
        world.add(figures);
        world.add(speechBubbles);
        add(new ScrollArea(left, top, width, height) {
            @Override
            protected void onScroll(ScrollEvent event) {
                if (state == null || event.amount == 0 || !boardCamera.hitTest(event.pos.x, event.pos.y)) return;
                wheelDelta += event.amount;
                int steps = (int) wheelDelta;
                wheelDelta -= steps;
                zoom(-steps);
            }

            @Override
            protected void onClick(PointerEvent event) {
                if (state == null || event.button != PointerEvent.LEFT) return;
                if (!boardCamera.hitTest(event.current.x, event.current.y)) return;
                PointF point = boardCamera.screenToCamera((int) event.current.x, (int) event.current.y);
                for (Map.Entry<String, Figure> entry : guests.entrySet()) {
                    if (!entry.getKey().equals(state.selfId()) && entry.getValue().hitsSign(point)) {
                        join.accept(entry.getKey());
                        return;
                    }
                }
                int column = (int) Math.floor(point.x / TILE);
                int row = (int) Math.floor(point.y / TILE);
                for (InnApi.Npc npc : state.npcs()) {
                    Figure figure = npcs.get(npc.id());
                    if ((npc.x() == column && npc.y() == row) ||
                            (figure != null && figure.sprite.overlapsPoint(point.x, point.y))) {
                        speak.accept(npc);
                        return;
                    }
                }
                if (state.walkable(column, row)) goTo.accept(new InnMotion.Cell(column, row));
            }
        });
    }

    void zoom(int steps) {
        int step = Math.clamp(zoomStep + steps, 0, MAX_ZOOM_STEPS);
        if (state == null || step == zoomStep) return;
        zoomStep = step;
        applyZoom();
        followPlayer();
    }

    void showState(InnApi.State next, List<InnApi.Event> history, boolean testMode, InnMotion.Cell predictedSelf) {
        showState(next, history, testMode, predictedSelf, null);
    }

    void showState(InnApi.State next, List<InnApi.Event> history, boolean testMode, InnMotion.Cell predictedSelf,
                   InnApi.Appearance ownAppearance) {
        if (next == null || state == null || !next.selfId().equals(state.selfId()) ||
                !next.map().equals(map)) {
            for (Figure guest : guests.values()) removeFigure(guest);
            guests.clear();
            for (Figure npc : npcs.values()) removeFigure(npc);
            npcs.clear();
            removeFigure(guide);
            guide = null;
            lastPlayer = null;
            lastEvent = 0;
        }
        boolean npcsChanged = state == null || next == null || !next.npcs().equals(state.npcs());
        state = next;
        if (state == null) {
            if (floor != null) floor.visible = false;
            return;
        }
        if (!state.map().equals(map)) rebuildFloor(state.map());
        floor.visible = true;
        long now = System.nanoTime();
        if (npcsChanged || npcs.size() != state.npcs().size()) {
            for (Figure npc : npcs.values()) removeFigure(npc);
            npcs.clear();
            for (InnApi.Npc npc : state.npcs()) {
                InnMotion.Cell cell = new InnMotion.Cell(npc.x(), npc.y());
                Figure figure = new Figure(new InnMotion(cell, npc.id().hashCode()), npc.name(),
                    false, false, npcColumn(npc.role()));
                figures.add(figure);
                npcs.put(npc.id(), figure);
            }
        }
        List<String> ids = state.people().stream().map(InnApi.Person::id).toList();
        guests.entrySet().removeIf(entry -> {
            if (ids.contains(entry.getKey())) return false;
            removeFigure(entry.getValue());
            return true;
        });
        for (InnApi.Person person : state.people()) {
            InnMotion.Cell cell = new InnMotion.Cell(person.x(), person.y());
            boolean own = person.id().equals(state.selfId());
            Figure guest = guests.computeIfAbsent(person.id(),
                id -> addFigure(cell, id.hashCode(), person.name(), own, false));
            guest.rename(person.name());
            guest.appearance(own && ownAppearance != null ? ownAppearance : person.appearance());
            guest.seeking(person.queued());
            if (own) guest.motion.moveLocalTo(predictedSelf == null ? cell : predictedSelf, state, now);
            else guest.motion.moveTo(cell, state, now);
        }
        InnApi.Person self = state.self();
        InnMotion.Cell player = new InnMotion.Cell(self.x(), self.y());
        if (!testMode) {
            removeFigure(guide);
            guide = null;
        } else if (guide == null) {
            guide = addFigure(neighbor(player, player), 2, "길잡이", false, true);
            guide.motion.face(player);
        } else if (!player.equals(lastPlayer) && guide.motion.destination().distanceTo(player) != 1) {
            InnMotion.Cell destination = lastPlayer != null && InnMotion.canStep(state, player, lastPlayer)
                ? lastPlayer : neighbor(player, guide.motion.destination());
            guide.motion.moveTo(destination, state, now);
        }
        lastPlayer = player;
        for (InnApi.Event event : history) {
            if (event.id() <= lastEvent) continue;
            lastEvent = event.id();
            Figure guest = guests.get(event.playerId());
            if (guest == null) continue;
            long age = Math.max(0, state.now() - event.time());
            if ("drink".equals(event.kind()) && age <= 2_000) {
                guest.motion.drink(now);
            } else if ("chat".equals(event.kind()) && age < SPEECH_MILLIS && event.text() != null && !event.text().isBlank()) {
                guest.speak(event.text(), now + (SPEECH_MILLIS - age) * 1_000_000L);
            }
        }
    }

    void moveSelfVisual(InnMotion.Cell target, long now) {
        if (state == null) return;
        Figure figure = guests.get(state.selfId());
        if (figure != null) figure.motion.moveLocalTo(target, state, now);
    }

    void correctSelfVisual(InnMotion.Cell target) {
        if (state == null) return;
        InnApi.Person self = state.self();
        Figure figure = guests.get(self.id());
        if (figure == null) return;
        InnMotion.Cell cell = target == null ? new InnMotion.Cell(self.x(), self.y()) : target;
        figure.motion.correctTo(cell, state, System.nanoTime());
    }

    @Override
    public void update() {
        if (state != null) {
            long now = System.nanoTime();
            for (Figure guest : guests.values()) guest.pose(guest.motion.pose(now));
            for (Figure npc : npcs.values()) npc.pose(npc.motion.pose(now));
            if (guide != null) guide.pose(guide.motion.pose(now));
            figures.sort(Comparator.nullsLast(Comparator.comparingDouble(g -> ((Figure) g).depth)));
            followPlayer();
            for (Figure guest : guests.values()) guest.placeSpeech(now);
        }
        super.update();
    }

    @Override
    public void destroy() {
        super.destroy();
        Camera.remove(boardCamera);
        if (floorKey != null) TextureCache.remove(floorKey);
    }

    private Figure addFigure(InnMotion.Cell cell, int phase, String name, boolean own, boolean companion) {
        Figure figure = new Figure(new InnMotion(cell, phase), name, own, companion, -1);
        figures.add(figure);
        return figure;
    }

    private void removeFigure(Figure figure) {
        if (figure == null) return;
        figures.erase(figure);
        figure.destroy();
    }

    private InnMotion.Cell neighbor(InnMotion.Cell player, InnMotion.Cell nearest) {
        InnMotion.Cell best = player;
        int bestDistance = Integer.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                InnMotion.Cell cell = new InnMotion.Cell(player.x() + dx, player.y() + dy);
                if (!InnMotion.canStep(state, player, cell)) continue;
                int distance = cell.distanceTo(nearest);
                if (distance < bestDistance) {
                    best = cell;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private void rebuildFloor(List<String> rows) {
        if (floor != null) {
            world.erase(floor);
            floor.destroy();
            TextureCache.remove(floorKey);
        }
        map = rows;
        int columns = rows.getFirst().length();
        mapWidth = columns * TILE;
        mapHeight = rows.size() * TILE;
        float fit = Math.min((float) viewportWidth / mapWidth, (float) viewportHeight / mapHeight);
        fitZoom = fit < 1f ? fit : (float) Math.floor(fit);
        applyZoom();
        floorKey = new Pixmap(mapWidth, mapHeight, Pixmap.Format.RGBA8888);
        for (int y = 0; y < rows.size(); y++) {
            for (int x = 0; x < columns; x++) paintTile(floorKey, x, y, rows.get(y).charAt(x));
        }
        floor = new Image(floorKey);
        world.addToBack(floor);
    }

    private void applyZoom() {
        float zoom = zoomStep == 0 ? fitZoom : (float) Math.floor(fitZoom) + zoomStep;
        // Keep the physical scissor rectangle fixed while the visible world area changes.
        boardCamera.zoom = zoom;
        boardCamera.width = (int) Math.ceil(viewportWidth / zoom);
        boardCamera.height = (int) Math.ceil(viewportHeight / zoom);
    }

    private void followPlayer() {
        Figure player = guests.get(state.selfId());
        if (player == null) return;
        float zoom = boardCamera.zoom;
        boardCamera.scroll.set(
            cameraScroll(player.sprite.x + player.sprite.width() / 2, mapWidth, viewportWidth, zoom),
            cameraScroll(player.depth + 2 - TILE / 2f, mapHeight, viewportHeight, zoom));
    }

    private static float cameraScroll(float target, int mapSize, int viewportSize, float zoom) {
        float visible = viewportSize / zoom;
        if (visible >= mapSize) return Math.round((mapSize - visible) * zoom / 2) / zoom;
        float aligned = Math.round((target - visible / 2) * zoom) / zoom;
        return Math.max(0, Math.min(mapSize - visible, aligned));
    }

    private static int npcColumn(String role) {
        return switch (role) {
            case "guard" -> 0;
            case "innkeeper" -> 1;
            case "vendor" -> 2;
            case "bard" -> 3;
            default -> 4;
        };
    }

    private static void paintTile(Pixmap map, int column, int row, char kind) {
        int x = column * TILE;
        int y = row * TILE;
        if (kind == '#') {
            fill(map, x, y, 16, 16, 0x35282a);
            for (int course = 0; course < 3; course++) {
                int brickStart = course % 2 == 0 ? x : x - 4;
                for (int brick = brickStart; brick < x + 16; brick += 8) {
                    int from = Math.max(brick + 1, x);
                    int to = Math.min(brick + 8, x + 16);
                    fill(map, from, y + 1 + course * 5, to - from, 4,
                        (column + row + course) % 2 == 0 ? 0x675047 : 0x59433d);
                    fill(map, from, y + 1 + course * 5, to - from, 1, 0x76594d);
                }
            }
            return;
        }
        fill(map, x, y, 16, 16, FLOOR[(column + row * 3) % FLOOR.length]);
        for (int plank = 0; plank < 4; plank++) {
            int seam = (3 + column * 7 + row * 5 + plank * 11) % 15;
            fill(map, x, y + 4 * plank, 16, 1, 0x4d342a);
            fill(map, x + seam, y + 4 * plank + 1, 1, 3, 0x5d4032);
        }
        if (kind == 'B' || kind == 'T') {
            int inset = kind == 'B' ? 0 : 1;
            fill(map, x + inset, y + 5, 16 - inset * 2, 10, 0x40281f);
            fill(map, x + inset, y + 3, 16 - inset * 2, 8, 0xae703d);
            fill(map, x + inset, y + 3, 16 - inset * 2, 1, 0xde9d58);
            fill(map, x + 6, y + 5, 3, 4, 0xe0a958);
            fill(map, x + 9, y + 6, 2, 2, 0xad713b);
            fill(map, x + 6, y + 5, 3, 1, 0xf6dfaa);
        } else if (kind == 'D') {
            fill(map, x, y + 3, 16, 13, 0x7a3534);
            fill(map, x, y + 3, 16, 1, 0xd69850);
            fill(map, x, y + 14, 16, 1, 0xd69850);
        }
    }

    private static void fill(Pixmap map, int x, int y, int w, int h, int rgb) {
        map.setColor(rgb << 8 | 0xff);
        map.fillRectangle(x, y, w, h);
    }

    private final class SpeechBubble extends Group {
        private static final int INK = 0x392824;
        private static final int PAPER = 0xfff8e9cc;
        private static final int EDGE = 0xff392824;

        private final ColorBlock shadow = new ColorBlock(1, 1, 0x991d1717);
        private final ColorBlock border = new ColorBlock(1, 1, EDGE);
        private final ColorBlock background = new ColorBlock(1, 1, PAPER);
        private final ColorBlock tail = new ColorBlock(5, 2, EDGE);
        private final ColorBlock tailCenter = new ColorBlock(3, 1, PAPER);
        private final ColorBlock tip = new ColorBlock(1, 1, EDGE);
        private final RenderedTextBlock label = PixelScene.renderTextBlock(6);
        private String message;
        private long expires;
        private int columns;

        SpeechBubble() {
            add(shadow);
            add(border);
            add(background);
            add(tail);
            add(tailCenter);
            add(tip);
            add(label);
            label.maxWidth(180);
            label.setHightlighting(false);
            label.hardlight(INK);
            visible = false;
        }

        void show(String message, long expires) {
            this.message = message;
            this.expires = expires;
            columns = 0;
        }

        void place(float center, float anchor, float spriteTop, float feet, long now) {
            float leftEdge = Math.max(1, boardCamera.scroll.x + 1);
            float rightEdge = Math.min(mapWidth - 1, boardCamera.scroll.x + viewportWidth / boardCamera.zoom - 1);
            float topEdge = Math.max(1, boardCamera.scroll.y + 1);
            float bottomEdge = Math.min(mapHeight - 1, boardCamera.scroll.y + viewportHeight / boardCamera.zoom - 1);
            visible = now < expires && center >= leftEdge && center <= rightEdge && feet >= topEdge && spriteTop < bottomEdge;
            if (!visible) return;

            int limit = Math.max(2, Math.min(18, (int) ((rightEdge - leftEdge - 8) / 7)));
            if (columns != limit) {
                columns = limit;
                label.text(previewText(message, columns));
            }
            float width = (float) Math.ceil(label.width()) + 8;
            float height = (float) Math.ceil(label.height()) + 6;
            float left = Math.round(Math.max(leftEdge, Math.min(rightEdge - width, center - width / 2)));
            float above = anchor - height - 5;
            boolean below = above < topEdge && feet + 5 + height <= bottomEdge;
            float top = Math.round(below ? feet + 5 : Math.max(topEdge, above));
            float pointer = Math.round(Math.max(left + 4, Math.min(left + width - 4, center)));

            shadow.size(width, height);
            border.size(width, height);
            background.size(width - 2, height - 2);
            shadow.x = background.x = left + 1;
            shadow.y = background.y = top + 1;
            border.x = left;
            border.y = top;
            tail.x = pointer - 2;
            tail.y = below ? top - 2 : top + height;
            tailCenter.x = pointer - 1;
            tailCenter.y = below ? top - 1 : top + height;
            tip.x = pointer;
            tip.y = below ? top - 3 : top + height + 2;
            label.setPos(left + 4, top + 2);
        }

        private static String previewText(String text, int lineLength) {
            int length = text.codePointCount(0, text.length());
            if (length > lineLength * 2) text = text.substring(0, text.offsetByCodePoints(0, lineLength * 2 - 1)) + "…";
            if (length <= lineLength) return text;
            int split = text.offsetByCodePoints(0, lineLength);
            return text.substring(0, split).stripTrailing() + "\n" + text.substring(split).stripLeading();
        }
    }

    private final class Figure extends Group {
        private final InnMotion motion;
        private final boolean companion;
        private final int npcColumn;
        private final Image sprite;
        private final String baseSprite;
        private final ColorBlock marker;
        private final ColorBlock nameplate = new ColorBlock(1, 1, 0xe1231817);
        private final RenderedTextBlock label = PixelScene.renderTextBlock(5);
        private final ColorBlock mug = new ColorBlock(3, 3, 0xffe8b75f);
        private Image sign;
        private RenderedTextBlock signText;
        private SpeechBubble speech;
        private String name;
        private InnApi.Appearance appearance;
        private AvatarSprites outfit;
        private int frame = -1;
        private float depth;

        Figure(InnMotion motion, String name, boolean own, boolean companion, int npcColumn) {
            this.motion = motion;
            this.companion = companion;
            this.npcColumn = npcColumn;
            baseSprite = npcColumn >= 0 ? NPC_SPRITES : own ? Assets.Sprites.WARRIOR : Assets.Sprites.ROGUE;
            sprite = new Image(baseSprite);
            marker = new ColorBlock(1, 1, own ? 0xffefbb77 : companion ? 0xff98d4eb : 0x99231613);
            add(marker);
            add(sprite);
            add(mug);
            add(nameplate);
            add(label);
            label.setHightlighting(false);
            label.hardlight(own ? GOLD : companion ? BLUE : CREAM);
            rename(name);
        }

        void appearance(InnApi.Appearance value) {
            if (value == null || value.equals(appearance)) return;
            AvatarSprites replacement = AvatarSprites.acquire(value, baseSprite);
            sprite.texture(replacement.texture());
            if (outfit != null) outfit.close();
            outfit = replacement;
            appearance = value;
            frame = -1;
        }

        @Override
        public void destroy() {
            if (speech != null) {
                speech.remove();
                speech.destroy();
            }
            super.destroy();
            if (outfit != null) outfit.close();
        }

        void speak(String text, long expires) {
            if (speech == null) {
                speech = new SpeechBubble();
                speechBubbles.add(speech);
            }
            speech.show(text, expires);
        }

        void placeSpeech(long now) {
            if (speech != null) {
                float anchor = sign != null && sign.visible ? sign.y : nameplate.y;
                speech.place(sprite.x + sprite.width() / 2, anchor, sprite.y, depth, now);
            }
        }

        void rename(String value) {
            if (value.equals(name)) return;
            name = value;
            int letters = name.codePointCount(0, name.length());
            label.text(letters <= 10 ? name : name.substring(0, name.offsetByCodePoints(0, 9)) + "…");
        }

        void seeking(boolean enabled) {
            if (enabled && sign == null) {
                sign = new Image(SIGN);
                signText = PixelScene.renderTextBlock("동료 구함", 6);
                signText.hardlight(0xffe1a4);
                add(sign);
                add(signText);
            }
            if (sign != null) {
                sign.visible = enabled;
                signText.visible = enabled;
            }
        }

        boolean hitsSign(PointF p) {
            return sign != null && sign.visible && p.x >= sign.x && p.y >= sign.y &&
                p.x < sign.x + sign.width() && p.y < sign.y + sign.height();
        }

        void pose(InnMotion.Pose pose) {
            float center = ((float) pose.x() + .5f) * TILE;
            depth = ((float) pose.y() + 1f) * TILE - 2f;
            if (frame != pose.frame() || sprite.flipHorizontal != pose.facingLeft()) {
                frame = pose.frame();
                sprite.flipHorizontal = pose.facingLeft();
                if (npcColumn >= 0) sprite.frame(npcColumn * TILE, frame * TILE, TILE, TILE);
                else sprite.frame(frame * AvatarSprites.FRAME_WIDTH, 0, AvatarSprites.FRAME_WIDTH, AvatarSprites.FRAME_HEIGHT);
            }
            sprite.x = center - sprite.width() / 2;
            sprite.y = depth - sprite.height();
            marker.size(10, 2);
            marker.x = center - marker.width() / 2;
            marker.y = depth;
            float labelX = Math.max(2, Math.min(mapWidth - label.width() - 2, center - label.width() / 2));
            float labelY = Math.max(1, sprite.y - label.height() - (companion ? 8 : 2));
            label.setPos(labelX, labelY);
            nameplate.size(label.width() + 2, label.height() + 2);
            nameplate.x = labelX - 1;
            nameplate.y = labelY - 1;
            mug.visible = pose.drinkSeconds() >= 0;
            mug.x = center + (pose.facingLeft() ? -6 : 4);
            mug.y = sprite.y + (float) Math.min(pose.drinkSeconds() * 8, 6);
            if (sign != null && sign.visible) {
                sign.scale.set(.72f);
                sign.x = Math.max(1, Math.min(mapWidth - sign.width() - 1, center - sign.width() / 2));
                sign.y = Math.max(0, labelY - sign.height() + 2);
                if (sign.y + sign.height() >= labelY + 2) {
                    label.visible = false;
                    nameplate.visible = false;
                } else {
                    label.visible = true;
                    nameplate.visible = true;
                }
                signText.setPos(sign.x + (sign.width() - signText.width()) / 2, sign.y + 3);
            } else {
                label.visible = true;
                nameplate.visible = true;
            }
        }
    }
}
