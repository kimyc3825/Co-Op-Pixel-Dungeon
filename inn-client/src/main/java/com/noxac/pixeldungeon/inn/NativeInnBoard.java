// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.badlogic.gdx.graphics.Pixmap;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.gltextures.TextureCache;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.PointerArea;
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
    private static final String SIGN = "inn/looking-sign.png";
    private static final int[] FLOOR = {0x72503b, 0x79543e, 0x805840, 0x754b36};

    private final float left;
    private final float top;
    private final float width;
    private final float height;
    private final Consumer<InnMotion.Cell> goTo;
    private final Consumer<String> join;
    private final Group figures = new Group();
    private final Map<String, Figure> guests = new HashMap<>();

    private Image floor;
    private Pixmap floorKey;
    private List<String> map = List.of();
    private InnApi.State state;
    private Figure guide;
    private InnMotion.Cell lastPlayer;
    private long lastEvent;
    private float tile;
    private float boardX;
    private float boardY;

    NativeInnBoard(float left, float top, float width, float height,
                   Consumer<InnMotion.Cell> goTo, Consumer<String> join) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.goTo = goTo;
        this.join = join;
        add(figures);
        add(new PointerArea(left, top, width, height) {
            @Override
            protected void onClick(PointerEvent event) {
                if (state == null || event.button != PointerEvent.LEFT) return;
                PointF point = camera().screenToCamera((int) event.current.x, (int) event.current.y);
                for (Map.Entry<String, Figure> entry : guests.entrySet()) {
                    if (!entry.getKey().equals(state.selfId()) && entry.getValue().hitsSign(point)) {
                        join.accept(entry.getKey());
                        return;
                    }
                }
                int x = (int) Math.floor((point.x - boardX) / tile);
                int y = (int) Math.floor((point.y - boardY) / tile);
                if (state.walkable(x, y)) goTo.accept(new InnMotion.Cell(x, y));
            }
        });
    }

    void showState(InnApi.State next, List<InnApi.Event> history, boolean testMode, InnMotion.Cell predictedSelf) {
        if (next == null || state == null || !next.selfId().equals(state.selfId()) ||
                !next.map().equals(map)) {
            for (Figure guest : guests.values()) removeFigure(guest);
            guests.clear();
            removeFigure(guide);
            guide = null;
            lastPlayer = null;
            lastEvent = 0;
        }
        state = next;
        if (state == null) {
            if (floor != null) floor.visible = false;
            return;
        }
        if (!state.map().equals(map)) rebuildFloor(state.map());
        floor.visible = true;
        long now = System.nanoTime();
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
            guest.seeking(person.queued());
            guest.motion.moveTo(own && predictedSelf != null ? predictedSelf : cell, state, now);
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
            if (guest != null && "drink".equals(event.kind()) && state.now() - event.time() <= 2_000) {
                guest.motion.drink(now);
            }
        }
    }

    void moveSelfVisual(InnMotion.Cell target) {
        if (state == null) return;
        InnApi.Person self = state.self();
        Figure figure = guests.get(self.id());
        if (figure == null) return;
        InnMotion.Cell cell = target == null ? new InnMotion.Cell(self.x(), self.y()) : target;
        figure.motion.moveTo(cell, state, System.nanoTime());
    }

    @Override
    public void update() {
        if (state != null) {
            long now = System.nanoTime();
            for (Figure guest : guests.values()) guest.pose(guest.motion.pose(now));
            if (guide != null) guide.pose(guide.motion.pose(now));
            figures.sort(Comparator.nullsLast(Comparator.comparingDouble(g -> ((Figure) g).depth)));
        }
        super.update();
    }

    @Override
    public void destroy() {
        super.destroy();
        if (floorKey != null) TextureCache.remove(floorKey);
    }

    private Figure addFigure(InnMotion.Cell cell, int phase, String name, boolean own, boolean companion) {
        Figure figure = new Figure(new InnMotion(cell, phase), name, own, companion);
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
            erase(floor);
            floor.destroy();
            TextureCache.remove(floorKey);
        }
        map = rows;
        int columns = rows.getFirst().length();
        tile = Math.max(1, (float) Math.floor(Math.min((width - 4) / columns, (height - 8) / rows.size())));
        boardX = PixelScene.align(left + (width - columns * tile) / 2);
        boardY = PixelScene.align(top + (height - rows.size() * tile) / 2);
        floorKey = new Pixmap(columns * TILE, rows.size() * TILE, Pixmap.Format.RGBA8888);
        for (int y = 0; y < rows.size(); y++) {
            for (int x = 0; x < columns; x++) paintTile(floorKey, x, y, rows.get(y).charAt(x));
        }
        floor = new Image(floorKey);
        floor.x = boardX;
        floor.y = boardY;
        floor.scale.set(tile / TILE);
        addToBack(floor);
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

    private final class Figure extends Group {
        private final InnMotion motion;
        private final boolean own;
        private final boolean companion;
        private final Image sprite;
        private final ColorBlock marker;
        private final ColorBlock nameplate = new ColorBlock(1, 1, 0xe1231817);
        private final RenderedTextBlock label = PixelScene.renderTextBlock(5);
        private final ColorBlock mug = new ColorBlock(3, 3, 0xffe8b75f);
        private Image sign;
        private RenderedTextBlock signText;
        private String name;
        private int frame = -1;
        private float depth;

        Figure(InnMotion motion, String name, boolean own, boolean companion) {
            this.motion = motion;
            this.own = own;
            this.companion = companion;
            sprite = new Image(own ? Assets.Sprites.WARRIOR : Assets.Sprites.ROGUE);
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
            float scale = tile / TILE;
            float center = boardX + ((float) pose.x() + .5f) * tile;
            depth = boardY + ((float) pose.y() + 1f) * tile - 2f * scale;
            if (frame != pose.frame() || sprite.flipHorizontal != pose.facingLeft()) {
                frame = pose.frame();
                sprite.flipHorizontal = pose.facingLeft();
                sprite.frame(frame * 12, 0, 12, 15);
            }
            sprite.scale.set(scale);
            sprite.x = center - sprite.width() / 2;
            sprite.y = depth - sprite.height();
            marker.size(10 * scale, Math.max(1f, scale * 2));
            marker.x = center - marker.width() / 2;
            marker.y = depth;
            float labelX = Math.max(left + 2, Math.min(left + width - label.width() - 2, center - label.width() / 2));
            float labelY = Math.max(top + 1, sprite.y - label.height() - (companion ? 8 : 2));
            label.setPos(labelX, labelY);
            nameplate.size(label.width() + 2, label.height() + 2);
            nameplate.x = labelX - 1;
            nameplate.y = labelY - 1;
            mug.visible = pose.drinkSeconds() >= 0;
            mug.x = center + (pose.facingLeft() ? -6 : 4) * scale;
            mug.y = sprite.y + (float) Math.min(pose.drinkSeconds() * 8, 6) * scale;
            if (sign != null && sign.visible) {
                sign.scale.set(.72f);
                sign.x = Math.max(left + 1, Math.min(left + width - sign.width() - 1, center - sign.width() / 2));
                sign.y = Math.max(top, labelY - sign.height() + 2);
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
