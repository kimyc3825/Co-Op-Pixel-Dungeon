// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTileSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.InnAtbMeter;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndSettings;
import com.watabou.input.KeyEvent;
import com.watabou.input.PointerEvent;
import com.watabou.input.ScrollEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.PointerArea;
import com.watabou.noosa.ScrollArea;
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
import java.util.Objects;
import java.util.function.Supplier;

public final class NativeDungeonScene extends PixelScene {
    private static final int BACK = 0xFF151918;
    private static final int BOARD = 0xFF202824;
    private static final int EDGE = 0xFF454F43;
    private static final int HUD = 0xE9222924;
    static final int TEXT = 0xF4E5C9;
    static final int MUTED = 0xC0B7A0;
    static final int SELF = 0xD7BD78;
    static final int ALLY = 0x9FD1CA;
    static final int ENEMY = 0xEC9F8A;
    static final String TRAINING_SPRITES = "inn/training.png";

    private static final int TILE = 16;
    private static final int WALL_LIP_HEIGHT = 4;
    private static final long MOVE_QUEUE_LEAD_MS = 70;
    private static final long MOVE_RETRY_NS = 60_000_000L;
    private static final long COMMAND_ACK_WAIT_NS = 550_000_000L;
    private static final long SPEECH_MS = 4_500;
    private static final int CHAT_HISTORY_LIMIT = 80;
    private static final List<String> POTIONS = List.of("violet-potion", "amber-potion");

    private record Step(int dx, int dy) {}
    private record SubmittedAction(InnApi.Action action, long acknowledgement, long sentAt) {}
    private record ImpactLabel(RenderedTextBlock text, InnApi.BattleEvent event) {}
    private record Destination(int x, int y, String targetId) {}

    private static final class WallLip extends Image {
        final int column;
        final int row;

        WallLip(String texture, RectF overhang, int column, int row) {
            super(texture);
            this.column = column;
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
    private final Map<String, Image> toolViews = new HashMap<>();
    private final Map<Long, ColorBlock> projectileViews = new HashMap<>();
    private final List<ImpactLabel> impactLabels = new ArrayList<>();
    private final List<WallLip> wallLips = new ArrayList<>();
    private final List<InnApi.PartyChat> chatHistory = new ArrayList<>();
    private final PartySlot[] party = new PartySlot[2];
    private final KeyboardMovement movement = new KeyboardMovement();
    private InnApi.DungeonState state;
    private InnApi.Combatant self;
    private Signal.Listener<KeyEvent> keys;
    private Group world;
    private Group actorOverlays;
    private Group speechOverlays;
    private Group projectiles;
    private Group impacts;
    private Group loot;
    private Group hud;
    private Group help;
    private Group chatLog;
    private ChatRows chatRows;
    private ScrollPane chatScroll;
    private StyledButton chatPrompt;
    private StyledButton chatSend;
    private TextInput chatEditor;
    private Tilemap tiles;
    private TextureFilm trainingAtlas;
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
    private RenderedTextBlock encounterLabel;
    private ColorBlock encounterBackdrop;
    private ColorBlock encounterStripe;
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
    private float mapTop;
    private float mapBottom;
    private int zoomStep;
    private float zoomWheel;
    private float boardX;
    private float boardY;
    private float boardWidth;
    private float boardHeight;
    private int columns;
    private int rows;
    private SubmittedAction submitted;
    private String waitingAction;
    private Step lastMove;
    private Destination destination;
    private Destination resumable;
    private long lastMoveAt;
    private String localNotice = "";
    private long noticeUntil;
    private long seenAttackEvent;
    private long seenChatId;
    private boolean attackHeld;
    private boolean shootHeld;
    private boolean aiming;
    private boolean rationHeld;
    private boolean pauseHeld;
    private boolean examine;
    private boolean chatPinned;
    private boolean chatDirty = true;
    private boolean chatOpenPending;
    private boolean chatCancelPending;
    private boolean chatSubmitPending;
    private boolean composerEnterReleased;
    private boolean waitForKeyRelease;
    private boolean rationFirst = true;
    private boolean leaving;

    @Override
    public void create() {
        super.create();
        RectF insets = getCommonInsets();
        boardX = 0;
        boardY = 0;
        boardWidth = Camera.main.width;
        boardHeight = Camera.main.height;
        float left = insets.left + 7;
        float top = insets.top + 6;
        float right = boardWidth - insets.right - 7;
        float bottom = boardHeight - insets.bottom - 6;
        float toolbarY = bottom - 21;
        mapTop = top + 43;
        mapBottom = toolbarY - 6;
        panel(this, 0, 0, boardWidth, boardHeight, BACK);
        floorPanel = panel(this, boardX, boardY, boardWidth, boardHeight, BOARD);
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
        pauseShade = panel(this, boardX, boardY, boardWidth, boardHeight, 0x8A757575);
        pauseShade.visible = false;
        actorOverlays = new Group();
        add(actorOverlays);
        impacts = new Group();
        add(impacts);
        pauseCard = new Group();
        add(pauseCard);
        float pauseCardWidth = Math.min(boardWidth - 14, 118);
        float pauseCardX = right - pauseCardWidth;
        float pauseCardY = mapTop;
        panel(pauseCard, pauseCardX, pauseCardY, pauseCardWidth, 30, 0xE9262C29);
        RenderedTextBlock pauseTitle = label(pauseCard, "전술 정지", 9, TEXT, pauseCardX + 5, pauseCardY + 4, pauseCardWidth - 10);
        pauseTitle.align(RenderedTextBlock.CENTER_ALIGN);
        pauseCountdown = label(pauseCard, " ", 7, TEXT, pauseCardX + 5, pauseCardY + 17, pauseCardWidth - 10);
        pauseCountdown.align(RenderedTextBlock.CENTER_ALIGN);
        pauseCard.visible = false;
        speechOverlays = new Group();
        add(speechOverlays);
        waitingCard = new Group();
        add(waitingCard);
        waitingCard.visible = false;
        float waitingWidth = Math.min(boardWidth - 14, 144);
        float waitingX = boardX + (boardWidth - waitingWidth) / 2;
        float waitingY = boardY + boardHeight / 2 - 13;
        panel(waitingCard, waitingX, waitingY, waitingWidth, 26, 0xE9242C27);
        waitingLabel = label(waitingCard, " ", 8, TEXT, waitingX + 4, waitingY + 7, waitingWidth - 8);
        waitingLabel.align(RenderedTextBlock.CENTER_ALIGN);

        add(new ScrollArea(floorPanel) {
            @Override
            protected void onClick(PointerEvent event) {
                PointF point = camera().screenToCamera((int) event.current.x, (int) event.current.y);
                int x = (int) Math.floor((point.x - mapX) / tile);
                int y = (int) Math.floor((point.y - mapY) / tile);
                if (event.button == PointerEvent.RIGHT) examineTile(x, y);
                else if (event.button == PointerEvent.LEFT) onTile(x, y);
            }

            @Override
            protected void onScroll(ScrollEvent event) {
                zoomWheel += event.amount;
                int steps = (int) zoomWheel;
                zoomWheel -= steps;
                if (steps != 0) zoom(-steps);
            }
        });

        hud = new Group();
        add(hud);
        float partyWidth = Math.min(87, (right - left - 120) / 2);
        for (int i = 0; i < party.length; i++) {
            party[i] = new PartySlot(left + i * (partyWidth + 3), top, partyWidth);
        }
        button(hud, "메뉴", this::openGameMenu).setRect(right - 29, top, 29, 17);
        helpButton = button(hud, "F8 도움", this::toggleHelp);
        helpButton.setRect(right - 65, top, 34, 17);
        pauseButton = button(hud, "정지 · P", this::togglePause);
        pauseButton.setRect(right - 117, top, 50, 17);
        floorTitle = label(hud, NativeClient.expeditionThemeTitle(DungeonLayout.SEWERS), 7, SELF, left, top + 30, 56);
        encounterBackdrop = panel(hud, left + 57, top + 27, 47, 14, 0xF0262522);
        encounterStripe = panel(hud, left + 57, top + 27, 2, 14, 0xFFFFFFFF);
        encounterLabel = label(hud, "", 7, MUTED, left + 62, top + 30, 40);
        floorStatus = label(hud, " ", 6, TEXT, left + 109, top + 31, right - left - 165);
        fireLabel = label(hud, " ", 6, MUTED, right - 54, top + 31, 54);

        StyledButton bag = button(hud, "배낭", () -> showPanel(BackpackWindow::new));
        bag.icon(Icons.BACKPACK.get());
        bag.setRect(left, toolbarY, 43, 21);
        button(hud, "대화", this::toggleChatLog).setRect(left + 45, toolbarY, 31, 21);
        float quickWidth = Math.min(51, (right - left - 84) / 3);
        float quickX = right - 3 * quickWidth - 4;
        hitButton = button(hud, "평타 · Q", this::attack);
        hitButton.setRect(quickX, toolbarY, quickWidth, 21);
        hitButton.enable(false);
        shootButton = button(hud, "1 · 활", this::toggleAim);
        shootButton.setRect(quickX + quickWidth + 2, toolbarY, quickWidth, 21);
        shootButton.enable(false);
        rationButton = button(hud, "2 · 식량 0", this::eat);
        rationButton.setRect(right - quickWidth, toolbarY, quickWidth, 21);
        rationButton.enable(false);
        queueLabel = label(hud, "", 6, ALLY, left + 80, toolbarY + 7, Math.max(1, quickX - left - 83));
        notice = label(hud, " ", 7, ENEMY, left, toolbarY - 12, right - left);

        help = new Group();
        add(help);
        float helpCardWidth = Math.min(boardWidth - 12, 165);
        float helpX = left;
        float helpY = mapTop;
        ColorBlock helpBackdrop = panel(help, helpX, helpY, helpCardWidth, 94, 0xE01A201D);
        help.add(new PointerArea(helpBackdrop));
        panel(help, helpX, helpY, helpCardWidth, 1, 0xAF9DB4A6);
        label(help, "이동  클릭 · WASD · 방향키 · 숫자패드", 7, TEXT, helpX + 5, helpY + 4, helpCardWidth - 10);
        label(help, "평타  Q · 대상 클릭", 7, TEXT, helpX + 5, helpY + 15, helpCardWidth - 10);
        label(help, "자동 이동 중지 Space · 줍기 C · 재개 R", 7, TEXT, helpX + 5, helpY + 26, helpCardWidth - 10);
        label(help, "1 활 · 2 식량 · E 살펴보기", 7, TEXT, helpX + 5, helpY + 37, helpCardWidth - 10);
        label(help, "F1/F/I 배낭 · H 상태 · +/- 확대", 7, TEXT, helpX + 5, helpY + 48, helpCardWidth - 10);
        label(help, "전술 정지 / 재개  P", 7, TEXT, helpX + 5, helpY + 59, helpCardWidth - 10);
        label(help, "대화 Enter · 취소 Esc · 도움말 F8", 7, TEXT, helpX + 5, helpY + 70, helpCardWidth - 10);
        label(help, "연습 도구는 해당 칸에 서서 사용", 7, ALLY, helpX + 5, helpY + 81, helpCardWidth - 10);
        help.visible = help.active = false;

        createChatLog();
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
        updateChatInput();
        refreshChatLog();
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
        waitingAction = null;
        if (!typing()) {
            if (panelWindow == null) {
                if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) showPanel(BackpackWindow::new);
                else if (Gdx.input.isKeyJustPressed(Input.Keys.F8)) toggleHelp();
            }
            onHotkey(Input.Keys.Q, Gdx.input.isKeyPressed(Input.Keys.Q));
            onHotkey(Input.Keys.NUM_1, Gdx.input.isKeyPressed(Input.Keys.NUM_1));
            onHotkey(Input.Keys.NUM_2, Gdx.input.isKeyPressed(Input.Keys.NUM_2));
            onHotkey(Input.Keys.P, Gdx.input.isKeyPressed(Input.Keys.P));
            updateInput(now);
        }
        if (self != null) actorViews.get(self.id()).waitForEnergy(waitingForEnergy(now));
        long receipt = System.nanoTime();
        for (DungeonActorView view : actorViews.values()) view.expireSpeech(receipt);
        super.update();
        if (self != null && party[1].actor != null) {
            DungeonActorView ally = actorViews.get(party[1].actor.id());
            if (ally != null) ally.separateNicknameFrom(actorViews.get(self.id()));
        }
        world.sort(WORLD_DEPTH);
    }

