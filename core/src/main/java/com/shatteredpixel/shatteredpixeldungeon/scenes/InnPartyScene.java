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
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTerrainTilemap;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTileSheet;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonWallsTilemap;
import com.shatteredpixel.shatteredpixeldungeon.tiles.FogOfWar;
import com.shatteredpixel.shatteredpixeldungeon.tiles.RaisedTerrainTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.InnGuestStatus;
import com.shatteredpixel.shatteredpixeldungeon.ui.InnGuestTool;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.InnTurnIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInnGuestBag;
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
import com.watabou.utils.RectF;
import com.watabou.utils.Reflection;
import com.watabou.utils.Signal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Guest view of the host's original dungeon, using the original tiles, camera and sprites. */
public final class InnPartyScene extends PixelScene {
    /**
     * Steps the guest may send ahead of the host's answers while exploring. Each is shown at once; the host
     * confirms or corrects them in order. With an enemy in view only one step is in flight, so the guest can
     * react to the fight turn by turn.
     */
    private static final int STEPS_AHEAD = 3;
    private final Map<Integer, ActorView> actors = new HashMap<>();
    private final Map<Integer, ItemSprite> drops = new HashMap<>();
    /** Cells with a chest, tomb or remains: stepping there opens it instead of moving. */
    private final Set<Integer> containers = new HashSet<>();
    /** Cells with a chest the companion cannot open (locked or crystal). */
    private final Set<Integer> lockedContainers = new HashSet<>();
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
    private InnGuestStatus statusPane;
    private InnGuestTool bagButton;
    private InnGuestTool waitTool;
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
    /** Bag index of the potion the player is aiming, or -1. */
    private int throwIndex = -1;
    private RectF insets;

    @Override
    public void create() {
        super.create();
        party = InnOriginalParty.current();
        insets = getCommonInsets();
        statusPane = new InnGuestStatus(InnAdventure.profile().heroClass());
        statusPane.camera = uiCamera;
        statusPane.setPos(insets.left, insets.top);
        statusPane.visible = false;
        add(statusPane);
        status = renderTextBlock("파티장이 원본 던전에 입장하는 중", 8);
        status.camera = uiCamera;
        status.setPos(insets.left + 5, insets.top + 5);
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
        menuButton.setRect(uiCamera.width - getCommonInsets().right - 23, getCommonInsets().top + 4, 19, 19);
        add(menuButton);
        attackButton = new RedButton("공격 (Q)") {
            @Override
            protected void onClick() {
                attackSelected();
            }
        };
        float right = uiCamera.width - insets.right;
        float bottom = uiCamera.height - insets.bottom;
        // The original toolbar's inventory and wait buttons, in the same order at the bottom right.
        bagButton = new InnGuestTool(0, 0, 24, 26, 160, 0) {
            @Override
            protected void onClick() {
                openBag();
            }

            @Override
            public GameAction keyAction() {
                return SPDAction.INVENTORY;
            }

            @Override
            protected String hoverText() {
                return Messages.titleCase(Messages.get(WndKeyBindings.class, "inventory"));
            }
        };
        bagButton.camera = uiCamera;
        bagButton.setPos(right - bagButton.width(), bottom - bagButton.height());
        add(bagButton);
        waitTool = new InnGuestTool(24, 0, 20, 26, 176, 0) {
            @Override
            protected void onClick() {
                waitTurn();
            }

            @Override
            protected String hoverText() {
                return "대기 (Space)";
            }
        };
        waitTool.camera = uiCamera;
        waitTool.setPos(bagButton.left() - waitTool.width(), bagButton.top());
        add(waitTool);
        attackButton.camera = uiCamera;
        attackButton.setRect(right - 48, bagButton.top() - 18, 48, 16);
        attackButton.visible = attackButton.active = false;
        add(attackButton);
        waitButton = new RedButton("대기 (Space)", 7) {
            @Override
            protected void onClick() {
                waitTurn();
            }
        };
        waitButton.visible = waitButton.active = false;
        turnIndicator = new InnTurnIndicator();
        turnIndicator.camera = uiCamera;
        turnIndicator.setRect(0, insets.top + InnGuestStatus.HEIGHT + 2, uiCamera.width, InnTurnIndicator.HEIGHT);
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
        if (repeatIn <= 0 && party.inFlight() < stepsAhead() && !showingMenu()) moveHeld();
    }

