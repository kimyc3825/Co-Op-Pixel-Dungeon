// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.scenes;

import com.badlogic.gdx.Input;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.InnAdventure;
import com.shatteredpixel.shatteredpixeldungeon.InnAtb;
import com.shatteredpixel.shatteredpixeldungeon.InnOriginalParty;
import com.shatteredpixel.shatteredpixeldungeon.SPDAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.InnPartySprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTerrainTilemap;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTileSheet;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonWallsTilemap;
import com.shatteredpixel.shatteredpixeldungeon.tiles.FogOfWar;
import com.shatteredpixel.shatteredpixeldungeon.tiles.RaisedTerrainTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.watabou.input.GameAction;
import com.watabou.input.KeyBindings;
import com.watabou.input.KeyEvent;
import com.watabou.input.PointerEvent;
import com.watabou.input.ScrollEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.PointerArea;
import com.watabou.utils.Reflection;
import com.watabou.utils.Signal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Guest view of the host's original dungeon, using the original tiles, camera and sprites. */
public final class InnPartyScene extends PixelScene {
    private final Map<Integer, ActorView> actors = new HashMap<>();
    private final Map<Integer, ItemSprite> drops = new HashMap<>();
    private final Set<GameAction> movement = new HashSet<>();
    private final Set<GameAction> tappedMovement = new HashSet<>();
    private final Signal.Listener<KeyEvent> keys = this::key;
    private final Signal.Listener<ScrollEvent> scrolling = event -> {
        Camera.main.zoom(Math.max(minZoom, Math.min(maxZoom, Camera.main.zoom - Math.signum(event.amount))));
        return true;
    };
    private InnOriginalParty party;
    private Group world, figures, loot, fatigue;
    private DungeonTerrainTilemap ground;
    private RaisedTerrainTilemap raised;
    private DungeonWallsTilemap walls;
    private FogOfWar fog;
    private RenderedTextBlock status;
    private RedButton attackButton;
    private RedButton timeButton;
    private Integer attackTarget;
    private boolean spaceHeld;
    private int revision;
    private int floor;
    private int player;
    private float repeatIn;

    @Override
    public void create() {
        super.create();
        party = InnOriginalParty.current();
        status = renderTextBlock("파티장이 원본 던전에 입장하는 중", 8);
        status.camera = uiCamera;
        status.setPos(5, 5);
        add(status);
        attackButton = new RedButton("공격 (Q)") {
            @Override
            protected void onClick() {
                attackSelected();
            }
        };
        attackButton.camera = uiCamera;
        attackButton.setRect(uiCamera.width - 54, uiCamera.height - 22, 48, 16);
        attackButton.visible = attackButton.active = false;
        add(attackButton);
        timeButton = new RedButton("1× (Space)", 7) {
            @Override
            protected void onClick() {
                party.toggleTime();
            }
        };
        timeButton.camera = uiCamera;
        timeButton.setRect(uiCamera.width - 110, uiCamera.height - 22, 52, 16);
        timeButton.visible = timeButton.active = false;
        add(timeButton);
        KeyEvent.addKeyListener(keys);
        ScrollEvent.addScrollListener(scrolling);
    }

    @Override
    public void update() {
        if (party == null || party.isClosed()) {
            Game.instance.finish();
            return;
        }
        InnOriginalParty.Snapshot state = party.latest();
        if (state != null && state.revision != revision) {
            if (state.result.equals("end")) {
                Game.instance.finish();
                return;
            }
            apply(state);
        }
        super.update();
        party.updateGuest(Game.elapsed);
        repeatIn -= Game.elapsed;
        if (repeatIn <= 0 && !party.awaitingAction()) moveHeld();
    }

    private void apply(InnOriginalParty.Snapshot state) {
        revision = state.revision;
        InnAtb.setSlowTime(state.slowTime);
        timeButton.text(state.slowTime ? "0.5× (Space)" : "1× (Space)");
        timeButton.visible = timeButton.active = true;
        player = state.player;
        if (state.floor != floor) {
            floor = state.floor;
            rebuild(state);
        }
        Level level = Dungeon.level;
        boolean terrainChanged = false;
        boolean fogChanged = false;
        for (int cell = 0; cell < state.tiles.length; cell++) {
            int value = state.tiles[cell];
            int terrain = value == 0 ? Terrain.WALL : (value - 1) % 64;
            boolean visible = value != 0 && value <= 64;
            boolean visited = value != 0;
            if (terrain != level.map[cell]) {
                level.map[cell] = terrain;
                terrainChanged = true;
            }
            if (visible != level.heroFOV[cell] || visited != level.visited[cell]) {
                level.heroFOV[cell] = visible;
                level.visited[cell] = visited;
                fogChanged = true;
            }
        }
        if (terrainChanged) {
            level.buildFlagMaps();
            level.cleanWalls();
            ground.updateMap();
            raised.updateMap();
            walls.updateMap();
        }
        if (terrainChanged || fogChanged) fog.updateFog();
        applyActors(state);
        applyLoot(state);
        ActorView self = actors.get(player);
        attackTarget = null;
        if (self != null && self.actor.isAlive()) {
            for (Map.Entry<Integer, ActorView> entry : actors.entrySet()) {
                ActorView target = entry.getValue();
                if (target.kind == 2 && target.actor.isAlive() && level.adjacent(self.actor.pos, target.actor.pos)) {
                    attackTarget = entry.getKey();
                    break;
                }
            }
        }
        attackButton.visible = attackButton.active = attackTarget != null;
        if (self != null) {
            String energy = (state.energy / 10) + "." + (state.energy % 10);
            status.text(state.depth + "층 · HP " + self.actor.HP + "/" + self.actor.HT
                    + " · ATB " + energy + "/10" + (self.actor.HP <= 0 ? " · 쓰러짐" : "")
                    + "\n방향키 이동 · 클릭/Q 평타 · Esc 여관");
        }
    }