    private static double drawDepth(Gizmo value) {
        return value instanceof DungeonActorView actor ? actor.depth() : ((WallLip) value).row;
    }

    private void accept(InnApi.DungeonState next) {
        InnApi.DungeonState prior = state;
        InnApi.Combatant priorSelf = self;
        state = next;
        updateEncounterBadge();
        boolean paused = paused();
        // The actor layer remains visible while its interpolation and sprite animations are stopped.
        world.active = !paused;
        if (paused != (prior != null && prior.pausedUntil() > 0)) {
            movement.reset();
            submitted = null;
            aiming = false;
            lastMove = null;
            if (paused) {
                interruptRoute();
                actorViews.values().forEach(DungeonActorView::cancelPreviewMove);
            }
        }
        if (next == null) {
            self = null;
            submitted = null;
            if (panelWindow != null) panelWindow.hide();
            for (PartySlot slot : party) slot.set(null, false);
            showWaiting("던전을 불러오는 중…");
            return;
        }
        if (prior == null || !next.id().equals(prior.id())) {
            chatHistory.clear();
            seenChatId = 0;
            chatDirty = true;
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
            clearImages(dropViews);
            clearImages(toolViews);
            selected = null;
            submitted = null;
            lastMove = null;
            destination = resumable = null;
        }
        self = find(next.selfId());
        if (destination != null && (self == null || self.hp() == 0 || priorSelf != null && self.hp() < priorSelf.hp()
            || destination.targetId() == null && prior != null && !prior.inCombat() && next.inCombat())) {
            interruptRoute();
        }
        long now = NativeClient.dungeonNow();
        String hostId = next.actors().getFirst().id();
        for (InnApi.Combatant actor : next.actors()) {
            DungeonActorView view = actorViews.get(actor.id());
            if (view == null) {
                view = new DungeonActorView(actor, actor.id().equals(next.selfId()), actor.id().equals(hostId));
                actorViews.put(actor.id(), view);
                world.add(view);
                actorOverlays.add(view.overlay());
                if ("player".equals(actor.kind())) speechOverlays.add(view.speechOverlay());
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
        placeMap();
        acceptChat(next);
        if (submitted != null && next.commandAck() > submitted.acknowledgement()) {
            if (self != null && priorSelf != null && "move".equals(submitted.action().type()) &&
                self.x() == priorSelf.x() && self.y() == priorSelf.y() && !submitted.action().equals(next.queued())) {
                actorViews.get(self.id()).cancelPreviewMove();
            }
            submitted = null;
        }
        showTrainingTools(next, priorSelf);
        showLoot(next, prior);
        for (InnApi.BattleEvent event : next.attackEvents()) {
            if (event.id() <= seenAttackEvent) continue;
            seenAttackEvent = event.id();
            showAttackResult(event, now);
        }
        boolean training = !next.trainingTools().isEmpty();
        setText(fireLabel, training ? "도구 칸 밟기" : !isSoloAtb() && next.friendlyFire() ? "아군 피해 켜짐" : "");
        fireLabel.hardlight(training ? ALLY : next.friendlyFire() ? ENEMY : MUTED);

        int index = 0;
        if (self != null) party[index++].set(self, true);
        for (InnApi.Combatant actor : next.actors()) {
            if (index == party.length) break;
            if (!actor.id().equals(next.selfId()) && ("player".equals(actor.kind()) || "guide".equals(actor.kind()))) {
                party[index++].set(actor, false);
            }
        }
        while (index < party.length) party[index++].set(null, false);
        long enemies = next.actors().stream().filter(actor -> enemy(actor) && actor.hp() > 0).count();
        boolean practice = next.actors().stream().anyMatch(actor -> "guide".equals(actor.kind()));
        boolean solo = isSoloAtb();
        floorSummary = (practice ? "길잡이 훈련" : solo ? "1인 ATB 원정" : "2인 원정") +
            ("won".equals(next.status()) ? "   ·   소탕 완료" :
            training && enemies == 0 ? "   ·   허수아비 훈련" : "   ·   남은 적 " + enemies);
        if (!"active".equals(next.status()) && !"won".equals(next.status())) {
            showWaiting(switch (next.status()) {
                case "lost" -> solo ? "쓰러졌습니다" : "파티가 쓰러졌습니다";
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
        setText(queueLabel, queued == null ? "" : switch (queued.type()) {
            case "move" -> "예약  " + directionName(queued.dx(), queued.dy()) + " 이동";
            case "eat" -> "예약  식량";
            case "shoot" -> "예약  화살";
            case "use-item" -> "예약  " + itemName(queued.target());
            default -> "예약  평타";
        });
        updateMarkers();
    }

    private static void clearImages(Map<String, Image> images) {
        for (Image image : images.values()) {
            image.remove();
            image.destroy();
        }
        images.clear();
    }

    private void updateEncounterBadge() {
        boolean visible = state != null && !state.waitingForPartner() && "active".equals(state.status());
        encounterBackdrop.visible = encounterStripe.visible = visible;
        if (!visible) {
            setText(encounterLabel, "");
            return;
        }
        String encounter = state.encounter();
        int color = switch (encounter) {
            case "alert" -> 0xF4D28F;
            case "combat" -> 0xF3A699;
            case "pursuit" -> 0xF3B580;
            default -> 0xC4CFB8;
        };
        String name = switch (encounter) {
            case "alert" -> "적 경계";
            case "combat" -> "교전";
            case "pursuit" -> "추격";
            default -> "탐색";
        };
        encounterStripe.hardlight(color);
        encounterLabel.hardlight(color);
        setText(encounterLabel, name);
    }

    private void showTrainingTools(InnApi.DungeonState next, InnApi.Combatant priorSelf) {
        boolean moved = priorSelf != null && self != null && (priorSelf.x() != self.x() || priorSelf.y() != self.y());
        for (InnApi.TrainingTool tool : next.trainingTools()) {
            int frame = switch (tool.id()) {
                case "ration-rack" -> 1;
                case "recovery-kit" -> 2;
                default -> -1;
            };
            if (frame < 0) continue;
            Image sprite = toolViews.computeIfAbsent(tool.id(), id -> {
                if (trainingAtlas == null) trainingAtlas = new TextureFilm(TRAINING_SPRITES, TILE, TILE);
                Image image = new Image(TRAINING_SPRITES);
                image.frame(trainingAtlas.get(frame));
                loot.add(image);
                return image;
            });
            sprite.scale.set(tile / TILE);
            sprite.x = align(mapX + (tool.x() + 0.5f) * tile - sprite.width() / 2);
            sprite.y = align(mapY + (tool.y() + 0.5f) * tile - sprite.height() / 2);
            if (moved && tool.x() == self.x() && tool.y() == self.y()) {
                tell(frame == 1 ? "식량 상자 · 식량 " + self.rations() + "개" : "회복 도구 · 체력과 행동력 회복");
            }
        }
    }

    private void showLoot(InnApi.DungeonState next, InnApi.DungeonState prior) {
        for (InnApi.Drop drop : next.drops()) {
            if (drop.pickedBy() == null) {
                Image image = dropViews.computeIfAbsent(drop.id(), id -> {
                    Image sprite = itemIcon(drop.item());
                    sprite.scale.set(tile / TILE);
                    loot.add(sprite);
                    return sprite;
                });
                image.scale.set(tile / TILE);
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
                        tell(collector.name() + " · " + itemName(drop.item()) + " " + drop.amount() + " 획득");
                    }
                }
            }
        }
    }

    private void buildMap(List<String> map, String theme) {
        rows = map.size();
        columns = map.getFirst().length();
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
                    WallLip lip = new WallLip(texture, atlas.get(overhang), x, y);
                    wallLips.add(lip);
                    world.add(lip);
                }
            }
        }
        tiles.map(indices, columns);
        add(tiles);
        placeMap();
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
        moveLayerUp(speechOverlays);
        moveLayerUp(waitingCard);
        moveLayerUp(hud);
        moveLayerUp(help);
        moveLayerUp(chatLog);
    }