    private void apply(InnOriginalParty.Snapshot state) {
        revision = state.revision;
        graceShown = state.grace / 10f;
        boolean playing = state.result.equals("play");
        waitTool.enable(playing);
        bagButton.enable(playing);
        if (!playing) throwIndex = -1;
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
            statusPane.visible = true;
            statusPane.show(self.actor.HP, self.actor.HT, state.level, state.depth + "층 · 금화 " + state.gold);
        }
        refreshStatusLine(self);
    }

    private void rebuild(InnOriginalParty.Snapshot state) {
        if (world != null) {
            erase(world);
            world.destroy();
        }
        actors.clear();
        drops.clear();
        containers.clear();
        lockedContainers.clear();
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
                if (throwIndex >= 0) {
                    int index = throwIndex;
                    throwIndex = -1;
                    refreshStatusLine(actors.get(player));
                    if (cell >= 0) party.command("H", index, cell);
                    return;
                }
                party.command("T", cell, 0);
            }
        });
        Gizmo[] hud = {statusPane, status, turnIndicator, menuButton, attackButton, waitTool, bagButton};
        for (Gizmo component : hud) erase(component);
        add(world);
        for (Gizmo component : hud) add(component);
        attackButton.givePointerPriority();
        waitTool.givePointerPriority();
        bagButton.givePointerPriority();
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
        containers.clear();
        lockedContainers.clear();
        for (String row : state.loot.split(";")) {
            String[] columns = row.split(",", -1);
            if (columns.length != 2) continue;
            try {
                int cell = Integer.parseInt(columns[0]);
                int image = Integer.parseInt(columns[1]);
                if (cell < 0 || cell >= state.tiles.length || image < 0 || image >= 512) continue;
                present.add(cell);
                if (image == ItemSpriteSheet.LOCKED_CHEST || image == ItemSpriteSheet.CRYSTAL_CHEST) {
                    containers.add(cell);
                    lockedContainers.add(cell);
                } else if (image == ItemSpriteSheet.CHEST || image == ItemSpriteSheet.TOMB
                        || image == ItemSpriteSheet.BONES || image == ItemSpriteSheet.REMAINS) {
                    containers.add(cell);
                }
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
        if (action == SPDAction.INVENTORY) {
            if (event.pressed) openBag();
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

    /** How many steps may be in flight: several while exploring, one with a living enemy in view. */
    private int stepsAhead() {
        for (ActorView view : actors.values()) {
            if (view.kind == 2 && view.actor.isAlive()) return 1;
        }
        return STEPS_AHEAD;
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
        if ((dx != 0 || dy != 0) && !knownBlocked(dx, dy)) {
            int sequence = party.command("M", dx, dy);
            if (sequence > 0) predictStep(dx, dy, sequence);
            repeatIn = 0.12f;
        }
    }

    /**
     * True if the step leads into a cell this screen already knows the host would refuse: a wall, a chasm or
     * another living character. Such moves are not sent, so holding a key against a wall costs nothing.
     */
    private boolean knownBlocked(int dx, int dy) {
        ActorView self = actors.get(player);
        Level level = Dungeon.level;
        if (self == null || level == null) return true;
        int from = self.actor.pos;
        int cell = from + dx + dy * level.width();
        if (!level.insideMap(cell) || !level.adjacent(from, cell)) return true;
        if (level.visited[cell] && (!level.passable[cell] || level.pit[cell])) return true;
        if (lockedContainers.contains(cell)) return true;
        for (ActorView other : actors.values()) {
            if (other != self && other.actor.pos == cell && other.actor.HP > 0) return true;
        }
        return false;
    }

    /** Shows the local player's step at once; the host's next answer confirms or corrects it. */
    private void predictStep(int dx, int dy, int sequence) {
        ActorView self = actors.get(player);
        InnOriginalParty.Snapshot state = party.latest();
        Level level = Dungeon.level;
        // While the world waits for the host player the step is queued, so wait for the host's answer instead.
        if (self == null || state == null || !self.actor.isAlive() || state.turn == TurnClock.WAIT_HOST) return;
        int from = self.actor.pos;
        int cell = from + dx + dy * level.width();
        if (!level.insideMap(cell) || !level.adjacent(from, cell) || !level.passable[cell] || level.pit[cell]
                || containers.contains(cell)) return;
        for (ActorView other : actors.values()) {
            if (other != self && other.actor.pos == cell && other.actor.HP > 0) return;
        }
        // A step sent while the previous one is still animating finishes that one at once.
        if (self.sprite.isMoving) self.sprite.interruptMotion();
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
        if (throwIndex >= 0) {
            throwIndex = -1;
            refreshStatusLine(actors.get(player));
            return;
        }
        openMenu();
    }

    /** The line under the status pane: aiming prompt, a fallen notice, or nothing. */
    private void refreshStatusLine(ActorView self) {
        String line = throwIndex >= 0 ? "던질 곳을 선택하세요 · Esc 취소"
                : self != null && self.actor.HP <= 0 ? "쓰러졌습니다 · 파티장을 지켜봅니다" : "";
        // An empty text keeps the old words on screen, so hide the line instead.
        status.visible = !line.isEmpty();
        if (status.visible) status.text(line);
        status.setPos(insets.left + 3, insets.top + InnGuestStatus.HEIGHT + InnTurnIndicator.HEIGHT + 4);
    }

    private void openBag() {
        InnOriginalParty.Snapshot state = party == null ? null : party.latest();
        if (state == null || showingMenu() || !state.result.equals("play")) return;
        ActorView self = actors.get(player);
        if (self == null || !self.actor.isAlive()) return;
        movement.clear();
        tappedMovement.clear();
        throwIndex = -1;
        refreshStatusLine(self);
        add(new WndInnGuestBag(state.gear, state.gold, new WndInnGuestBag.Actions() {
            @Override public void equip(int index) { party.command("E", index, 0); }
            @Override public void unequip(int slot) { party.command("R", slot, 0); }
            @Override public void use(int index, int target) { party.command("U", index, target); }
            @Override public void throwAt(int index) {
                throwIndex = index;
                refreshStatusLine(actors.get(player));
            }
            @Override public void drop(int index) { party.command("D", index, 0); }
            @Override public void give(int index) { party.command("G", index, 0); }
        }));
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
