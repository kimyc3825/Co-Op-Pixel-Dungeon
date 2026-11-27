// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.scenes;

import com.badlogic.gdx.Input;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.InnAdventure;
import com.shatteredpixel.shatteredpixeldungeon.InnOriginalParty;
import com.shatteredpixel.shatteredpixeldungeon.SPDAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.TurnClock;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.InnPartySprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTerrainTilemap;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTileSheet;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonWallsTilemap;
import com.shatteredpixel.shatteredpixeldungeon.tiles.FogOfWar;
import com.shatteredpixel.shatteredpixeldungeon.tiles.RaisedTerrainTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.InnTurnIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndKeyBindings;
import com.watabou.glwrap.Blending;
import com.watabou.input.GameAction;
import com.watabou.input.KeyBindings;
import com.watabou.input.KeyEvent;
import com.watabou.input.PointerEvent;
import com.watabou.input.ScrollEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.NoosaScript;
import com.watabou.noosa.NoosaScriptNoLighting;
import com.watabou.noosa.PointerArea;
import com.watabou.noosa.SkinnedBlock;
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
    private final Signal.Listener<KeyEvent> space = this::space;
    private final Signal.Listener<ScrollEvent> scrolling = event -> {
        if (showingMenu()) return true;
        Camera.main.zoom(Math.max(minZoom, Math.min(maxZoom, Camera.main.zoom - Math.signum(event.amount))));
        return true;
    };
    private InnOriginalParty party;
    private Group world, figures, loot, markers;
    private DungeonTerrainTilemap ground;
    private RaisedTerrainTilemap raised;
    private DungeonWallsTilemap walls;
    private FogOfWar fog;
    private SkinnedBlock water;
    private float waterOffset;
    private int predictedSequence;
    private RenderedTextBlock status;
    private IconButton menuButton;
    private RedButton attackButton;
    private RedButton waitButton;
    private InnTurnIndicator turnIndicator;
    // Countdown of the latest snapshot, counted down locally between snapshots.
    private float graceShown;
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
        menuButton = new IconButton(Icons.get(Icons.PREFS)) {
            @Override
            protected void onClick() {
                openMenu();
            }

            @Override
            protected String hoverText() {
                return Messages.get(WndKeyBindings.class, "menu");
            }
        };
        menuButton.camera = uiCamera;
        menuButton.setRect(uiCamera.width - 23, 4, 19, 19);
        add(menuButton);
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
        waitButton = new RedButton("대기 (Space)", 7) {
            @Override
            protected void onClick() {
                waitTurn();
            }
        };
        waitButton.camera = uiCamera;
        waitButton.setRect(uiCamera.width - 110, uiCamera.height - 22, 52, 16);
        waitButton.visible = waitButton.active = false;
        add(waitButton);
        turnIndicator = new InnTurnIndicator();
        turnIndicator.camera = uiCamera;
        turnIndicator.setRect(0, 26, uiCamera.width, InnTurnIndicator.HEIGHT);
        add(turnIndicator);
        KeyEvent.addKeyListener(keys);
        KeyEvent.captureKey(Input.Keys.SPACE, space);
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
        if (water != null) {
            waterOffset -= 5 * Game.elapsed;
            water.offsetTo(0, waterOffset);
            waterOffset = water.offsetY();
        }
        party.updateGuest(Game.elapsed);
        updateTurnIndicator(state);
        repeatIn -= Game.elapsed;
        if (repeatIn <= 0 && !party.awaitingAction() && !showingMenu()) moveHeld();
    }

    private void apply(InnOriginalParty.Snapshot state) {
        revision = state.revision;
        graceShown = state.grace / 10f;
        waitButton.visible = waitButton.active = state.result.equals("play");
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
            status.text(state.depth + "층 · HP " + self.actor.HP + "/" + self.actor.HT
                    + (self.actor.HP <= 0 ? " · 쓰러짐" : "")
                    + "\n방향키 이동 · 클릭/Q 평타 · Space 대기 · Esc 메뉴");
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
        predictedSequence = 0;
        world = new Group();
        // Water tiles are transparent in the terrain sheet and show this layer through them.
        water = new SkinnedBlock(state.width * DungeonTilemap.SIZE, state.height * DungeonTilemap.SIZE,
                Dungeon.level.waterTex()) {
            @Override
            protected NoosaScript script() {
                return NoosaScriptNoLighting.get();
            }

            @Override
            public void draw() {
                Blending.disable();
                super.draw();
                Blending.enable();
            }
        };
        water.autoAdjust = true;
        world.add(water);
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
        markers = new Group();
        world.add(markers);
        world.add(new PointerArea(ground) {
            @Override
            protected void onClick(PointerEvent event) {
                if (showingMenu()) return;
                int cell = ground.screenToTile((int) event.current.x, (int) event.current.y, true);
                party.command("T", cell, 0);
            }
        });
        erase(status);
        erase(menuButton);
        erase(attackButton);
        erase(waitButton);
        erase(turnIndicator);
        add(world);
        add(status);
        add(turnIndicator);
        add(menuButton);
        add(attackButton);
        add(waitButton);
        attackButton.givePointerPriority();
        waitButton.givePointerPriority();
        menuButton.givePointerPriority();
        for (var component : members.toArray(new Gizmo[0])) {
            if (component instanceof Window) {
                erase(component);
                add(component);
            }
        }
    }

    private void applyActors(InnOriginalParty.Snapshot state) {
        Set<Integer> present = new HashSet<>();
        Set<Integer> alertedActors = new HashSet<>();
        String[] rows = state.actors.split(";");
        for (String row : rows) {
            if (!row.startsWith(InnOriginalParty.DETECTION_RECORD)) continue;
            for (String id : row.substring(1).split(",")) {
                try {
                    alertedActors.add(Integer.parseInt(id));
                } catch (NumberFormatException ignored) {
                    // Skip invalid status records.
                }
            }
        }
        for (String row : rows) {
            String[] columns = row.split(",", -1);
            if (columns.length != 6 || row.startsWith(InnOriginalParty.DETECTION_RECORD)) continue;
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
                    // Keep the local player's predicted step until the host has answered that command.
                    boolean predicted = id == player && predictedSequence > 0 && state.ack < predictedSequence;
                    if (id == player && !predicted) predictedSequence = 0;
                    if (!predicted) view.actor.pos = cell;
                    if (!predicted && previous != cell) {
                        if (view.sprite.isMoving) view.sprite.interruptMotion();
                        if (Dungeon.level.adjacent(previous, cell)) view.sprite.move(previous, cell);
                        else view.sprite.place(cell);
                    }
                }
                view.sprite.alpha(hp == 0 ? 0.4f : 1f);
                if (hp > 0 && alertedActors.contains(id)) view.sprite.showAlert(markers);
                else view.sprite.hideAlert();
            } catch (NumberFormatException ignored) {
                // Skip invalid actor records.
            }
        }
        Iterator<Map.Entry<Integer, ActorView>> it = actors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ActorView> entry = it.next();
            if (!present.contains(entry.getKey())) {
                entry.getValue().sprite.hideAlert();
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
        if (showingMenu()) return true;
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

    private boolean space(KeyEvent event) {
        boolean firstPress = event.pressed && !spaceHeld;
        spaceHeld = event.pressed;
        if (KeyBindings.bindingKey) return false;
        if (firstPress && !showingMenu()) waitTurn();
        return true;
    }

    /** Asks the host to let this player wait one turn. */
    private void waitTurn() {
        if (party == null || showingMenu()) return;
        ActorView self = actors.get(player);
        if (self == null || !self.actor.isAlive()) return;
        party.command("W", 0, 0);
    }

    private void updateTurnIndicator(InnOriginalParty.Snapshot state) {
        if (state == null || turnIndicator == null) return;
        if (state.turn == TurnClock.WAIT_NONE || !state.result.equals("play")) {
            turnIndicator.hide();
            return;
        }
        graceShown = Math.max(0f, graceShown - Game.elapsed);
        // The host's party member is this screen's player.
        turnIndicator.show(state.turn == TurnClock.WAIT_GUEST, graceShown);
    }

    private void attackSelected() {
        if (attackTarget != null && !showingMenu()) party.command("A", attackTarget, 0);
    }

    private void moveHeld() {
        if (movement.isEmpty() && tappedMovement.isEmpty() || !actors.containsKey(player)) return;
        int dx = direction(SPDAction.E, SPDAction.NE, SPDAction.SE)
                - direction(SPDAction.W, SPDAction.NW, SPDAction.SW);
        int dy = direction(SPDAction.S, SPDAction.SE, SPDAction.SW)
                - direction(SPDAction.N, SPDAction.NE, SPDAction.NW);
        tappedMovement.clear();
        if (dx != 0 || dy != 0) {
            int sequence = party.command("M", dx, dy);
            if (sequence > 0) predictStep(dx, dy, sequence);
            repeatIn = 0.12f;
        }
    }

    /** Shows the local player's step at once; the host's next answer confirms or corrects it. */
    private void predictStep(int dx, int dy, int sequence) {
        ActorView self = actors.get(player);
        InnOriginalParty.Snapshot state = party.latest();
        Level level = Dungeon.level;
        // While the world waits for the host player the step is queued, so wait for the host's answer instead.
        if (self == null || state == null || !self.actor.isAlive() || self.sprite.isMoving
                || state.turn == TurnClock.WAIT_HOST) return;
        int from = self.actor.pos;
        int cell = from + dx + dy * level.width();
        if (!level.insideMap(cell) || !level.adjacent(from, cell) || !level.passable[cell] || level.pit[cell]) return;
        for (ActorView other : actors.values()) {
            if (other != self && other.actor.pos == cell && other.actor.HP > 0) return;
        }
        self.sprite.move(from, cell);
        self.actor.pos = cell;
        predictedSequence = sequence;
    }

    private int direction(GameAction straight, GameAction diagonalA, GameAction diagonalB) {
        return movement.contains(straight) || movement.contains(diagonalA) || movement.contains(diagonalB)
                || tappedMovement.contains(straight) || tappedMovement.contains(diagonalA)
                || tappedMovement.contains(diagonalB) ? 1 : 0;
    }

    @Override
    protected void onBackPressed() {
        openMenu();
    }

    private void openMenu() {
        if (showingMenu()) return;
        movement.clear();
        tappedMovement.clear();
        add(new WndGame());
    }

    private boolean showingMenu() {
        for (var component : members) {
            if (component instanceof Window) return true;
        }
        return false;
    }

    @Override
    public void onPause() {
        spaceHeld = false;
        super.onPause();
    }

    @Override
    public void destroy() {
        KeyEvent.removeKeyListener(keys);
        KeyEvent.releaseCapturedKey(space);
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

        @Override
        public String waterTex() {
            if (texture.startsWith("environment/tiles_prison")) return Assets.Environment.WATER_PRISON;
            if (texture.startsWith("environment/tiles_caves")) return Assets.Environment.WATER_CAVES;
            if (texture.startsWith("environment/tiles_city")) return Assets.Environment.WATER_CITY;
            if (texture.startsWith("environment/tiles_halls")) return Assets.Environment.WATER_HALLS;
            return Assets.Environment.WATER_SEWERS;
        }
        @Override protected boolean build() { return false; }
        @Override protected void createMobs() {}
        @Override protected void createItems() {}
    }
}