    private void placeMap() {
        if (tiles == null) return;
        float fit = Math.max(TILE, Math.min((boardWidth - 12) / columns, (mapBottom - mapTop) / rows));
        float scale = (float) Math.pow(1.25, zoomStep);
        float size = Math.min(48, fit * scale);
        float focusX = self == null ? columns / 2f : self.x() + .5f;
        float focusY = self == null ? rows / 2f : self.y() + .5f;
        float x = align(mapOrigin(6, boardWidth - 6, columns * size, focusX * size));
        float y = align(mapOrigin(mapTop, mapBottom, rows * size, focusY * size));
        mapX = x;
        mapY = y;
        tile = size;
        tiles.scale.set(tile / TILE);
        tiles.x = mapX;
        tiles.y = mapY;
        for (WallLip lip : wallLips) {
            lip.scale.set(tile / TILE);
            lip.x = mapX + lip.column * tile;
            lip.y = mapY + lip.row * tile - lip.height();
        }
        for (DungeonActorView view : actorViews.values()) view.place(mapX, mapY, tile);
    }

    private static float mapOrigin(float start, float end, float extent, float focus) {
        float center = (start + end) / 2;
        if (extent <= end - start) return center - extent / 2;
        return Math.clamp(center - focus, end - extent, start);
    }

