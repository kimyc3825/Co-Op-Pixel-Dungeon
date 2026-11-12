// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTileSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.input.KeyEvent;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.PointerArea;
import com.watabou.noosa.TextInput;
import com.watabou.noosa.TextureFilm;
import com.watabou.noosa.Tilemap;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.PointF;
import com.watabou.utils.RectF;
import com.watabou.utils.Signal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class NativeDungeonScene extends PixelScene {
    private static final int BACK = 0xFF151918;
    private static final int BOARD = 0xFF202824;
    private static final int PANEL = 0xFF252C27;
    private static final int EDGE = 0xFF454F43;
    static final int TEXT = 0xF4E5C9;
    static final int MUTED = 0xC0B7A0;
    static final int SELF = 0xD7BD78;
    static final int ALLY = 0x9FD1CA;
    static final int ENEMY = 0xEC9F8A;

    private static final int TILE = 16;
    private static final int WALL_LIP_HEIGHT = 4;
    private static final long MOVE_QUEUE_LEAD_MS = 70;
    private static final long MOVE_RETRY_NS = 60_000_000L;
    private static final long COMMAND_ACK_WAIT_NS = 550_000_000L;

    private record Step(int dx, int dy) {}
    private record SubmittedAction(InnApi.Action action, long acknowledgement, long sentAt) {}
    private record ImpactLabel(RenderedTextBlock text, InnApi.BattleEvent event) {}

    private static final class WallLip extends Image {
        final int row;

        WallLip(String texture, RectF overhang, int row) {
            super(texture);
            this.row = row;
            float top = overhang.top + overhang.height() / 2;
            float bottom = top + overhang.height() * WALL_LIP_HEIGHT / TILE;
            frame(new RectF(overhang.left, top, overhang.right, bottom));
        }
    }

    private static final Comparator<Gizmo> WORLD_DEPTH = Comparator.comparingDouble(NativeDungeonScene::drawDepth)
        .thenComparingInt(value -> value instanceof WallLip ? 0 : 1);

    private final Map<String, DungeonActorView> actorViews = new HashMap<>();
    private final Map<String, Image> dropViews = new HashMap<>();
    private final Map<Long, ColorBlock> projectileViews = new HashMap<>();
    private final List<ImpactLabel> impactLabels = new ArrayList<>();
    private final List<WallLip> wallLips = new ArrayList<>();
    private final PartySlot[] party = new PartySlot[2];
    private final KeyboardMovement movement = new KeyboardMovement();
    private InnApi.DungeonState state;
    private InnApi.Combatant self;
    private Signal.Listener<KeyEvent> keys;
    private Group world;
    private Group actorOverlays;
    private Group projectiles;
    private Group impacts;
    private Group loot;
    private Group help;
    private Tilemap tiles;
    private ColorBlock floorPanel;
    private ColorBlock selectedTile;
    private ColorBlock queuedTile;
    private ColorBlock aimTile;
    private Group waitingCard;
    private RenderedTextBlock waitingLabel;
    private ColorBlock pauseShade;
    private Group pauseCard;
    private RenderedTextBlock pauseCountdown;
    private RenderedTextBlock floorTitle;
    private RenderedTextBlock floorStatus;
    private RenderedTextBlock queueLabel;
    private RenderedTextBlock fireLabel;
    private RenderedTextBlock notice;
    private StyledButton helpButton;
    private StyledButton pauseButton;
    private StyledButton hitButton;
    private StyledButton shootButton;
    private StyledButton rationButton;
    private Window panelWindow;
    private String selected;
    private String floorSummary = "";
    private float mapX;
    private float mapY;
    private float tile = TILE;
    private float sideX;
    private float sideWidth;
    private float boardX;
    private float boardY;
    private float boardWidth;
    private float boardHeight;
    private int columns;
    private int rows;
    private SubmittedAction submitted;
    private Step lastMove;
    private long lastMoveAt;
    private String localNotice = "";
    private long noticeUntil;
    private long seenAttackEvent;
    private boolean attackHeld;
    private boolean shootHeld;
    private boolean aiming;
    private boolean rationHeld;
    private boolean helpHeld;
    private boolean pauseHeld;
    private boolean rationFirst = true;
    private boolean leaving;

    @Override
    public void create() {
        super.create();
        RectF insets = getCommonInsets();
        float left = insets.left + 8;
        float top = insets.top + 5;
        float width = Camera.main.width - insets.left - insets.right - 16;
        float height = Camera.main.height - insets.top - insets.bottom - 10;
        ColorBlock background = panel(this, 0, 0, Camera.main.width, Camera.main.height, BACK);
        background.camera = Camera.main;
        floorTitle = label(this, NativeClient.expeditionThemeTitle(DungeonLayout.SEWERS), 13, TEXT, left, top + 2, 95);
        boolean compactHeader = width < 295;
        float leaveWidth = compactHeader ? 43 : 58;
        float helpWidth = compactHeader ? 37 : 58;
        float pauseWidth = compactHeader ? 57 : 73;
        float right = left + width;
        float pauseX = right - leaveWidth - helpWidth - pauseWidth - 10;
        floorStatus = label(this, " ", 7, MUTED, left, top + 18, Math.min(145, pauseX - left - 3));
        StyledButton leave = button(this, "여관으로", this::leave);
        leave.setRect(right - leaveWidth, top + 3, leaveWidth, 19);
        helpButton = button(this, compactHeader ? "F1 도움" : "F1 조작법", this::toggleHelp);
        helpButton.setRect(right - leaveWidth - helpWidth - 5, top + 3, helpWidth, 19);
        pauseButton = button(this, "전술 정지 · P", this::togglePause);
        pauseButton.setRect(pauseX, top + 3, pauseWidth, 19);

        sideWidth = Math.max(77, Math.min(116, width * 0.31f));
        sideX = left + width - sideWidth;
        boardX = left;
        boardY = top + 31;
        boardWidth = width - sideWidth - 6;
        boardHeight = height - 31;
        panel(this, boardX, boardY, boardWidth, boardHeight, EDGE);
        floorPanel = panel(this, boardX + 1, boardY + 1, boardWidth - 2, boardHeight - 2, BOARD);
        panel(this, sideX, boardY, sideWidth, boardHeight, EDGE);
        panel(this, sideX + 1, boardY + 1, sideWidth - 2, boardHeight - 2, PANEL);
        float toolsWidth = (sideWidth - 16) / 3;
        button(this, "가방", () -> showPanel(BackpackWindow::new)).setRect(sideX + 6, boardY + 4, toolsWidth, 13);
        button(this, "상태", () -> showPanel(StatusWindow::new)).setRect(sideX + 8 + toolsWidth, boardY + 4, toolsWidth, 13);
        button(this, "대화", () -> showPanel(ChatWindow::new)).setRect(sideX + 10 + toolsWidth * 2, boardY + 4, toolsWidth, 13);

        float partyTop = boardY + 21;
        float spacing = boardHeight < 125 ? 26 : 31;
        for (int i = 0; i < party.length; i++) {
            party[i] = new PartySlot(sideX + 6, partyTop + i * spacing, sideWidth - 12);
        }
        queueLabel = label(this, "예약 없음", 7, ALLY, sideX + 6, partyTop + 2 * spacing + 3, sideWidth - 12);
        float attackWidth = (sideWidth - 15) / 2;
        hitButton = button(this, "평타 · Space", this::attack);
        hitButton.setRect(sideX + 6, boardY + boardHeight - 24, attackWidth, 18);
        hitButton.enable(false);
        shootButton = button(this, "활 · R", this::toggleAim);
        shootButton.setRect(sideX + 9 + attackWidth, boardY + boardHeight - 24, attackWidth, 18);
        shootButton.enable(false);
        rationButton = button(this, "식량 0 · E", this::eat);
        rationButton.setRect(sideX + 6, boardY + boardHeight - 44, sideWidth - 12, 18);
        rationButton.enable(false);
        notice = label(this, " ", 7, ENEMY, boardX + 5, boardY + boardHeight - 14, boardWidth - 10);

        selectedTile = panel(this, 0, 0, 1, 1, 0x66D5664E);
        queuedTile = panel(this, 0, 0, 1, 1, 0x6594C9C0);
        aimTile = panel(this, 0, 0, 1, 1, 0x88E9C474);
        selectedTile.visible = queuedTile.visible = aimTile.visible = false;
        loot = new Group();
        add(loot);
        world = new Group();
        add(world);
        projectiles = new Group();
        add(projectiles);
        pauseShade = panel(this, boardX + 1, boardY + 1, boardWidth - 2, boardHeight - 2, 0x8A757575);
        pauseShade.visible = false;
        actorOverlays = new Group();
        add(actorOverlays);
        impacts = new Group();
        add(impacts);
        fireLabel = label(this, " ", 6, MUTED, boardX + boardWidth - 56, boardY + 5, 53);
        pauseCard = new Group();
        add(pauseCard);
        float pauseCardWidth = Math.min(boardWidth - 14, 118);
        float pauseCardX = boardX + (boardWidth - pauseCardWidth) / 2;
        float pauseCardY = boardY + 8;
        panel(pauseCard, pauseCardX, pauseCardY, pauseCardWidth, 30, 0xE9262C29);
        RenderedTextBlock pauseTitle = label(pauseCard, "전술 정지", 9, TEXT, pauseCardX + 5, pauseCardY + 4, pauseCardWidth - 10);
        pauseTitle.align(RenderedTextBlock.CENTER_ALIGN);
        pauseCountdown = label(pauseCard, " ", 7, TEXT, pauseCardX + 5, pauseCardY + 17, pauseCardWidth - 10);
        pauseCountdown.align(RenderedTextBlock.CENTER_ALIGN);
        pauseCard.visible = false;
        waitingCard = new Group();
        add(waitingCard);
        waitingCard.visible = false;
        float waitingWidth = Math.min(boardWidth - 14, 144);
        float waitingX = boardX + (boardWidth - waitingWidth) / 2;
        float waitingY = boardY + boardHeight / 2 - 13;
        panel(waitingCard, waitingX, waitingY, waitingWidth, 26, 0xE9242C27);
        waitingLabel = label(waitingCard, " ", 8, TEXT, waitingX + 4, waitingY + 7, waitingWidth - 8);
        waitingLabel.align(RenderedTextBlock.CENTER_ALIGN);

        add(new PointerArea(floorPanel) {
            @Override
            protected void onClick(PointerEvent event) {
                PointF point = camera().screenToCamera((int) event.current.x, (int) event.current.y);
                onTile((int) Math.floor((point.x - mapX) / tile), (int) Math.floor((point.y - mapY) / tile));
            }
        });

        help = new Group();
        add(help);
        float helpCardWidth = Math.min(boardWidth - 12, 131);
        float helpX = boardX + 6;
        float helpY = boardY + 6;
        panel(help, helpX, helpY, helpCardWidth, 85, 0xC01A201D);
        panel(help, helpX, helpY, helpCardWidth, 1, 0xAF9DB4A6);
        label(help, "이동  WASD · 방향키", 7, TEXT, helpX + 5, helpY + 4, helpCardWidth - 10);
        label(help, "대각선  방향 조합 · 숫자패드", 7, TEXT, helpX + 5, helpY + 15, helpCardWidth - 10);
        label(help, "평타  Space · 적 클릭", 7, TEXT, helpX + 5, helpY + 26, helpCardWidth - 10);
        label(help, "인접 칸 클릭  한 칸 이동", 7, TEXT, helpX + 5, helpY + 37, helpCardWidth - 10);
        label(help, "식량 사용  E · 행동력 3", 7, TEXT, helpX + 5, helpY + 48, helpCardWidth - 10);
        label(help, "전술 정지 / 재개  P", 7, TEXT, helpX + 5, helpY + 59, helpCardWidth - 10);
        label(help, "활 조준  R → 칸 클릭", 7, TEXT, helpX + 5, helpY + 70, helpCardWidth - 10);
        help.visible = false;

        keys = this::onKey;
        KeyEvent.addKeyListener(keys);
        accept(NativeClient.dungeonState());
        updatePauseControls();
        fadeIn();
    }

    @Override
    public void update() {
        InnApi.DungeonState next = NativeClient.dungeonState();
        if (next != state) accept(next);
        long now = NativeClient.dungeonNow();
        if (state != null) {
            for (PartySlot slot : party) slot.update(now);
        }
        updateActionButtons(now);
        updatePauseControls();
        updateRangedVisuals(now);
        updateAimMarker();
        String connection = NativeClient.dungeonConnection();
        setText(floorStatus, connection.isEmpty() ? floorSummary : floorSummary + "   ·   " + connection);
        String connectionNotice = NativeClient.dungeonNotice();
        setText(notice, connectionNotice.isEmpty() && System.nanoTime() < noticeUntil ? localNotice : connectionNotice);
        previewQueuedMove(now);
        onHotkey(Input.Keys.F1, Gdx.input.isKeyPressed(Input.Keys.F1));
        onHotkey(Input.Keys.SPACE, Gdx.input.isKeyPressed(Input.Keys.SPACE));
        onHotkey(Input.Keys.R, Gdx.input.isKeyPressed(Input.Keys.R));
        onHotkey(Input.Keys.E, Gdx.input.isKeyPressed(Input.Keys.E));
        onHotkey(Input.Keys.P, Gdx.input.isKeyPressed(Input.Keys.P));
        updateInput(now);
        super.update();
        world.sort(WORLD_DEPTH);
    }

    private static double drawDepth(Gizmo value) {
        return value instanceof DungeonActorView actor ? actor.depth() : ((WallLip) value).row;
    }

    private void accept(InnApi.DungeonState next) {
        InnApi.DungeonState prior = state;
        InnApi.Combatant priorSelf = self;
        state = next;
        boolean paused = paused();
        // The actor layer remains visible while its interpolation and sprite animations are stopped.
        world.active = !paused;
        if (paused != (prior != null && prior.pausedUntil() > 0)) {
            movement.reset();
            submitted = null;
            aiming = false;
            lastMove = null;
            if (paused) actorViews.values().forEach(DungeonActorView::cancelPreviewMove);
        }
        if (next == null) {
            self = null;
            submitted = null;
            showWaiting("던전을 불러오는 중…");
            return;
        }
        if (tiles == null || prior == null || !next.map().equals(prior.map()) || !next.id().equals(prior.id()) ||
                !next.theme().equals(prior.theme())) {
            setText(floorTitle, NativeClient.expeditionThemeTitle(next.theme()));
            buildMap(next.map(), next.theme());
            actorViews.values().forEach(view -> {
                view.remove();
                view.destroy();
            });
            actorViews.clear();
            dropViews.values().forEach(image -> {
                image.remove();
                image.destroy();
            });
            dropViews.clear();
            selected = null;
            submitted = null;
            lastMove = null;
        }
        self = find(next.selfId());
        long now = NativeClient.dungeonNow();
        String hostId = next.actors().getFirst().id();
        for (InnApi.Combatant actor : next.actors()) {
            DungeonActorView view = actorViews.get(actor.id());
            if (view == null) {
                view = new DungeonActorView(actor, actor.id().equals(next.selfId()), actor.id().equals(hostId));
                actorViews.put(actor.id(), view);
                world.add(view);
                actorOverlays.add(view.overlay());
            } else {
                view.accept(actor, find(actor.targetId()), now);
            }
            view.place(mapX, mapY, tile);
        }
        actorViews.entrySet().removeIf(entry -> {
            if (find(entry.getKey()) != null) return false;
            entry.getValue().remove();
            entry.getValue().destroy();
            return true;
        });
        if (submitted != null && next.commandAck() > submitted.acknowledgement()) {
            if (self != null && priorSelf != null && "move".equals(submitted.action().type()) &&
                self.x() == priorSelf.x() && self.y() == priorSelf.y() && !submitted.action().equals(next.queued())) {
                actorViews.get(self.id()).cancelPreviewMove();
            }
            submitted = null;
        }
        showLoot(next, prior);
        for (InnApi.BattleEvent event : next.attackEvents()) {
            if (event.id() <= seenAttackEvent) continue;
            seenAttackEvent = event.id();
            showAttackResult(event, now);
        }
        setText(fireLabel, next.friendlyFire() ? "아군 피해 켜짐" : "아군 피해 꺼짐");
        fireLabel.hardlight(next.friendlyFire() ? ENEMY : MUTED);

        int index = 0;
        if (self != null) party[index++].set(self, true);
        for (InnApi.Combatant actor : next.actors()) {
            if (index == party.length) break;
            if (!"enemy".equals(actor.kind()) && !actor.id().equals(next.selfId())) {
                party[index++].set(actor, false);
            }
        }
        while (index < party.length) party[index++].set(null, false);
        long enemies = next.actors().stream().filter(actor -> enemy(actor) && actor.hp() > 0).count();
        boolean practice = next.actors().stream().anyMatch(actor -> "guide".equals(actor.kind()));
        floorSummary = (practice ? "길잡이 훈련" : "2인 원정") + ("won".equals(next.status()) ? "   ·   소탕 완료" :
            (next.inCombat() ? "   ·   교전" : "   ·   탐색") + "   ·   남은 적 " + enemies);
        if (!"active".equals(next.status()) && !"won".equals(next.status())) {
            showWaiting(switch (next.status()) {
                case "lost" -> "파티가 쓰러졌습니다";
                case "closed" -> "원정이 끝났습니다";
                default -> "여관으로 돌아갈 수 있습니다";
            });
        } else if (next.waitingForPartner()) {
            showWaiting("동료가 입장하는 중…");
        } else if (self != null && self.hp() == 0) {
            showWaiting("쓰러졌습니다 · 동료의 전투를 지켜봅니다");
        } else {
            showWaiting("");
        }
        InnApi.Action queued = activePlayer() ? next.queued() : null;
        setText(queueLabel, queued == null ? "예약 없음" : switch (queued.type()) {
            case "move" -> "예약  " + directionName(queued.dx(), queued.dy()) + " 이동";
            case "eat" -> "예약  식량";
            case "shoot" -> "예약  화살";
            default -> "예약  평타";
        });
        updateMarkers();
    }

    private void showLoot(InnApi.DungeonState next, InnApi.DungeonState prior) {
        for (InnApi.Drop drop : next.drops()) {
            if (drop.pickedBy() == null) {
                Image image = dropViews.computeIfAbsent(drop.id(), id -> {
                    Image sprite = new Image(Assets.Sprites.ITEMS);
                    sprite.frame(ItemSpriteSheet.film.get("gold".equals(drop.item())
                        ? ItemSpriteSheet.GOLD : ItemSpriteSheet.RATION));
                    sprite.scale.set(tile / TILE);
                    loot.add(sprite);
                    return sprite;
                });
                image.x = align(mapX + (drop.x() + 0.5f) * tile - image.width() / 2);
                image.y = align(mapY + (drop.y() + 0.75f) * tile - image.height() / 2);
            } else {
                Image image = dropViews.remove(drop.id());
                if (image != null) {
                    image.remove();
                    image.destroy();
                }
                if (prior != null && prior.drops().stream().noneMatch(old ->
                    old.id().equals(drop.id()) && old.pickedBy() != null)) {
                    InnApi.Combatant collector = find(drop.pickedBy());
                    if (collector != null) {
                        String item = "gold".equals(drop.item()) ? "금화 " + drop.amount() : "식량 " + drop.amount();
                        tell(collector.name() + " · " + item + " 획득");
                    }
                }
            }
        }
    }

    private void buildMap(List<String> map, String theme) {
        rows = map.size();
        columns = map.getFirst().length();
        tile = Math.min(36, Math.min((boardWidth - 8) / columns, (boardHeight - 8) / rows));
        mapX = align(boardX + (boardWidth - columns * tile) / 2);
        mapY = align(boardY + (boardHeight - rows * tile) / 2);
        if (tiles != null) {
            tiles.remove();
            tiles.destroy();
        }
        for (WallLip lip : wallLips) {
            lip.remove();
            lip.destroy();
        }
        wallLips.clear();
        String texture = DungeonLayout.PRISON.equals(theme) ? Assets.Environment.TILES_PRISON : Assets.Environment.TILES_SEWERS;
        TextureFilm atlas = new TextureFilm(texture, TILE, TILE);
        tiles = new Tilemap(texture, atlas);
        int[] indices = new int[columns * rows];
        for (int y = 0; y < rows; y++) {
            String row = map.get(y);
            for (int x = 0; x < columns; x++) {
                boolean wall = row.charAt(x) == '#';
                int variation = Math.floorMod(x * 31 + y * 17, 9);
                indices[y * columns + x] = wall
                    ? variation == 0 ? DungeonTileSheet.FLAT_WALL_DECO : DungeonTileSheet.FLAT_WALL
                    : variation == 0 ? DungeonTileSheet.FLOOR_ALT_1 : DungeonTileSheet.FLOOR;
                if (wall && y > 0 && map.get(y - 1).charAt(x) != '#') {
                    int overhang = DungeonTileSheet.WALL_OVERHANG;
                    if (x + 1 < columns && row.charAt(x + 1) != '#') overhang++;
                    if (x > 0 && row.charAt(x - 1) != '#') overhang += 2;
                    WallLip lip = new WallLip(texture, atlas.get(overhang), y);
                    lip.scale.set(tile / TILE);
                    lip.x = mapX + x * tile;
                    lip.y = mapY + y * tile - lip.height();
                    wallLips.add(lip);
                    world.add(lip);
                }
            }
        }
        tiles.map(indices, columns);
        tiles.scale.set(tile / TILE);
        tiles.x = mapX;
        tiles.y = mapY;
        add(tiles);
        // Re-append the overlay layers so tile rebuilds do not cover actors or input feedback.
        moveLayerUp(selectedTile);
        moveLayerUp(queuedTile);
        moveLayerUp(aimTile);
        moveLayerUp(loot);
        moveLayerUp(world);
        moveLayerUp(projectiles);
        moveLayerUp(pauseShade);
        moveLayerUp(actorOverlays);
        moveLayerUp(impacts);
        moveLayerUp(pauseCard);
        moveLayerUp(fireLabel);
        moveLayerUp(notice);
        moveLayerUp(waitingCard);
        moveLayerUp(help);
    }

    private void moveLayerUp(Gizmo layer) {
        remove(layer);
        addToFront(layer);
    }

    private boolean canAct() {
        return !leaving && panelWindow == null && !paused() && activePlayer();
    }

    private boolean activePlayer() { return self != null && self.hp() > 0 && battleOngoing(); }

    private boolean battleOngoing() {
        return state != null && !state.waitingForPartner() && ("active".equals(state.status()) || "won".equals(state.status()));
    }

    private boolean canAttack() {
        return canAct() && "active".equals(state.status());
    }

    private boolean paused() { return state != null && state.pausedUntil() > 0; }

    private boolean canTogglePause(long wallNow) {
        return !leaving && panelWindow == null && self != null && battleOngoing() &&
            (paused() || wallNow >= state.pauseReadyAt());
    }

    private void togglePause() {
        if (canTogglePause(NativeClient.dungeonWallNow())) NativeClient.toggleDungeonPause();
    }

    private void updatePauseControls() {
        long wallNow = NativeClient.dungeonWallNow();
        boolean paused = paused();
        pauseShade.visible = pauseCard.visible = paused;
        String remaining = paused ? countdown(state.pausedUntil() - wallNow) : "";
        if (paused) setText(pauseCountdown, "자동 재개 " + remaining);
        long reuse = state == null ? 0 : state.pauseReadyAt() - wallNow;
        String caption = paused ? (panelWindow == null ? "재개 · P" : "정지 중 " + remaining)
            : reuse > 0 ? "정지 " + countdown(reuse) : "전술 정지 · P";
        if (!caption.equals(pauseButton.text())) pauseButton.text(caption);
        pauseButton.enable(canTogglePause(wallNow));
    }

    private static String countdown(long millis) {
        long seconds = Math.ceilDiv(Math.max(0, millis), 1_000);
        long remainder = seconds % 60;
        return (seconds / 60) + ":" + (remainder < 10 ? "0" : "") + remainder;
    }

    private long readyAt(String action) {
        return BattleRules.readyAt(self, action, state.now(), state.inCombat());
    }

    private void updateActionButtons(long now) {
        boolean able = canAct();
        boolean attackEnabled = canAttack();
        if (!attackEnabled) aiming = false;
        long attackWait = attackEnabled ? Math.max(0, self.attackReadyAt() - now) : 0;
        String attackLabel = hitButton.width() < 43 ? "평타" : "평타 · Space";
        if (attackWait > 0) {
            long tenths = (attackWait + 99) / 100;
            attackLabel = "평타 " + tenths / 10 + "." + tenths % 10 + "초";
        } else if (attackEnabled && readyAt("attack") > now) {
            attackLabel = "평타 · 행동력 1";
        }
        if (!attackLabel.equals(hitButton.text())) hitButton.text(attackLabel);
        hitButton.enable(attackEnabled);
        hitButton.textColor(attackEnabled && readyAt("attack") <= now ? TEXT : MUTED);

        String shootLabel = aiming ? "조준 해제" : "활 · R";
        if (!shootLabel.equals(shootButton.text())) shootButton.text(shootLabel);
        shootButton.enable(attackEnabled);
        shootButton.textColor(attackEnabled && readyAt("shoot") <= now ? TEXT : MUTED);

        int rations = self == null ? 0 : self.rations();
        String rationLabel = "식량 " + rations + " · E";
        if (!rationLabel.equals(rationButton.text())) rationButton.text(rationLabel);
        boolean rationEnabled = able && rations > 0 && self.hp() < self.maxHp();
        rationButton.enable(rationEnabled);
        rationButton.textColor(rationEnabled && readyAt("eat") <= now ? TEXT : MUTED);
    }

    private boolean passable(int x, int y) {
        return mapCell(x, y) && state.map().get(y).charAt(x) != '#';
    }

    private boolean mapCell(int x, int y) { return x >= 0 && y >= 0 && x < columns && y < rows; }

    private boolean mayStep(Step step, boolean ready) {
        int x = self.x() + step.dx();
        int y = self.y() + step.dy();
        if (!passable(x, y) || step.dx() != 0 && step.dy() != 0 &&
            (!passable(x, self.y()) && !passable(self.x(), y))) return false;
        // The party host checks the destination again when a queued move is executed.
        return !ready || state.actors().stream().noneMatch(actor -> actor.hp() > 0 && actor.x() == x && actor.y() == y);
    }

    private void move(Step step, boolean fresh, long now) {
        if (!canAct()) return;
        long availableAt = readyAt("move");
        boolean ready = availableAt <= now;
        InnApi.Action queued = state.queued();
        long inputAt = System.nanoTime();
        if (awaitingAcknowledgement(inputAt) && !"move".equals(submitted.action().type()) &&
            inputAt - submitted.sentAt() < MOVE_RETRY_NS) return;
        if (!fresh && (awaitingAcknowledgement(inputAt) || availableAt > now + MOVE_QUEUE_LEAD_MS ||
            queued != null && ("move".equals(queued.type()) || !ready))) return;
        if (step.equals(lastMove) && inputAt - lastMoveAt < MOVE_RETRY_NS) return;
        if (!mayStep(step, ready)) return;
        InnApi.Action action = new InnApi.Action("move", step.dx(), step.dy(), null);
        if (!beginRequest(action, inputAt)) return;
        lastMove = step;
        lastMoveAt = inputAt;
        if (ready && (queued == null || !"move".equals(queued.type()))) {
            actorViews.get(self.id()).previewMove(step.dx(), step.dy(), fresh);
        }
        NativeClient.dungeonMove(step.dx(), step.dy());
    }

    private void previewQueuedMove(long now) {
        if (!canAct() || state.queued() == null || !"move".equals(state.queued().type()) || readyAt("move") > now) return;
        Step step = new Step(state.queued().dx(), state.queued().dy());
        if (mayStep(step, true)) actorViews.get(self.id()).previewMove(step.dx(), step.dy(), false);
    }

    private void onTile(int x, int y) {
        if (!canAct() || !mapCell(x, y)) return;
        if (aiming) {
            shoot(x, y);
            return;
        }
        if (!passable(x, y)) return;
        for (InnApi.Combatant actor : state.actors()) {
            if (canAttack() && actor.hp() > 0 && actor.x() == x && actor.y() == y &&
                !self.id().equals(actor.id()) && (enemy(actor) || state.friendlyFire())) {
                selected = actor.id();
                attackTarget(actor);
                updateMarkers();
                return;
            }
        }
        int dx = x - self.x();
        int dy = y - self.y();
        if ((dx != 0 || dy != 0) && Math.abs(dx) <= 1 && Math.abs(dy) <= 1) {
            move(new Step(dx, dy), true, NativeClient.dungeonNow());
        }
    }

    private void attack() {
        if (!canAttack()) return;
        aiming = false;
        InnApi.Combatant target = find(selected);
        if (!inReach(target)) {
            target = state.actors().stream().filter(this::inReach).findFirst().orElse(null);
        }
        if (target != null) {
            selected = target.id();
            attackTarget(target);
            updateMarkers();
        } else {
            tell("가까운 적에게 평타를 쓸 수 있습니다.");
        }
    }

    private void attackTarget(InnApi.Combatant target) {
        if (!beginRequest(new InnApi.Action("attack", 0, 0, target.id()), System.nanoTime())) return;
        NativeClient.dungeonAttack(target.id());
    }

    private void toggleAim() {
        if (!canAttack()) return;
        aiming = !aiming;
        if (aiming) tell("활 조준 · 쏠 칸을 선택하세요. 사거리 5칸 · 행동력 2");
    }

    private boolean withinShotRange(int x, int y) {
        int distance = Math.max(Math.abs(x - self.x()), Math.abs(y - self.y()));
        return distance > 0 && distance <= BattleRules.SHOOT_RANGE;
    }

    private void shoot(int x, int y) {
        if (!canAttack()) return;
        if (!withinShotRange(x, y)) {
            tell("화살 사거리 5칸");
            return;
        }
        if (beginRequest(new InnApi.Action("shoot", 0, 0, x + "," + y), System.nanoTime())) {
            aiming = false;
            NativeClient.dungeonShoot(x, y);
        }
    }

    private void updateAimMarker() {
        if (!aiming || !canAttack()) {
            aimTile.visible = false;
            return;
        }
        PointF hover = PointerEvent.currentHoverPos();
        PointF point = Camera.main.screenToCamera((int) hover.x, (int) hover.y);
        int x = (int) Math.floor((point.x - mapX) / tile);
        int y = (int) Math.floor((point.y - mapY) / tile);
        marker(aimTile, mapCell(x, y) && withinShotRange(x, y) ? x : null, y);
    }

    private void showAttackResult(InnApi.BattleEvent event, long now) {
        if (now - event.at() >= 900 || !mapCell(event.x(), event.y())) return;
        boolean hit = "hit".equals(event.result());
        if (!hit && !state.selfId().equals(event.actorId()) && !"shoot".equals(event.action())) return;
        String caption = switch (event.result()) {
            case "hit" -> "-" + event.amount();
            case "out_of_reach" -> "사거리 벗어남";
            case "blocked" -> "벽에 막힘";
            case "target_gone" -> "대상 쓰러짐";
            case "ally_blocked" -> "아군에 막힘";
            default -> "빗나감";
        };
        if (!hit && state.selfId().equals(event.actorId())) tell(caption);
        RenderedTextBlock text = label(impacts, caption, 7, hit ? ENEMY : SELF, 0, 0, 58);
        impactLabels.add(new ImpactLabel(text, event));
    }

    private void updateRangedVisuals(long now) {
        if (state == null) return;
        projectileViews.entrySet().removeIf(entry -> {
            if (state.projectiles().stream().anyMatch(value -> value.id() == entry.getKey())) return false;
            entry.getValue().remove();
            entry.getValue().destroy();
            return true;
        });
        for (InnApi.Projectile projectile : state.projectiles()) {
            ColorBlock dart = projectileViews.computeIfAbsent(projectile.id(), id ->
                panel(projectiles, 0, 0, Math.max(3, tile * .54f), 1, 0xFFF3CA77));
            int dx = projectile.nextX() - projectile.x();
            int dy = projectile.nextY() - projectile.y();
            float progress = 1f - Math.clamp((projectile.stepAt() - now) / (float) BattleRules.PROJECTILE_STEP_MS, 0, 1);
            dart.origin.set(dart.width() / 2, .5f);
            dart.x = mapX + (projectile.x() + .5f + dx * progress) * tile - dart.origin.x;
            dart.y = mapY + (projectile.y() + .48f + dy * progress) * tile;
            dart.angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        }
        impactLabels.removeIf(impact -> {
            long age = Math.max(0, now - impact.event().at());
            RenderedTextBlock text = impact.text();
            if (age >= 900) {
                text.remove();
                text.destroy();
                return true;
            }
            float x = mapX + (impact.event().x() + .5f) * tile - text.width() / 2;
            float y = mapY + impact.event().y() * tile - 2 - age / 120f;
            text.setPos(align(Math.clamp(x, boardX + 2, boardX + boardWidth - 2 - text.width())),
                align(Math.max(boardY + 3, y)));
            text.alpha(Math.clamp((900 - age) / 250f, 0, 1));
            return false;
        });
    }

    private void eat() {
        if (!canAct()) return;
        if (self.rations() == 0) {
            tell("보유한 식량이 없습니다.");
        } else if (self.hp() == self.maxHp()) {
            tell("체력이 이미 가득 찼습니다.");
        } else if (beginRequest(new InnApi.Action("eat", 0, 0, null), System.nanoTime())) {
            NativeClient.dungeonEat();
        }
    }

    private boolean awaitingAcknowledgement(long now) {
        return submitted != null && now - submitted.sentAt() < COMMAND_ACK_WAIT_NS;
    }

    private boolean beginRequest(InnApi.Action action, long now) {
        if (action.equals(state.queued()) || awaitingAcknowledgement(now) && action.equals(submitted.action())) return false;
        submitted = new SubmittedAction(action, state.commandAck(), now);
        return true;
    }

    private boolean inReach(InnApi.Combatant actor) {
        if (actor == null || !enemy(actor) || actor.hp() == 0) return false;
        int dx = actor.x() - self.x();
        int dy = actor.y() - self.y();
        if (Math.max(Math.abs(dx), Math.abs(dy)) != 1) return false;
        return dx == 0 || dy == 0 || (passable(actor.x(), self.y()) && passable(self.x(), actor.y()));
    }

    private void updateMarkers() {
        if (!activePlayer()) {
            selectedTile.visible = queuedTile.visible = false;
            return;
        }
        InnApi.Combatant target = find(selected);
        marker(selectedTile, target == null || target.hp() == 0 ? null : target.x(), target == null ? 0 : target.y());
        InnApi.Action queued = state.queued();
        if (queued == null || self == null) {
            queuedTile.visible = false;
        } else if ("move".equals(queued.type())) {
            marker(queuedTile, self.x() + queued.dx(), self.y() + queued.dy());
        } else if ("attack".equals(queued.type())) {
            InnApi.Combatant victim = find(queued.target());
            marker(queuedTile, victim == null ? null : victim.x(), victim == null ? 0 : victim.y());
        } else if ("shoot".equals(queued.type()) && queued.target() != null && queued.target().matches("\\d{1,3},\\d{1,3}")) {
            String[] cell = queued.target().split(",");
            int x = Integer.parseInt(cell[0]);
            int y = Integer.parseInt(cell[1]);
            marker(queuedTile, mapCell(x, y) ? x : null, y);
        } else {
            queuedTile.visible = false;
        }
    }

    private void marker(ColorBlock marker, Integer x, int y) {
        marker.visible = x != null;
        if (x != null) {
            marker.x = mapX + x * tile + 1;
            marker.y = mapY + y * tile + 1;
            marker.size(tile - 2, tile - 2);
        }
    }

    private void updateInput(long now) {
        if (!canAct()) {
            movement.reset();
            return;
        }
        movement.sample(Gdx.input::isKeyPressed);
        KeyboardMovement.Input input = movement.next(16);
        if (input != null) move(new Step(input.dx(), input.dy()), input.fresh(), now);
    }

    private boolean onKey(KeyEvent event) {
        return panelWindow == null && (onHotkey(event.code, event.pressed) || !paused() && movement.onKey(event.code, event.pressed));
    }

    private boolean onHotkey(int key, boolean pressed) {
        if (key == Input.Keys.F1) {
            if (pressed && !helpHeld && panelWindow == null) toggleHelp();
            helpHeld = pressed;
            return true;
        }
        if (key == Input.Keys.SPACE) {
            if (pressed && !attackHeld) attack();
            attackHeld = pressed;
            return true;
        }
        if (key == Input.Keys.R) {
            if (pressed && !shootHeld) toggleAim();
            shootHeld = pressed;
            return true;
        }
        if (key == Input.Keys.E) {
            if (pressed && !rationHeld) eat();
            rationHeld = pressed;
            return true;
        }
        if (key == Input.Keys.P) {
            if (pressed && !pauseHeld) togglePause();
            pauseHeld = pressed;
            return true;
        }
        return false;
    }

    private void tell(String text) {
        localNotice = text;
        noticeUntil = System.nanoTime() + 1_800_000_000L;
    }

    @Override
    public void onPause() {
        movement.reset();
        helpHeld = attackHeld = shootHeld = rationHeld = pauseHeld = aiming = false;
        super.onPause();
    }

    private void toggleHelp() {
        help.visible = !help.visible;
        helpButton.text(help.visible ? "F1 접기" : helpButton.width() < 40 ? "F1 도움" : "F1 조작법");
    }

    private void showPanel(Supplier<? extends Window> open) {
        if (state == null || leaving || panelWindow != null) return;
        movement.reset();
        panelWindow = open.get();
        add(panelWindow);
    }

    private void leave() {
        if (leaving) return;
        leaving = true;
        NativeClient.returnToInn();
    }

    @Override
    protected void onBackPressed() {
        if (panelWindow != null) panelWindow.hide();
        else if (aiming) aiming = false;
        else leave();
    }

    @Override
    public void destroy() {
        if (keys != null) KeyEvent.removeKeyListener(keys);
        super.destroy();
    }

    private void showWaiting(String text) {
        waitingCard.visible = !text.isEmpty();
        setText(waitingLabel, text);
    }

    private InnApi.Combatant find(String id) {
        if (id == null || state == null) return null;
        for (InnApi.Combatant actor : state.actors()) if (id.equals(actor.id())) return actor;
        return null;
    }

    private static boolean enemy(InnApi.Combatant actor) {
        return "enemy".equals(actor.kind());
    }

    private static String directionName(int dx, int dy) {
        String vertical = dy < 0 ? "위" : dy > 0 ? "아래" : "";
        String horizontal = dx < 0 ? "왼쪽" : dx > 0 ? "오른쪽" : "";
        return vertical.isEmpty() || horizontal.isEmpty() ? vertical + horizontal : vertical + " " + horizontal;
    }

    static ColorBlock panel(Group group, float x, float y, float width, float height, int color) {
        ColorBlock block = new ColorBlock(width, height, color);
        block.x = x;
        block.y = y;
        group.add(block);
        return block;
    }

    static RenderedTextBlock label(Group group, String text, int size, int color, float x, float y, float width) {
        RenderedTextBlock label = renderTextBlock(size);
        label.setHightlighting(false);
        label.maxWidth(Math.max(1, (int) width));
        label.hardlight(color);
        setText(label, text);
        label.setPos(align(x), align(y));
        group.add(label);
        return label;
    }

    private static void setText(RenderedTextBlock label, String text) {
        label.visible = text != null && !text.isBlank();
        if (label.visible && !text.equals(label.text())) label.text(text);
    }

    private static StyledButton button(Group group, String label, Runnable onClick) {
        StyledButton button = new StyledButton(Chrome.Type.GREY_BUTTON_TR, label, 8) {
            @Override
            protected void onClick() {
                onClick.run();
            }
        };
        group.add(button);
        return button;
    }

    private class DungeonWindow extends Window {
        DungeonWindow(String title, int width, int height) {
            super(width, height);
            label(this, title, 9, SELF, 5, 5, width - 40);
            button(this, "닫기", this::hide).setRect(width - 34, 2, 30, 15);
        }

        @Override
        public void destroy() {
            if (panelWindow == this) panelWindow = null;
            movement.reset();
            super.destroy();
        }
    }

    private final class BackpackWindow extends DungeonWindow {
        private final StyledButton[] slots = new StyledButton[2];
        private final RenderedTextBlock instructions;
        private int selectedSlot = -1;

        BackpackWindow() {
            super("가방", 148, 69);
            instructions = label(this, " ", 7, MUTED, 5, 22, 138);
            for (int index = 0; index < slots.length; index++) {
                int slot = index;
                slots[index] = button(this, "", () -> select(slot));
                slots[index].setRect(5 + index * 71, 38, 67, 23);
            }
            refresh();
        }

        @Override
        public void update() {
            refresh();
            super.update();
        }

        private void select(int slot) {
            if (paused()) return;
            if (selectedSlot < 0) {
                selectedSlot = slot;
            } else {
                if (selectedSlot != slot) rationFirst = !rationFirst;
                selectedSlot = -1;
            }
            refresh();
        }

        private void refresh() {
            boolean readOnly = paused();
            if (readOnly) selectedSlot = -1;
            setText(instructions, readOnly ? "전술 정지 · 열람만 가능" : "두 칸을 선택해 위치를 바꿉니다.");
            int gold = self == null ? 0 : state.drops().stream().filter(drop ->
                "gold".equals(drop.item()) && self.id().equals(drop.pickedBy())).mapToInt(InnApi.Drop::amount).sum();
            String ration = "식량  " + (self == null ? 0 : self.rations());
            String coins = "금화  " + gold;
            for (int index = 0; index < slots.length; index++) {
                StyledButton slot = slots[index];
                slot.active = !readOnly;
                String text = ((index == 0) == rationFirst) ? ration : coins;
                if (!text.equals(slot.text())) slot.text(text);
                slot.textColor(index == selectedSlot ? SELF : TEXT);
            }
        }
    }

    private final class StatusWindow extends DungeonWindow {
        private final RenderedTextBlock[] names = new RenderedTextBlock[2];
        private final RenderedTextBlock[] balances = new RenderedTextBlock[2];
        private final RenderedTextBlock[] cooldowns = new RenderedTextBlock[2];

        StatusWindow() {
            super("파티 상태", 153, 100);
            for (int index = 0; index < names.length; index++) {
                int y = 23 + index * 35;
                names[index] = label(this, " ", 8, index == 0 ? SELF : ALLY, 6, y, 141);
                balances[index] = label(this, " ", 7, TEXT, 6, y + 11, 141);
                cooldowns[index] = label(this, " ", 7, MUTED, 6, y + 21, 141);
            }
            refresh();
        }

        @Override
        public void update() {
            refresh();
            super.update();
        }

        private void refresh() {
            long now = NativeClient.dungeonNow();
            for (int index = 0; index < names.length; index++) {
                InnApi.Combatant actor = state == null ? null : party[index].actor;
                setText(names[index], actor == null ? "" : partyName(actor, index == 0));
                if (actor == null) {
                    setText(balances[index], "");
                    setText(cooldowns[index], "");
                    continue;
                }
                int energy = (int) (BattleRules.energyAt(actor, state.now(), now) + 1e-8);
                setText(balances[index], "HP " + actor.hp() + "/" + actor.maxHp() +
                    "    ·    행동력 " + energy + "/" + BattleRules.ENERGY_CAP);
                setText(cooldowns[index], actor.hp() == 0 ? "쓰러짐" :
                    "이동 " + actionCooldown(actor, "move", now) + "    ·    평타 " + actionCooldown(actor, "attack", now));
            }
        }

        private String actionCooldown(InnApi.Combatant actor, String type, long now) {
            long remaining = BattleRules.readyAt(actor, type, state.now(), state.inCombat()) - now;
            if (remaining <= 0) return "준비";
            long tenths = Math.ceilDiv(remaining, 100);
            return (tenths / 10) + "." + (tenths % 10) + "초";
        }
    }

    private final class ChatWindow extends DungeonWindow {
        private final ChatRows rows = new ChatRows();
        private final ScrollPane chat = new ScrollPane(rows);
        private final TextInput editor;
        private final StyledButton send;
        private List<InnApi.PartyChat> displayed;

        ChatWindow() {
            super("파티 대화", 184, 112);
            panel(this, 4, 21, 176, 65, 0xFF252A27);
            add(chat);
            chat.setRect(5, 22, 174, 63);
            editor = new TextInput(Chrome.get(Chrome.Type.TOAST_WHITE), false, defaultZoom * 7) {
                @Override
                public void enterPressed() { submitMessage(); }
            };
            editor.setMaxLength(160);
            add(editor);
            editor.setRect(4, 91, 134, 16);
            send = button(this, "보내기", this::submitMessage);
            send.setRect(140, 91, 40, 16);
            refresh();
        }

        @Override
        public void update() {
            refresh();
            super.update();
        }

        private void refresh() {
            List<InnApi.PartyChat> messages = state == null ? List.of() : state.messages();
            if (!messages.equals(displayed)) {
                float scrollY = rows.camera.scroll.y;
                boolean bottom = scrollY + chat.height() >= rows.height() - 5;
                rows.rebuild(messages);
                chat.setSize(chat.width(), chat.height());
                chat.scrollTo(0, bottom ? rows.height() : scrollY);
                displayed = messages;
            }
            send.enable(canChat());
        }

        private boolean canChat() {
            return state != null && !state.waitingForPartner() && !"closed".equals(state.status());
        }

        private void submitMessage() {
            if (!canChat()) return;
            String message = editor.getText().strip();
            if (message.isEmpty()) return;
            NativeClient.dungeonChat(message);
            editor.setText("");
        }
    }

    private static final class ChatRows extends Component {
        void rebuild(List<InnApi.PartyChat> messages) {
            for (Gizmo child : members) if (child != null) child.destroy();
            clear();
            float y = 2;
            if (messages.isEmpty()) {
                y = label(this, "아직 대화가 없습니다.", 7, MUTED, 3, y, 166).bottom() + 2;
            } else {
                for (InnApi.PartyChat message : messages) {
                    String text = message.name() + "  " + message.text();
                    y = label(this, text, 7, TEXT, 3, y, 166).bottom() + 2;
                }
            }
            setSize(170, y);
        }
    }

    private static String partyName(InnApi.Combatant actor, boolean own) {
        String name = actor.name();
        if (name.codePointCount(0, name.length()) > 11) name = name.substring(0, name.offsetByCodePoints(0, 10)) + "…";
        return own ? name + " (나)" : name;
    }

    private final class PartySlot {
        private final Group group = new Group();
        private final RenderedTextBlock name;
        private final RenderedTextBlock health;
        private final RenderedTextBlock energyLabel;
        private final ColorBlock hpBar;
        private final ColorBlock charge;
        private final float width;
        private InnApi.Combatant actor;

        private PartySlot(float x, float y, float width) {
            this.width = width;
            add(group);
            name = label(group, " ", 8, TEXT, x, y, width);
            health = label(group, " ", 7, MUTED, x, y + 9, width);
            energyLabel = label(group, " ", 7, ALLY, x + width / 2 - 2, y + 9, width / 2 + 2);
            panel(group, x, y + 19, width, 3, 0xFF414B41);
            hpBar = panel(group, x, y + 19, width, 3, 0xFFA67F66);
            panel(group, x, y + 23, width, 2, 0xFF414B41);
            charge = panel(group, x, y + 23, width, 2, 0xFF94C9C0);
            group.visible = false;
        }

        private void set(InnApi.Combatant actor, boolean own) {
            this.actor = actor;
            group.visible = actor != null;
            if (actor == null) return;
            name.hardlight(own ? SELF : TEXT);
            setText(name, partyName(actor, own));
            setText(health, "HP " + actor.hp() + "/" + actor.maxHp());
            hpBar.size(width * fraction(actor.hp(), actor.maxHp()), 3);
        }

        private void update(long now) {
            if (actor == null) return;
            boolean active = state != null && ("active".equals(state.status()) || "won".equals(state.status())) &&
                !state.waitingForPartner();
            double energy = active && actor.hp() > 0 ? BattleRules.energyAt(actor, state.now(), now) : 0;
            charge.size(width * (float) (energy / BattleRules.ENERGY_CAP), 2);
            String balance = "행동 " + (int) (energy + 1e-8) + "/" + BattleRules.ENERGY_CAP;
            setText(energyLabel, actor.hp() == 0 ? "쓰러짐" : active ? balance : "대기");
        }
    }

    private static float fraction(long value, long maximum) {
        return Math.max(0, Math.min(1, value / (float) Math.max(1, maximum)));
    }
}