    private void rebuild(InnOriginalParty.Snapshot state) {
        if (world != null) {
            erase(world);
            world.destroy();
        }
        actors.clear();
        drops.clear();
        Dungeon.hero = null;
        Dungeon.depth = state.depth;
        Dungeon.branch = state.branch;
        Dungeon.seed = party.seed;
        Dungeon.level = new GuestLevel(state.texture);
        Dungeon.level.setSize(state.width, state.height);
        Dungeon.level.cleanWalls();
        DungeonTileSheet.setupVariance(state.tiles.length, Dungeon.seedCurDepth());
        world = new Group();
        ground = new DungeonTerrainTilemap();
        world.add(ground);
        loot = new Group();
        world.add(loot);
        figures = new Group();
        world.add(figures);
        raised = new RaisedTerrainTilemap();
        world.add(raised);
        walls = new DungeonWallsTilemap();
        world.add(walls);
        fog = new FogOfWar(state.width, state.height);
        world.add(fog);
        fatigue = new Group();
        world.add(fatigue);
        world.add(new PointerArea(ground) {
            @Override
            protected void onClick(PointerEvent event) {
                int cell = ground.screenToTile((int) event.current.x, (int) event.current.y, true);
                party.command("T", cell, 0);
            }
        });
        erase(status);
        erase(attackButton);
        erase(timeButton);
        add(world);
        add(status);
        add(attackButton);
        add(timeButton);
        attackButton.givePointerPriority();
        timeButton.givePointerPriority();
    }