    private void zoom(int steps) {
        int next = Math.clamp(zoomStep + steps, -2, 3);
        if (panelWindow != null || chatEditor != null || next == zoomStep) return;
        zoomStep = next;
        placeMap();
        if (state != null && self != null) {
            showTrainingTools(state, self);
            showLoot(state, state);
        }
        updateMarkers();
    }

    private void moveLayerUp(Gizmo layer) {
        remove(layer);
        addToFront(layer);
    }

    private boolean canAct() {
        return !leaving && panelWindow == null && chatEditor == null && !paused() && activePlayer();
    }

    private boolean activePlayer() { return self != null && self.hp() > 0 && battleOngoing(); }

    private boolean isSoloAtb() {
        return state != null && !state.waitingForPartner() && state.actors().stream()
            .filter(actor -> "player".equals(actor.kind()) || "guide".equals(actor.kind())).count() == 1;
    }

    private boolean battleOngoing() {
        return state != null && !state.waitingForPartner() && ("active".equals(state.status()) || "won".equals(state.status()));
    }

    private boolean canAttack() {
        return canAct() && "active".equals(state.status());
    }

    private boolean paused() { return state != null && state.pausedUntil() > 0; }

    private boolean canTogglePause(long wallNow) {
        return !leaving && panelWindow == null && chatEditor == null && self != null && battleOngoing() &&
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
        String attackLabel = "평타 · Q";
        if (attackWait > 0) {
            long tenths = (attackWait + 99) / 100;
            attackLabel = "평타 " + tenths / 10 + "." + tenths % 10 + "초";
        } else if (attackEnabled && readyAt("attack") > now) {
            attackLabel = "평타 · 행동력 1";
        }
        if (!attackLabel.equals(hitButton.text())) hitButton.text(attackLabel);
        hitButton.enable(attackEnabled);
        hitButton.textColor(attackEnabled && readyAt("attack") <= now ? TEXT : MUTED);

        String shootLabel = aiming ? "조준 해제" : "1 · 활";
        if (!shootLabel.equals(shootButton.text())) shootButton.text(shootLabel);
        shootButton.enable(attackEnabled);
        shootButton.textColor(attackEnabled && readyAt("shoot") <= now ? TEXT : MUTED);

        int rations = self == null ? 0 : self.rations();
        String rationLabel = "2 · 식량 " + rations;
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
        if (!ready && mayStep(step, true)) waitingAction = "move";
        if (awaitingAcknowledgement(inputAt) && !"move".equals(submitted.action().type()) &&
            inputAt - submitted.sentAt() < MOVE_RETRY_NS) return;
        if (!fresh && (awaitingAcknowledgement(inputAt) || availableAt > now + MOVE_QUEUE_LEAD_MS ||
            queued != null && ("move".equals(queued.type()) || !ready))) return;
        if (!fresh && step.equals(lastMove) && inputAt - lastMoveAt < MOVE_RETRY_NS) return;
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

    private boolean waitingForEnergy(long now) {
        if (!canAct()) return false;
        InnApi.Action action = state.queued();
        if (action == null && awaitingAcknowledgement(System.nanoTime())) action = submitted.action();
        String type = action == null ? waitingAction : action.type();
        return type != null && BattleRules.lacksEnergy(self, type, state.now(), now, state.inCombat());
    }

    private void previewQueuedMove(long now) {
        if (!canAct() || state.queued() == null || !"move".equals(state.queued().type()) || readyAt("move") > now) return;
        Step step = new Step(state.queued().dx(), state.queued().dy());
        if (mayStep(step, true)) actorViews.get(self.id()).previewMove(step.dx(), step.dy(), false);
    }

    private void onTile(int x, int y) {
        if (!mapCell(x, y)) return;
        if (examine) {
            examineTile(x, y);
            return;
        }
        if (!canAct()) return;
        if (aiming) {
            shoot(x, y);
            return;
        }
        if (!passable(x, y)) return;
        for (InnApi.Combatant actor : state.actors()) {
            if (canAttack() && actor.hp() > 0 && actor.x() == x && actor.y() == y &&
                !self.id().equals(actor.id()) && (isCombatTarget(actor) || state.friendlyFire())) {
                navigateTo(new Destination(x, y, actor.id()));
                return;
            }
        }
        if (x == self.x() && y == self.y()) interruptRoute();
        else navigateTo(new Destination(x, y, null));
    }

    private void navigateTo(Destination next) {
        destination = next;
        resumable = null;
        selected = next.targetId();
        updateMarkers();
        advanceRoute(NativeClient.dungeonNow());
    }

    private void advanceRoute(long now) {
        if (destination == null || !canAct() || awaitingAcknowledgement(System.nanoTime())) return;
        InnApi.Combatant target = find(destination.targetId());
        if (destination.targetId() != null) {
            if (target == null || target.hp() <= 0 || !canAttack()) {
                destination = null;
                return;
            }
            if (inReach(target)) {
                destination = null;
                attackTarget(target);
                return;
            }
        }
        int x = target == null ? destination.x() : target.x();
        int y = target == null ? destination.y() : target.y();
        if (target == null && self.x() == x && self.y() == y) {
            destination = null;
            return;
        }
        DungeonLayout.Cell step = DungeonNavigation.next(state, self, x, y, target != null);
        if (step == null) {
            interruptRoute();
            tell("갈 수 있는 길이 없습니다.");
        } else if (readyAt("move") <= now) {
            move(new Step(step.x(), step.y()), false, now);
        } else {
            waitingAction = "move";
        }
    }

    private void interruptRoute() {
        if (destination != null) resumable = destination;
        destination = null;
    }

    private void resumeRoute() {
        if (!canAct() || resumable == null) return;
        navigateTo(resumable);
    }

    private void goToLoot() {
        if (!canAct()) return;
        DungeonLayout.Cell drop = DungeonNavigation.nearestLoot(state, self);
        if (drop == null) tell("주울 전리품이 없습니다.");
        else navigateTo(new Destination(drop.x(), drop.y(), null));
    }

    private void toggleExamine() {
        if (panelWindow != null || chatEditor != null) return;
        interruptRoute();
        aiming = false;
        examine = !examine;
        if (examine) tell("살펴볼 칸을 선택하세요.");
    }

    private void examineTile(int x, int y) {
        if (state == null || !mapCell(x, y)) return;
        examine = false;
        InnApi.Combatant actor = state.actors().stream()
            .filter(value -> value.hp() > 0 && value.x() == x && value.y() == y).findFirst().orElse(null);
        if (actor != null && actor.id().equals(state.selfId())) {
            showPanel(StatusWindow::new);
            return;
        }
        if (actor != null && ("player".equals(actor.kind()) || "guide".equals(actor.kind()))) {
            inspectAlly(actor.id());
            return;
        }
        String description = actor == null ? describeGround(x, y)
            : actor.name() + " · 체력 " + actor.hp() + "/" + actor.maxHp();
        showPanel(() -> {
            DungeonWindow window = new DungeonWindow("살펴보기", 130, 52);
            label(window, description, 8, TEXT, 5, 22, 120);
            return window;
        });
    }

    private String describeGround(int x, int y) {
        for (InnApi.TrainingTool tool : state.trainingTools()) {
            if (tool.x() == x && tool.y() == y) {
                return "ration-rack".equals(tool.id()) ? "식량 상자 · 식량을 3개까지 보급"
                    : "회복 도구 · 체력과 행동력 회복";
            }
        }
        for (InnApi.Drop drop : state.drops()) {
            if (drop.pickedBy() == null && drop.x() == x && drop.y() == y) {
                return itemName(drop.item()) + " " + drop.amount();
            }
        }
        return passable(x, y) ? "던전 바닥" : "던전 벽";
    }

    private void attack() {
        if (!canAttack()) return;
        interruptRoute();
        aiming = false;
        InnApi.Combatant target = find(selected);
        if (target == null || !isCombatTarget(target) || !inReach(target)) {
            target = state.actors().stream().filter(NativeDungeonScene::isCombatTarget).filter(this::inReach)
                .findFirst().orElse(null);
        }
        if (target != null) {
            selected = target.id();
            attackTarget(target);
            updateMarkers();
        } else {
            tell(state.trainingTools().isEmpty() ? "가까운 적에게 평타를 쓸 수 있습니다." :
                "가까운 적이나 허수아비에게 평타를 쓸 수 있습니다.");
        }
    }

    private void attackTarget(InnApi.Combatant target) {
        if (!beginRequest(new InnApi.Action("attack", 0, 0, target.id()), System.nanoTime())) return;
        NativeClient.dungeonAttack(target.id());
    }

    private void toggleAim() {
        if (!canAttack()) return;
        interruptRoute();
        examine = false;
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
            // ColorBlock scales a unit quad, so its rotation pivot is in unit coordinates.
            dart.origin.set(.5f, .5f);
            dart.x = mapX + (projectile.x() + .5f + dx * progress) * tile - .5f;
            dart.y = mapY + (projectile.y() + .48f + dy * progress) * tile - .5f;
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
        interruptRoute();
        if (self.rations() == 0) {
            tell("보유한 식량이 없습니다.");
        } else if (self.hp() == self.maxHp()) {
            tell("체력이 이미 가득 찼습니다.");
        } else if (beginRequest(new InnApi.Action("eat", 0, 0, null), System.nanoTime())) {
            NativeClient.dungeonEat();
        }
    }

    private boolean canUsePotion(String item) {
        if (leaving || paused() || !activePlayer() || inventoryAmount(self, item) <= 0) return false;
        InnApi.Action action = new InnApi.Action("use-item", 0, 0, item);
        return !action.equals(state.queued()) && !awaitingAcknowledgement(System.nanoTime());
    }

    private void usePotion(String item) {
        if (canUsePotion(item) && beginRequest(new InnApi.Action("use-item", 0, 0, item), System.nanoTime())) {
            NativeClient.dungeonUseItem(item);
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
        if (actor == null || actor.hp() == 0) return false;
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
        if (input != null) {
            interruptRoute();
            aiming = examine = false;
            move(new Step(input.dx(), input.dy()), input.fresh(), now);
        } else if (!movement.active() && !aiming && !examine) {
            advanceRoute(now);
        }
    }

    private boolean onKey(KeyEvent event) {
        if (typing()) {
            if (chatEditor != null && event.code == Input.Keys.ESCAPE && event.pressed) chatCancelPending = true;
            return true;
        }
        if (panelWindow == null && event.code == Input.Keys.ENTER && event.pressed && !enterWithAlt()) {
            chatOpenPending = true;
            return true;
        }
        if (panelWindow != null) return false;
        if (onHotkey(event.code, event.pressed)) return true;
        if (!paused() && movement.onKey(event.code, event.pressed)) {
            if (event.pressed) interruptRoute();
            return true;
        }
        return false;
    }

    private boolean onHotkey(int key, boolean pressed) {
        if (key == Input.Keys.Q) {
            if (pressed && !attackHeld) attack();
            attackHeld = pressed;
            return true;
        }
        if (key == Input.Keys.NUM_1) {
            if (pressed && !shootHeld) toggleAim();
            shootHeld = pressed;
            return true;
        }
        if (key == Input.Keys.NUM_2) {
            if (pressed && !rationHeld) eat();
            rationHeld = pressed;
            return true;
        }
        if (key == Input.Keys.P) {
            if (pressed && !pauseHeld) togglePause();
            pauseHeld = pressed;
            return true;
        }
        if (!pressed) return false;
        switch (key) {
            case Input.Keys.SPACE, Input.Keys.NUMPAD_5, Input.Keys.Z -> {
                if (canAct()) interruptRoute();
            }
            case Input.Keys.C -> goToLoot();
            case Input.Keys.R -> resumeRoute();
            case Input.Keys.E -> toggleExamine();
            case Input.Keys.F1, Input.Keys.F, Input.Keys.I -> showPanel(BackpackWindow::new);
            case Input.Keys.H -> showPanel(StatusWindow::new);
            case Input.Keys.PLUS, Input.Keys.EQUALS -> zoom(1);
            case Input.Keys.MINUS -> zoom(-1);
            default -> { return false; }
        }
        return true;
    }

    private void tell(String text) {
        localNotice = text;
        noticeUntil = System.nanoTime() + 1_800_000_000L;
    }

    @Override
    public void onPause() {
        closeComposer();
        interruptRoute();
        movement.reset();
        attackHeld = shootHeld = rationHeld = pauseHeld = aiming = examine = false;
        super.onPause();
    }

    private void toggleHelp() {
        if (panelWindow != null || typing()) return;
        help.visible = help.active = !help.visible;
        helpButton.text(help.visible ? "F8 접기" : "F8 도움");
    }

    private void showPanel(Supplier<? extends Window> open) {
        if (state == null || leaving || panelWindow != null || chatEditor != null) return;
        interruptRoute();
        movement.reset();
        aiming = examine = false;
        panelWindow = open.get();
        add(panelWindow);
    }

    private void inspectAlly(String actorId) {
        showPanel(() -> new AllyWindow(actorId));
    }

    private void openGameMenu() {
        showPanel(() -> {
            DungeonWindow menu = new DungeonWindow("원정 메뉴", 112, 89);
            button(menu, "계속하기", menu::hide).setRect(4, 22, 104, 18);
            button(menu, "설정", () -> {
                menu.hide();
                showPanel(() -> new WndSettings() {
                    @Override
                    public void destroy() {
                        if (panelWindow == this) panelWindow = null;
                        movement.reset();
                        super.destroy();
                    }
                });
            }).setRect(4, 44, 104, 18);
            button(menu, "여관으로", this::leave).setRect(4, 66, 104, 18);
            return menu;
        });
    }

    private void leave() {
        if (leaving) return;
        leaving = true;
        NativeClient.returnToInn();
    }

    @Override
    protected void onBackPressed() {
        if (chatEditor != null) chatCancelPending = true;
        else if (panelWindow != null) panelWindow.hide();
        else if (chatPinned) toggleChatLog();
        else if (aiming) aiming = false;
        else if (examine) examine = false;
        else if (help.visible) toggleHelp();
        else openGameMenu();
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

    private static boolean isCombatTarget(InnApi.Combatant actor) {
        return enemy(actor) || "dummy".equals(actor.kind());
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

    private int inventoryAmount(InnApi.Combatant actor, String item) {
        if (actor == null) return 0;
        for (InnApi.InventoryItem entry : actor.inventory()) {
            if (item.equals(entry.item())) return entry.amount();
        }
        return switch (item) {
            case "ration" -> actor.rations();
            case "gold" -> state.drops().stream().filter(drop ->
                "gold".equals(drop.item()) && actor.id().equals(drop.pickedBy())).mapToInt(InnApi.Drop::amount).sum();
            default -> 0;
        };
    }

    private List<InnApi.InventoryItem> inventory(InnApi.Combatant actor) {
        if (actor == null) return List.of();
        if (!actor.inventory().isEmpty()) return actor.inventory();
        List<InnApi.InventoryItem> items = new ArrayList<>();
        for (String item : List.of("ration", "gold")) {
            int amount = inventoryAmount(actor, item);
            if (amount > 0) items.add(new InnApi.InventoryItem(item, amount));
        }
        return items;
    }

    private String itemName(String item) {
        if (item == null) return "물품";
        String known = knownPotionEffect(item);
        if (!known.isEmpty()) return known + " 물약";
        return switch (item) {
            case "ration" -> "식량";
            case "gold" -> "금화";
            case "violet-potion" -> "보랏빛 물약";
            case "amber-potion" -> "호박빛 물약";
            default -> "물품";
        };
    }

    private String knownPotionEffect(String item) {
        for (InnApi.ItemKnowledge known : state.identifiedItems()) {
            if (item.equals(known.item())) {
                if ("healing".equals(known.effect())) return "치유";
                if ("energy".equals(known.effect())) return "활력";
            }
        }
        return "";
    }

    private String partyKnowledge() {
        List<String> known = new ArrayList<>();
        for (String item : POTIONS) {
            String effect = knownPotionEffect(item);
            if (!effect.isEmpty()) known.add(("violet-potion".equals(item) ? "보라" : "호박") + " → " + effect);
        }
        return known.isEmpty() ? "공용 감정 · 아직 감정된 물약이 없습니다."
            : "공용 감정 · " + String.join(" / ", known);
    }

    private static Image itemIcon(String item) {
        Image image = new Image(Assets.Sprites.ITEMS);
        int frame = switch (item) {
            case "gold" -> ItemSpriteSheet.GOLD;
            case "violet-potion" -> ItemSpriteSheet.POTION_INDIGO;
            case "amber-potion" -> ItemSpriteSheet.POTION_AMBER;
            default -> ItemSpriteSheet.RATION;
        };
        image.frame(ItemSpriteSheet.film.get(frame));
        return image;
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
        private final Group[] potionRows = new Group[POTIONS.size()];
        private final RenderedTextBlock[] potionLabels = new RenderedTextBlock[POTIONS.size()];
        private final StyledButton[] potionButtons = new StyledButton[POTIONS.size()];
        private final RenderedTextBlock knowledge;
        private List<InnApi.ItemKnowledge> lastKnown;
        private int selectedSlot = -1;

        BackpackWindow() {
            super("배낭", 154, 121);
            instructions = label(this, " ", 7, MUTED, 5, 22, 144);
            for (int index = 0; index < slots.length; index++) {
                int slot = index;
                slots[index] = button(this, "", () -> select(slot));
                slots[index].setRect(5 + index * 74, 37, 70, 21);
            }
            for (int index = 0; index < POTIONS.size(); index++) {
                String item = POTIONS.get(index);
                int y = 62 + 19 * index;
                Group row = potionRows[index] = new Group();
                add(row);
                Image icon = itemIcon(item);
                icon.x = 8;
                icon.y = y + 2;
                row.add(icon);
                potionLabels[index] = label(row, "", 8, TEXT, 23, y + 4, 74);
                StyledButton use = potionButtons[index] = button(row, "마시기", () -> usePotion(item));
                use.setRect(101, y, 47, 16);
            }
            knowledge = label(this, "", 7, ALLY, 5, 103, 144);
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
            boolean hasPotion = POTIONS.stream().anyMatch(item -> inventoryAmount(self, item) > 0);
            setText(instructions, readOnly ? "전술 정지 · 열람만 가능" : hasPotion
                ? "두 칸을 선택해 위치를 바꿉니다.\n물약 마시기 · 행동력 3" : "두 칸을 선택해 위치를 바꿉니다.");
            String ration = "식량  " + inventoryAmount(self, "ration");
            String coins = "금화  " + inventoryAmount(self, "gold");
            for (int index = 0; index < slots.length; index++) {
                StyledButton slot = slots[index];
                slot.active = !readOnly;
                String text = ((index == 0) == rationFirst) ? ration : coins;
                if (!text.equals(slot.text())) slot.text(text);
                slot.textColor(index == selectedSlot ? SELF : TEXT);
            }
            for (int index = 0; index < POTIONS.size(); index++) {
                String item = POTIONS.get(index);
                int amount = inventoryAmount(self, item);
                Group row = potionRows[index];
                row.visible = row.active = amount > 0;
                if (amount > 0) {
                    setText(potionLabels[index], itemName(item) + " ×" + amount);
                    potionButtons[index].enable(canUsePotion(item));
                }
            }
            if (!state.identifiedItems().equals(lastKnown)) {
                lastKnown = state.identifiedItems();
                setText(knowledge, partyKnowledge());
            }
        }
    }

    private final class AllyWindow extends DungeonWindow {
        private final String actorId;
        private final RenderedTextBlock identity;
        private final RenderedTextBlock vitals;
        private final RenderedTextBlock cooldowns;
        private final RenderedTextBlock knowledge;
        private final InventoryRows items = new InventoryRows();
        private final ScrollPane itemScroll = new ScrollPane(items);
        private List<InnApi.InventoryItem> lastItems;
        private List<InnApi.ItemKnowledge> lastKnown;

        AllyWindow(String actorId) {
            super("동료 살펴보기", 154, 147);
            this.actorId = actorId;
            identity = label(this, "", 8, ALLY, 5, 22, 144);
            vitals = label(this, "", 7, TEXT, 5, 36, 144);
            cooldowns = label(this, "", 7, MUTED, 5, 48, 144);
            label(this, "동료 배낭 · 열람만 가능", 8, ALLY, 5, 64, 144);
            panel(this, 4, 77, 146, 52, 0x59121817);
            add(itemScroll);
            itemScroll.setRect(5, 78, 144, 50);
            knowledge = label(this, "", 7, ALLY, 5, 133, 144);
            refresh();
        }

        @Override
        public void update() {
            refresh();
            super.update();
        }

        private void refresh() {
            InnApi.Combatant actor = find(actorId);
            long now = NativeClient.dungeonNow();
            if (actor == null) {
                setText(identity, "동료를 찾을 수 없습니다.");
                setText(vitals, "");
                setText(cooldowns, "");
            } else {
                String name = partyName(actor, false);
                if ("player".equals(actor.kind())) {
                    List<AvatarProfile.Option> jobs = AvatarProfile.catalog().jobs();
                    name += " · " + jobs.get(AvatarSprites.indexOf(jobs, actor.appearance().job())).label();
                }
                setText(identity, name);
                int energy = actor.hp() > 0 && battleOngoing()
                    ? (int) (BattleRules.energyAt(actor, state.now(), now) + 1e-8) : 0;
                setText(vitals, "HP " + actor.hp() + "/" + actor.maxHp() +
                    "    ·    행동력 " + energy + "/" + BattleRules.ENERGY_CAP);
                setText(cooldowns, actor.hp() == 0 ? "쓰러짐" : cooldownSummary(actor, now));
            }
            List<InnApi.InventoryItem> contents = inventory(actor);
            List<InnApi.ItemKnowledge> known = state.identifiedItems();
            if (contents.equals(lastItems) && known.equals(lastKnown)) return;
            lastItems = contents;
            lastKnown = known;
            float scrollY = items.camera.scroll.y;
            items.rebuild(contents);
            itemScroll.setSize(itemScroll.width(), itemScroll.height());
            itemScroll.scrollTo(0, scrollY);
            setText(knowledge, partyKnowledge());
        }
    }

    private final class InventoryRows extends Component {
        void rebuild(List<InnApi.InventoryItem> contents) {
            for (Gizmo child : members) if (child != null) child.destroy();
            clear();
            float y = 2;
            if (contents.isEmpty()) {
                label(this, "소지한 물품이 없습니다.", 7, MUTED, 4, y + 2, 132);
                y += 14;
            } else {
                for (InnApi.InventoryItem item : contents) {
                    if (item.amount() <= 0) continue;
                    Image icon = itemIcon(item.item());
                    icon.x = 5;
                    icon.y = y;
                    add(icon);
                    label(this, itemName(item.item()) + " ×" + item.amount(), 8, TEXT, 22, y + 2, 112);
                    y += 14;
                }
            }
            setSize(144, y);
        }
    }

    private final class StatusWindow extends DungeonWindow {
        private final RenderedTextBlock[] names = new RenderedTextBlock[2];
        private final RenderedTextBlock[] balances = new RenderedTextBlock[2];
        private final RenderedTextBlock[] cooldowns = new RenderedTextBlock[2];

        StatusWindow() {
            super(isSoloAtb() ? "캐릭터 상태" : "파티 상태", 153, 100);
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
                setText(cooldowns[index], actor.hp() == 0 ? "쓰러짐" : cooldownSummary(actor, now));
            }
        }
    }

    private String cooldownSummary(InnApi.Combatant actor, long now) {
        return "이동 " + actionCooldown(actor, "move", now) + "    ·    평타 " + actionCooldown(actor, "attack", now);
    }

    private String actionCooldown(InnApi.Combatant actor, String type, long now) {
        long remaining = BattleRules.readyAt(actor, type, state.now(), state.inCombat()) - now;
        if (remaining <= 0) return "준비";
        long tenths = Math.ceilDiv(remaining, 100);
        return (tenths / 10) + "." + (tenths % 10) + "초";
    }

    private void createChatLog() {
        chatLog = new Group();
        add(chatLog);
        float x = boardX + 5;
        float width = Math.min(174, boardWidth - 10);
        float historyHeight = Math.min(54, Math.max(30, boardHeight / 3));
        float composerY = boardY + boardHeight - 46;
        float y = composerY - historyHeight - 14;
        ColorBlock backdrop = panel(chatLog, x, y, width, historyHeight + 28, 0xDB202622);
        chatLog.add(new PointerArea(backdrop));
        panel(chatLog, x, y, width, 1, 0xC2667767);
        label(chatLog, "대화 기록", 7, ALLY, x + 5, y + 3, width - 10);
        chatRows = new ChatRows(width - 8);
        chatScroll = new ScrollPane(chatRows);
        chatLog.add(chatScroll);
        chatScroll.setRect(x + 3, y + 12, width - 6, historyHeight);
        chatPrompt = button(chatLog, "Enter · 대화 입력", this::openComposer);
        chatPrompt.setRect(x + 3, composerY, width - 6, 12);
        chatSend = button(chatLog, "보내기", () -> chatSubmitPending = true);
        chatSend.setRect(x + width - 35, composerY, 32, 12);
        updateChatVisibility();
    }

    private boolean canChat() {
        return !leaving && state != null && !state.waitingForPartner() && !"closed".equals(state.status());
    }

    private boolean typing() { return chatEditor != null || waitForKeyRelease; }

    private void toggleChatLog() {
        if (panelWindow != null) return;
        if (chatEditor != null) closeComposer();
        else chatPinned = !chatPinned;
        updateChatVisibility();
    }

    private void updateChatVisibility() {
        chatLog.visible = chatLog.active = chatPinned || chatEditor != null;
        chatPrompt.visible = chatEditor == null;
        chatPrompt.enable(chatPrompt.visible && canChat());
        chatSend.visible = chatEditor != null;
        chatSend.enable(chatSend.visible);
    }

    private void openComposer() {
        if (!canChat() || chatEditor != null || panelWindow != null) return;
        interruptRoute();
        movement.reset();
        attackHeld = shootHeld = rationHeld = pauseHeld = aiming = examine = false;
        composerEnterReleased = !Gdx.input.isKeyPressed(Input.Keys.ENTER);
        chatEditor = new TextInput(Chrome.get(Chrome.Type.TOAST_WHITE), false, defaultZoom * 7) {
            @Override
            public void enterPressed() {
                if (composerEnterReleased && !enterWithAlt()) chatSubmitPending = true;
            }
        };
        chatEditor.setLeftAligned();
        chatEditor.setMaxLength(160);
        chatLog.add(chatEditor);
        chatEditor.setRect(chatPrompt.left(), chatPrompt.top(), chatSend.left() - chatPrompt.left() - 2,
            chatPrompt.height());
        updateChatVisibility();
    }

    private void closeComposer() {
        if (chatEditor == null) return;
        chatLog.erase(chatEditor);
        chatEditor.destroy();
        chatEditor = null;
        chatCancelPending = chatSubmitPending = false;
        waitForKeyRelease = Gdx.input.isKeyPressed(Input.Keys.ANY_KEY);
        updateChatVisibility();
    }

    private static boolean enterWithAlt() {
        return Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT);
    }

    private void updateChatInput() {
        if (waitForKeyRelease && !Gdx.input.isKeyPressed(Input.Keys.ANY_KEY)) waitForKeyRelease = false;
        if (chatEditor != null) {
            if (!canChat() || chatCancelPending || Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
                closeComposer();
            } else {
                composerEnterReleased |= !Gdx.input.isKeyPressed(Input.Keys.ENTER);
                if (chatSubmitPending) {
                    chatSubmitPending = false;
                    String message = chatEditor.getText().strip();
                    if (!message.isBlank()) {
                        NativeClient.dungeonChat(message);
                        closeComposer();
                    }
                }
            }
        } else if (!waitForKeyRelease && !enterWithAlt() &&
                (chatOpenPending || Gdx.input.isKeyJustPressed(Input.Keys.ENTER))) {
            openComposer();
        }
        chatOpenPending = false;
    }

    private void acceptChat(InnApi.DungeonState snapshot) {
        long receipt = System.nanoTime();
        long wallNow = NativeClient.dungeonWallNow();
        for (InnApi.PartyChat message : snapshot.messages()) {
            if (message.id() <= seenChatId) continue;
            seenChatId = message.id();
            if (chatHistory.size() == CHAT_HISTORY_LIMIT) chatHistory.removeFirst();
            chatHistory.add(message);
            chatDirty = true;
            long age = message.time() > 0 ? Math.max(0, wallNow - message.time()) : 0;
            DungeonActorView speaker = actorViews.get(message.actorId());
            if (speaker != null && age < SPEECH_MS) {
                speaker.speak(message.text(), receipt + (SPEECH_MS - age) * 1_000_000,
                    boardX, boardY, boardWidth);
            }
        }
    }

    private void refreshChatLog() {
        if (!chatLog.visible) return;
        chatPrompt.enable(chatEditor == null && canChat());
        if (!chatDirty) return;
        float scrollY = chatRows.camera.scroll.y;
        boolean bottom = scrollY + chatScroll.height() >= chatRows.height() - 5;
        chatRows.rebuild(chatHistory);
        chatScroll.setSize(chatScroll.width(), chatScroll.height());
        chatScroll.scrollTo(0, bottom ? chatRows.height() : scrollY);
        chatDirty = false;
    }

    static String wrapLongChatWords(String text, int columns) {
        StringBuilder wrapped = new StringBuilder(text.length());
        int run = 0;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) run = 0;
            else if (++run > columns) {
                wrapped.append('\n');
                run = 1;
            }
            wrapped.appendCodePoint(codePoint);
        }
        return wrapped.toString();
    }

