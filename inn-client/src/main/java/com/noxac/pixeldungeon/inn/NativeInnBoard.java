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

import java.util.ArrayList;
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
    private static final int[] FLOOR = {0x61432f, 0x65452f, 0x6b4832, 0x5b3e2e};
    private static final int[][] CANDLES = {{3, 3}, {7, 5}, {11, 9}, {15, 11}, {19, 9}};
    private static final int[][] PATRONS = {{4, 3}, {8, 5}, {20, 9}, {14, 11}};
    private static final int HEARTH_WIDTH = 24, HEARTH_HEIGHT = 12;
    private static final int CANDLE_WIDTH = 7, CANDLE_HEIGHT = 9;
    private static final int NOTE_WIDTH = 12, NOTE_HEIGHT = 12;
    private static final int PATRON_WIDTH = 14, PATRON_HEIGHT = 13;
    private static final int CANDLE_ROW = HEARTH_HEIGHT;
    private static final int NOTE_ROW = CANDLE_ROW + CANDLE_HEIGHT;
    private static final int PATRON_ROW = NOTE_ROW + NOTE_HEIGHT;

    private final Consumer<InnMotion.Cell> goTo;
    private final Consumer<String> join;
    private final Consumer<InnApi.Npc> speak;
    private final Camera boardCamera;
    private final int viewportWidth;
    private final int viewportHeight;
    private final Group world = new Group();
    private final Group decorations = new Group();
    private final Group figures = new Group();
    private final Group speechBubbles = new Group();
    private final List<AmbientSprite> animatedDecorations = new ArrayList<>();
    private final Map<String, Figure> guests = new HashMap<>();
    private final Map<String, Figure> npcs = new HashMap<>();

    private Image floor;
    private Pixmap floorKey;
    private Pixmap decorationKey;
    private Image bardNotes;
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
        world.add(decorations);
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
        decorations.visible = state != null;
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
            if (bardNotes != null) bardNotes.visible = false;
            for (InnApi.Npc npc : state.npcs()) {
                InnMotion.Cell cell = new InnMotion.Cell(npc.x(), npc.y());
                Figure figure = new Figure(new InnMotion(cell, npc.id().hashCode()), npc.name(),
                    false, false, npcColumn(npc.role()));
                figures.add(figure);
                npcs.put(npc.id(), figure);
                if (bardNotes != null && "bard".equals(npc.role())) {
                    bardNotes.x = npc.x() * TILE + 15;
                    bardNotes.y = npc.y() * TILE - 9;
                    bardNotes.visible = true;
                }
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
            long milliseconds = now / 1_000_000L;
            for (AmbientSprite decoration : animatedDecorations) decoration.animate(milliseconds);
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
        if (decorationKey != null) TextureCache.remove(decorationKey);
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
            for (int x = 0; x < columns; x++) paintTerrain(floorKey, x, y, rows.get(y).charAt(x));
        }
        if (tileAt(rows, 3, 3) == 'T' && tileAt(rows, 8, 5) == 'T') {
            paintRug(floorKey, 3 * TILE - 2, 3 * TILE - 2, 6 * TILE + 4, 3 * TILE + 4);
        }
        if (tileAt(rows, 10, 9) == 'T' && tileAt(rows, 15, 11) == 'T') {
            paintRug(floorKey, 10 * TILE - 2, 9 * TILE - 2, 7 * TILE + 4, 3 * TILE + 4);
        }
        if (tileAt(rows, 11, 15) == 'D' && tileAt(rows, 14, 15) == 'D') {
            paintRug(floorKey, 11 * TILE, 13 * TILE + 2, 4 * TILE, 2 * TILE - 2);
        }
        for (int y = 0; y < rows.size(); y++) {
            for (int x = 0; x < columns; x++) paintFurniture(floorKey, x, y, rows.get(y).charAt(x));
        }
        rebuildDecorations(rows);
        floor = new Image(floorKey);
        world.addToBack(floor);
    }

    private static char tileAt(List<String> rows, int x, int y) {
        if (y >= rows.size() || x >= rows.get(y).length()) return '\0';
        return rows.get(y).charAt(x);
    }

    private void rebuildDecorations(List<String> rows) {
        for (AmbientSprite sprite : animatedDecorations) {
            decorations.erase(sprite);
            sprite.destroy();
        }
        animatedDecorations.clear();
        if (decorationKey == null) decorationKey = makeDecorationAtlas();

        if (tileAt(rows, 18, 0) == '#' && tileAt(rows, 20, 0) == '#' && tileAt(rows, 19, 1) == '.') {
            int x = 18 * TILE + 3;
            paintHearth(floorKey, x);
            addDecoration(x + 9, 3, 0, HEARTH_WIDTH, HEARTH_HEIGHT, 135, 0);
        }
        for (int[] cell : CANDLES) {
            int x = cell[0], y = cell[1];
            if (tileAt(rows, x, y) == 'T') {
                addDecoration(x * TILE + 9, y * TILE - 1, CANDLE_ROW, CANDLE_WIDTH, CANDLE_HEIGHT, 175, x * 67);
            }
        }
        if (tileAt(rows, 16, 16) == '#' && tileAt(rows, 14, 15) == 'D') {
            int x = 16 * TILE + 4, y = 16 * TILE + 2;
            fill(floorKey, x + 2, y + 3, 5, 8, 0x33211b);
            fill(floorKey, x + 4, y + 5, 1, 6, 0x875a3d);
            addDecoration(x, y, CANDLE_ROW, CANDLE_WIDTH, CANDLE_HEIGHT, 175, 99);
        }
        for (int i = 0; i < PATRONS.length; i++) {
            int x = PATRONS[i][0], y = PATRONS[i][1];
            if (tileAt(rows, x, y) == 'T') {
                addDecoration(x * TILE + 1, y * TILE + 2, PATRON_ROW, PATRON_WIDTH, PATRON_HEIGHT, 0, i);
            }
        }
        bardNotes = addDecoration(0, 0, NOTE_ROW, NOTE_WIDTH, NOTE_HEIGHT, 370, 0);
        bardNotes.visible = false;
    }

    private Image addDecoration(int x, int y, int row, int width, int height, int period, int phase) {
        AmbientSprite sprite = new AmbientSprite(x, y, row, width, height, period, phase);
        animatedDecorations.add(sprite);
        decorations.add(sprite);
        return sprite;
    }

    private final class AmbientSprite extends Image {
        private final int row, width, height, period, phase;
        private int lastFrame = -1;

        AmbientSprite(int x, int y, int row, int width, int height, int period, int phase) {
            super(NativeInnBoard.this.decorationKey);
            this.x = x;
            this.y = y;
            this.row = row;
            this.width = width;
            this.height = height;
            this.period = period;
            this.phase = phase;
            animate(0);
        }

        void animate(long milliseconds) {
            int frame = period == 0 ? phase : Math.floorMod((milliseconds + phase) / period, 4);
            if (frame == lastFrame) return;
            frame(frame * width, row, width, height);
            lastFrame = frame;
        }
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

    private static void paintTerrain(Pixmap map, int column, int row, char kind) {
        int x = column * TILE;
        int y = row * TILE;
        if (kind == '#') {
            fill(map, x, y, 16, 16, 0x2d2221);
            for (int course = 0; course < 3; course++) {
                int brickStart = course % 2 == 0 ? x : x - 4;
                for (int brick = brickStart; brick < x + 16; brick += 8) {
                    int from = Math.max(brick + 1, x);
                    int to = Math.min(brick + 8, x + 16);
                    fill(map, from, y + 1 + course * 5, to - from, 4,
                        (column + row + course) % 2 == 0 ? 0x59473d : 0x4d3b33);
                    fill(map, from, y + 1 + course * 5, to - from, 1, 0x6a5343);
                }
            }
            fill(map, x, y + 14, TILE, 1, 0x805c3f);
            fill(map, x, y + 15, TILE, 1, 0x38271f);
            return;
        }
        fill(map, x, y, 16, 16, FLOOR[(column + row * 3) % FLOOR.length]);
        for (int plank = 0; plank < 4; plank++) {
            int seam = (3 + column * 7 + row * 5 + plank * 11) % 15;
            fill(map, x, y + 4 * plank, 16, 1, 0x452f24);
            fill(map, x + seam, y + 4 * plank + 1, 1, 3, 0x503526);
            fill(map, x + (seam + 7) % 13, y + 4 * plank + 2, 3, 1, 0x75533b);
        }
    }

    private static void paintFurniture(Pixmap map, int column, int row, char kind) {
        int x = column * TILE, y = row * TILE;
        if (kind == 'B' || kind == 'T') {
            int inset = kind == 'B' ? 0 : 1;
            fill(map, x + inset, y + 5, 16 - inset * 2, 10, 0x37241e);
            fill(map, x + inset, y + 3, 16 - inset * 2, 8, 0x97623a);
            fill(map, x + inset, y + 3, 16 - inset * 2, 1, 0xc78d54);
            fill(map, x + inset, y + 9, 16 - inset * 2, 2, 0x74462e);
            if ((column + row) % 2 == 0) {
                fill(map, x + 4, y + 6, 5, 3, 0xd7b98a);
                fill(map, x + 5, y + 6, 3, 2, 0xa46a3d);
            } else {
                fill(map, x + 5, y + 5, 3, 4, 0xe0a958);
                fill(map, x + 8, y + 6, 2, 2, 0xad713b);
                fill(map, x + 5, y + 5, 3, 1, 0xf6dfaa);
            }
        } else if (kind == 'D') {
            fill(map, x, y + 3, 16, 13, 0x7a3534);
            fill(map, x, y + 3, 16, 1, 0xd69850);
            fill(map, x, y + 14, 16, 1, 0xd69850);
        }
    }

    private static void paintRug(Pixmap map, int x, int y, int width, int height) {
        fill(map, x, y, width, height, 0x482724);
        fill(map, x + 1, y + 1, width - 2, height - 2, 0x9e6847);
        fill(map, x + 2, y + 2, width - 4, height - 4, 0x542e2e);
        fill(map, x + 4, y + 4, width - 8, height - 8, 0x703b37);
        for (int i = 6; i < width - 5; i += 4) {
            fill(map, x + i, y + 2, 2, 1, 0xc28d59);
            fill(map, x + i, y + height - 3, 2, 1, 0xc28d59);
        }
        for (int j = 9; j < height - 5; j += 8) {
            for (int i = 9; i < width - 5; i += 8) {
                fill(map, x + i, y + j - 1, 1, 3, 0x864841);
                fill(map, x + i - 1, y + j, 3, 1, 0x864841);
            }
        }
    }

    private static void paintHearth(Pixmap map, int x) {
        fill(map, x, 1, 42, 15, 0x322826);
        fill(map, x + 2, 2, 38, 12, 0x695750);
        fill(map, x + 9, 5, 24, 9, 0x2b1a19);
        fill(map, x + 11, 3, 20, 2, 0x2b1a19);
        fill(map, x + 3, 3, 6, 3, 0x8d7561);
        fill(map, x + 33, 3, 6, 3, 0x8d7561);
        fill(map, x + 3, 8, 6, 2, 0x836b59);
        fill(map, x + 33, 8, 6, 2, 0x836b59);
        fill(map, x + 2, 0, 38, 2, 0x977c66);
        fill(map, x + 1, 14, 40, 2, 0x806859);
        fill(map, x + 5, 16, 32, 1, 0x825337);
        fill(map, x + 12, 17, 18, 1, 0x754a32);
    }

    private static Pixmap makeDecorationAtlas() {
        Pixmap atlas = new Pixmap(HEARTH_WIDTH * 4, PATRON_ROW + PATRON_HEIGHT, Pixmap.Format.RGBA8888);
        int[] flameHeights = {5, 7, 6, 5};
        for (int frame = 0; frame < 4; frame++) {
            int x = frame * HEARTH_WIDTH;
            fill(atlas, x + 3, 10, 18, 1, 0x8c3f26);
            fill(atlas, x + 5, 9, 14, 1, 0x663927);
            for (int flame = 0; flame < 3; flame++) {
                int left = x + 5 + flame * 5;
                int height = flameHeights[(frame + flame) % 4];
                fill(atlas, left, 9 - height, 4, height, 0xbd4c2b);
                fill(atlas, left + 1, 9 - height, 2, height, 0xe67c37);
                fill(atlas, left + 1, 6, 2, 3, 0xffcc69);
            }
            fill(atlas, x + 8 + frame * 2, 3 - frame / 2, 1, 1, 0xffb35d);
            fill(atlas, x + 17 - frame, 5 - frame, 1, 1, 0xe07536);

            x = frame * CANDLE_WIDTH;
            fill(atlas, x + 1, CANDLE_ROW + 7, 5, 2, 0x765038);
            fill(atlas, x + 2, CANDLE_ROW + 4, 3, 4, 0xc9a977);
            fill(atlas, x + 2, CANDLE_ROW + 4, 1, 3, 0xf7dda1);
            fill(atlas, x + 3, CANDLE_ROW + 2 - frame % 2, 2, 2 + frame % 2, 0xee8f44);
            fill(atlas, x + 3, CANDLE_ROW + 3, 1, 1, 0xffe292);

            x = frame * NOTE_WIDTH;
            int rise = frame * 2;
            if (frame != 3) {
                fill(atlas, x + 2, NOTE_ROW + 10 - rise, 3, 2, 0xe6b96d);
                fill(atlas, x + 4, NOTE_ROW + 6 - rise, 1, 5, 0xe6b96d);
                fill(atlas, x + 5, NOTE_ROW + 6 - rise, 2, 1, 0xe6b96d);
            }
            if (frame != 0) {
                fill(atlas, x + 7, NOTE_ROW + 12 - rise, 3, 2, 0xa78663);
                fill(atlas, x + 9, NOTE_ROW + 9 - rise, 1, 4, 0xa78663);
            }
        }
        int[] coats = {0x456568, 0x705168, 0x586749, 0x7c543c};
        for (int patron = 0; patron < coats.length; patron++) {
            int x = patron * PATRON_WIDTH, y = PATRON_ROW;
            fill(atlas, x + 1, y + 9, 12, 4, 0x39271f);
            fill(atlas, x + 3, y + 5, 8, 6, coats[patron]);
            fill(atlas, x + 4, y + 1, 6, 5, 0x352822);
            fill(atlas, x + 5, y + 3, 4, 4, 0xc38e65);
            fill(atlas, x + 7, y + 4, 1, 1, 0x483328);
            fill(atlas, x + 2, y + 8, 10, 1, 0x6e4934);
            fill(atlas, x + 10, y + 5, 3, 4, 0xcfa069);
            fill(atlas, x + 10, y + 5, 3, 1, 0xf0d39b);
        }
        return atlas;
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
        private final RenderedTextBlock label = PixelScene.renderTextBlock(6, false);
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
        private final boolean player;
        private final int npcColumn;
        private final Image sprite;
        private final String baseSprite;
        private final ColorBlock marker;
        private final ColorBlock nameplateEdge;
        private final ColorBlock nameplate;
        private final RenderedTextBlock label;
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
            player = npcColumn < 0 && !companion;
            this.npcColumn = npcColumn;
            baseSprite = npcColumn >= 0 ? NPC_SPRITES : own ? Assets.Sprites.WARRIOR : Assets.Sprites.ROGUE;
            sprite = new Image(baseSprite);
            marker = new ColorBlock(1, 1, own ? 0xffefbb77 : companion ? 0xff98d4eb : 0x99231613);
            nameplateEdge = new ColorBlock(1, 1,
                own ? 0xefba8b57 : companion ? 0xd65c8394 : player ? 0xea8e7259 : 0xd684684d);
            nameplate = new ColorBlock(1, 1, 0xf0241c1a);
            label = PixelScene.renderTextBlock(8, false);
            add(marker);
            add(sprite);
            add(mug);
            add(nameplateEdge);
            add(nameplate);
            add(label);
            label.setHightlighting(false);
            label.hardlight(own ? 0xffe6ad : companion ? BLUE : CREAM);
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
                float anchor = nameplateEdge.y;
                if (sign != null && sign.visible) anchor = Math.min(anchor, sign.y);
                speech.place(sprite.x + sprite.width() / 2, anchor, sprite.y, depth, now);
            }
        }

        void rename(String value) {
            if (value.equals(name)) return;
            name = value;
            int letters = name.codePointCount(0, name.length());
            int limit = player ? 12 : 10;
            label.text(letters <= limit ? name : name.substring(0, name.offsetByCodePoints(0, limit - 1)) + "…");
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
            int side = 3;
            int pad = 2;
            float labelX = pixel(Math.clamp(center - label.width() / 2,
                side + 1, Math.max(side + 1, mapWidth - label.width() - side - 1)));
            float labelY = pixel(Math.max(pad + 1, sprite.y - label.height() - (companion ? 8 : 4)));
            label.setPos(labelX, labelY);
            nameplate.size(label.width() + side * 2, label.height() + pad * 2);
            nameplate.x = labelX - side;
            nameplate.y = labelY - pad;
            nameplateEdge.size(nameplate.width() + 2, nameplate.height() + 2);
            nameplateEdge.x = nameplate.x - 1;
            nameplateEdge.y = nameplate.y - 1;
            mug.visible = pose.drinkSeconds() >= 0;
            mug.x = center + (pose.facingLeft() ? -6 : 4);
            mug.y = sprite.y + (float) Math.min(pose.drinkSeconds() * 8, 6);
            if (sign != null && sign.visible) {
                sign.scale.set(.72f);
                sign.x = pixel(Math.max(1, Math.min(mapWidth - sign.width() - 1, center - sign.width() / 2)));
                float above = nameplate.y - sign.height() - 2;
                sign.y = pixel(above >= 0 ? above : Math.min(mapHeight - sign.height(), depth + 3));
                signText.setPos(pixel(sign.x + (sign.width() - signText.width()) / 2), pixel(sign.y + 3));
            }
        }

        private float pixel(float value) {
            return Math.round(value * boardCamera.zoom) / boardCamera.zoom;
        }
    }
}