    private void applyActors(InnOriginalParty.Snapshot state) {
        Set<Integer> present = new HashSet<>();
        Set<Integer> tiredActors = new HashSet<>();
        String[] rows = state.actors.split(";");
        for (String row : rows) {
            if (!row.startsWith("!")) continue;
            for (String id : row.substring(1).split(",")) {
                try {
                    tiredActors.add(Integer.parseInt(id));
                } catch (NumberFormatException ignored) {
                    // Skip invalid fatigue records.
                }
            }
        }
        for (String row : rows) {
            String[] columns = row.split(",", -1);
            if (columns.length != 6 || row.startsWith("!")) continue;
            try {
                int id = Integer.parseInt(columns[0]);
                int cell = Integer.parseInt(columns[1]);
                int hp = Integer.parseInt(columns[2]);
                int maxHp = Integer.parseInt(columns[3]);
                int kind = Integer.parseInt(columns[4]);
                if (cell < 0 || cell >= state.tiles.length || hp < 0 || maxHp < hp) continue;
                present.add(id);
                ActorView view = actors.get(id);
                if (view == null) {
                    Mob actor = new ReplicaActor();
                    actor.HP = hp;
                    actor.HT = maxHp;
                    actor.pos = cell;
                    CharSprite sprite = actorSprite(columns[5], kind, id == player);
                    view = new ActorView(actor, sprite, kind);
                    actors.put(id, view);
                    figures.add(sprite);
                    sprite.link(actor);
                    if (id == player) Camera.main.panFollow(sprite, 5f);
                } else {
                    int previous = view.actor.pos;
                    view.actor.HP = hp;
                    view.actor.HT = maxHp;
                    view.actor.pos = cell;
                    if (previous != cell) {
                        if (view.sprite.isMoving) view.sprite.interruptMotion();
                        if (Dungeon.level.adjacent(previous, cell)) view.sprite.move(previous, cell);
                        else view.sprite.place(cell);
                    }
                }
                view.sprite.alpha(hp == 0 ? 0.4f : 1f);
                view.sprite.showInnMonsterSweat(kind == 2 && hp > 0 && tiredActors.contains(id), fatigue);
            } catch (NumberFormatException ignored) {
                // Skip invalid actor records.
            }
        }
        Iterator<Map.Entry<Integer, ActorView>> it = actors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ActorView> entry = it.next();
            if (!present.contains(entry.getKey())) {
                removeVisual(entry.getValue().sprite);
                it.remove();
            }
        }
        figures.sort((first, second) -> {
            if (!(first instanceof CharSprite) || !(second instanceof CharSprite)) return 0;
            CharSprite a = (CharSprite) first;
            CharSprite b = (CharSprite) second;
            return Float.compare(a.y + a.height(), b.y + b.height());
        });
    }

    private CharSprite actorSprite(String type, int kind, boolean local) {
        if (kind == 1) return new InnPartySprite(local ? InnAdventure.profile() : party.peer);
        if (type.startsWith("com.shatteredpixel.shatteredpixeldungeon.sprites.")) {
            Class<?> cls = Reflection.forName(type);
            if (cls != null && CharSprite.class.isAssignableFrom(cls)) {
                CharSprite sprite = (CharSprite) Reflection.newInstance(cls);
                if (sprite != null) return sprite;
            }
        }
        return new InnPartySprite(null);
    }

    private void applyLoot(InnOriginalParty.Snapshot state) {
        Set<Integer> present = new HashSet<>();
        for (String row : state.loot.split(";")) {
            String[] columns = row.split(",", -1);
            if (columns.length != 2) continue;
            try {
                int cell = Integer.parseInt(columns[0]);
                int image = Integer.parseInt(columns[1]);
                if (cell < 0 || cell >= state.tiles.length || image < 0 || image >= 512) continue;
                present.add(cell);
                ItemSprite sprite = drops.get(cell);
                if (sprite == null) {
                    sprite = new ItemSprite(image);
                    sprite.place(cell);
                    loot.add(sprite);
                    drops.put(cell, sprite);
                } else {
                    sprite.view(image, null);
                }
            } catch (NumberFormatException ignored) {
                // Skip invalid item records.
            }
        }
        Iterator<Map.Entry<Integer, ItemSprite>> it = drops.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ItemSprite> entry = it.next();
            if (!present.contains(entry.getKey())) {
                removeVisual(entry.getValue());
                it.remove();
            }
        }
    }

    private void removeVisual(com.watabou.noosa.Gizmo sprite) {
        sprite.killAndErase();
        sprite.destroy();
    }

    private boolean key(KeyEvent event) {
        if (event.code == Input.Keys.SPACE) {
            if (event.pressed && !spaceHeld) party.toggleTime();
            spaceHeld = event.pressed;
            return true;
        }
        GameAction action = KeyBindings.getActionForKey(event);
        if (action == SPDAction.N || action == SPDAction.NE || action == SPDAction.NW
                || action == SPDAction.S || action == SPDAction.SE || action == SPDAction.SW
                || action == SPDAction.W || action == SPDAction.E) {
            if (!event.pressed) movement.remove(action);
            else if (movement.add(action)) {
                tappedMovement.add(action);
                repeatIn = 0.045f;
            }
            return true;
        }
        if (action == SPDAction.TAG_ATTACK) {
            if (event.pressed) attackSelected();
            return true;
        }
        return false;
    }

    private void attackSelected() {
        if (attackTarget != null) party.command("A", attackTarget, 0);
    }

    private void moveHeld() {
        if (movement.isEmpty() && tappedMovement.isEmpty() || !actors.containsKey(player)) return;
        int dx = direction(SPDAction.E, SPDAction.NE, SPDAction.SE)
                - direction(SPDAction.W, SPDAction.NW, SPDAction.SW);
        int dy = direction(SPDAction.S, SPDAction.SE, SPDAction.SW)
                - direction(SPDAction.N, SPDAction.NE, SPDAction.NW);
        tappedMovement.clear();
        if (dx != 0 || dy != 0) {
            party.command("M", dx, dy);
            repeatIn = 0.12f;
        }
    }

    private int direction(GameAction straight, GameAction diagonalA, GameAction diagonalB) {
        return movement.contains(straight) || movement.contains(diagonalA) || movement.contains(diagonalB)
                || tappedMovement.contains(straight) || tappedMovement.contains(diagonalA)
                || tappedMovement.contains(diagonalB) ? 1 : 0;
    }

    @Override
    protected void onBackPressed() {
        Game.instance.finish();
    }

    @Override
    public void destroy() {
        KeyEvent.removeKeyListener(keys);
        ScrollEvent.removeScrollListener(scrolling);
        super.destroy();
    }

    private static final class ActorView {
        final Mob actor;
        final CharSprite sprite;
        final int kind;

        ActorView(Mob actor, CharSprite sprite, int kind) {
            this.actor = actor;
            this.sprite = sprite;
            this.kind = kind;
        }
    }

    private static final class ReplicaActor extends Mob {
        ReplicaActor() { state = PASSIVE; }

        @Override
        public void updateSpriteState() {
            // The guest does not have local buffs or an original Hero actor.
        }
    }

    private static final class GuestLevel extends Level {
        final String texture;

        GuestLevel(String texture) {
            this.texture = texture;
            blobs = new HashMap<>();
        }

        @Override public String tilesTex() { return texture; }
        @Override protected boolean build() { return false; }
        @Override protected void createMobs() {}
        @Override protected void createItems() {}
    }
}