    private static final class ChatRows extends Component {
        private final float rowWidth;

        ChatRows(float rowWidth) { this.rowWidth = rowWidth; }

        void rebuild(List<InnApi.PartyChat> messages) {
            for (Gizmo child : members) if (child != null) child.destroy();
            clear();
            float y = 2;
            if (messages.isEmpty()) {
                y = label(this, "아직 대화가 없습니다.", 7, MUTED, 3, y, rowWidth - 5).bottom() + 2;
            } else {
                for (InnApi.PartyChat message : messages) {
                    String text = wrapLongChatWords(message.name() + "  " + message.text(),
                        Math.max(6, (int) ((rowWidth - 5) / 7)));
                    y = label(this, text, 7, TEXT, 3, y, rowWidth - 5).bottom() + 2;
                }
            }
            setSize(rowWidth, y);
        }
    }

    private static String partyName(InnApi.Combatant actor, boolean own) {
        String name = actor.name();
        if (name.codePointCount(0, name.length()) > 11) name = name.substring(0, name.offsetByCodePoints(0, 10)) + "…";
        return own ? name + " (나)" : name;
    }

    private final class PartySlot {
        private final Group group = new Group() {
            @Override
            public void destroy() {
                super.destroy();
                if (portraitOutfit != null) portraitOutfit.close();
            }
        };
        private final Image portrait = new Image(Assets.Sprites.ROGUE);
        private final RenderedTextBlock name;
        private final RenderedTextBlock health;
        private final RenderedTextBlock energyLabel;
        private final ColorBlock hpBar;
        private final ColorBlock charge;
        private final float width;
        private InnApi.Combatant actor;
        private AvatarSprites portraitOutfit;
        private InnApi.Appearance portraitAppearance;
        private boolean own;

        private PartySlot(float x, float y, float width) {
            this.width = width;
            hud.add(group);
            ColorBlock plate = panel(group, x - 1, y - 1, width + 2, 28, HUD);
            panel(group, x - 1, y - 1, width + 2, 1, EDGE);
            group.add(new PointerArea(plate) {
                @Override
                protected void onClick(PointerEvent event) {
                    if (actor == null) return;
                    if (own) showPanel(StatusWindow::new);
                    else inspectAlly(actor.id());
                }
            });
            panel(group, x + 1, y + 1, 14, 17, 0xB0131715);
            portrait.frame(0, 0, AvatarSprites.FRAME_WIDTH, AvatarSprites.FRAME_HEIGHT);
            portrait.x = x + 2;
            portrait.y = y + 2;
            group.add(portrait);
            name = label(group, " ", 8, TEXT, x + 17, y + 1, width - 18);
            health = label(group, " ", 6, MUTED, x + 17, y + 10, width - 17);
            energyLabel = label(group, " ", 6, ALLY, x + width - 32, y + 10, 32);
            panel(group, x, y + 19, width, 3, 0xFF414B41);
            hpBar = panel(group, x, y + 19, width, 3, 0xFFA67F66);
            panel(group, x, y + 23, width, 2, 0xFF414B41);
            charge = panel(group, x, y + 23, width, 2, 0xFFFFFFFF);
            group.visible = false;
        }

        private void set(InnApi.Combatant actor, boolean own) {
            this.actor = actor;
            this.own = own;
            group.visible = group.active = actor != null;
            if (actor == null) return;
            InnApi.Appearance appearance = "player".equals(actor.kind()) ? actor.appearance() : null;
            if (!Objects.equals(appearance, portraitAppearance)) {
                AvatarSprites prior = portraitOutfit;
                portraitOutfit = appearance == null ? null : AvatarSprites.acquire(appearance,
                    own ? Assets.Sprites.WARRIOR : Assets.Sprites.ROGUE);
                portrait.texture(portraitOutfit == null ? Assets.Sprites.ROGUE : portraitOutfit.texture());
                portrait.frame(0, 0, AvatarSprites.FRAME_WIDTH, AvatarSprites.FRAME_HEIGHT);
                portraitAppearance = appearance;
                if (prior != null) prior.close();
            }
            name.hardlight(own ? SELF : TEXT);
            String nick = partyName(actor, own);
            int length = Math.max(1, (int) (width - 18) / 7);
            if (nick.codePointCount(0, nick.length()) > length) {
                nick = nick.substring(0, nick.offsetByCodePoints(0, length - 1)) + "…";
            }
            setText(name, nick);
            setText(health, "HP " + actor.hp() + "/" + actor.maxHp());
            hpBar.size(width * fraction(actor.hp(), actor.maxHp()), 3);
        }

        private void update(long now) {
            if (actor == null) return;
            boolean active = state != null && ("active".equals(state.status()) || "won".equals(state.status())) &&
                !state.waitingForPartner();
            double energy = active && actor.hp() > 0 ? BattleRules.energyAt(actor, state.now(), now) : 0;
            float remaining = (float) (energy / BattleRules.ENERGY_CAP);
            int color = InnAtbMeter.colorForFraction(remaining);
            charge.size(width * remaining, 2);
            charge.hardlight(color);
            DungeonActorView view = actorViews.get(actor.id());
            if (view != null) view.showAtb(remaining, color);
            energyLabel.hardlight(active && actor.hp() > 0 ? color : MUTED);
            String balance = "행동 " + (int) (energy + 1e-8) + "/" + BattleRules.ENERGY_CAP;
            setText(energyLabel, width < 77 ? "" : actor.hp() == 0 ? "쓰러짐" : active ? balance : "대기");
        }
    }

    private static float fraction(long value, long maximum) {
        return Math.max(0, Math.min(1, value / (float) Math.max(1, maximum)));
    }
}
