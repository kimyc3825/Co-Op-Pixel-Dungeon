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
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.watabou.input.KeyEvent;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.PointerArea;
import com.watabou.noosa.TextureFilm;
import com.watabou.noosa.Tilemap;
import com.watabou.utils.PointF;
import com.watabou.utils.RectF;
import com.watabou.utils.Signal;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final long MOVE_QUEUE_LEAD_MS = 70;
    private static final long MOVE_RETRY_NS = 60_000_000L;
    private static final long COMMAND_ACK_WAIT_NS = 550_000_000L;

    private record Step(int dx, int dy) {}
    private record SubmittedAction(InnApi.Action action, long acknowledgement, long sentAt) {}

    private final Map<String, DungeonActorView> actorViews = new HashMap<>();
    private final Map<String, Image> dropViews = new HashMap<>();
    private final PartySlot[] party = new PartySlot[2];
    private final KeyboardMovement movement = new KeyboardMovement();
    private InnApi.DungeonState state;
    private InnApi.Combatant self;
    private Signal.Listener<KeyEvent> keys;
    private Group actors;
    private Group loot;
    private Group help;
    private Tilemap tiles;
    private ColorBlock floorPanel;
    private ColorBlock selectedTile;
    private ColorBlock queuedTile;
    private Group waitingCard;
    private RenderedTextBlock waitingLabel;
    private RenderedTextBlock floorStatus;
    private RenderedTextBlock queueLabel;
    private RenderedTextBlock notice;
    private StyledButton helpButton;
    private StyledButton hitButton;
    private StyledButton rationButton;
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
    private boolean attackHeld;
    private boolean rationHeld;
    private boolean helpHeld;
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
        label(this, "하수도", 13, TEXT, left, top + 2, 95);
        floorStatus = label(this, " ", 7, MUTED, left, top + 18, 145);

        StyledButton leave = button(this, "여관으로", this::leave);
        leave.setRect(left + width - 58, top + 3, 58, 19);
        helpButton = button(this, "F1 조작법", this::toggleHelp);
        helpButton.setRect(left + width - 122, top + 3, 58, 19);

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
        label(this, "파티", 9, TEXT, sideX + 6, boardY + 5, sideWidth - 12);

        float partyTop = boardY + 18;
        float spacing = boardHeight < 125 ? 26 : 31;
        for (int i = 0; i < party.length; i++) {
            party[i] = new PartySlot(sideX + 6, partyTop + i * spacing, sideWidth - 12);
        }
        queueLabel = label(this, "예약 없음", 7, ALLY, sideX + 6, partyTop + 2 * spacing + 3, sideWidth - 12);
        hitButton = button(this, "평타 · Space", this::attack);
        hitButton.setRect(sideX + 6, boardY + boardHeight - 24, sideWidth - 12, 18);
        hitButton.enable(false);
        rationButton = button(this, "식량 0 · E", this::eat);
        rationButton.setRect(sideX + 6, boardY + boardHeight - 44, sideWidth - 12, 18);
        rationButton.enable(false);
        notice = label(this, " ", 7, ENEMY, boardX + 5, boardY + boardHeight - 14, boardWidth - 10);

        selectedTile = panel(this, 0, 0, 1, 1, 0x66D5664E);
        queuedTile = panel(this, 0, 0, 1, 1, 0x6594C9C0);
        selectedTile.visible = queuedTile.visible = false;
        loot = new Group();
        add(loot);
        actors = new Group();
        add(actors);
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
        float helpWidth = Math.min(boardWidth - 12, 131);
        float helpX = boardX + 6;
        float helpY = boardY + 6;
        panel(help, helpX, helpY, helpWidth, 63, 0xC01A201D);
        panel(help, helpX, helpY, helpWidth, 1, 0xAF9DB4A6);
        label(help, "이동  WASD · 방향키", 7, TEXT, helpX + 5, helpY + 4, helpWidth - 10);
        label(help, "대각선  방향 조합 · 숫자패드", 7, TEXT, helpX + 5, helpY + 15, helpWidth - 10);
        label(help, "평타  Space · 적 클릭", 7, TEXT, helpX + 5, helpY + 26, helpWidth - 10);
        label(help, "인접 칸 클릭  한 칸 이동", 7, TEXT, helpX + 5, helpY + 37, helpWidth - 10);
        label(help, "식량 사용  E · 행동력 3", 7, TEXT, helpX + 5, helpY + 48, helpWidth - 10);
        help.visible = false;

        keys = this::onKey;
        KeyEvent.addKeyListener(keys);
        accept(NativeClient.dungeonState());
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
        String connection = NativeClient.dungeonConnection();
        setText(floorStatus, connection.isEmpty() ? floorSummary : floorSummary + "   ·   " + connection);
        String connectionNotice = NativeClient.dungeonNotice();
        setText(notice, connectionNotice.isEmpty() && System.nanoTime() < noticeUntil ? localNotice : connectionNotice);
        previewQueuedMove(now);
        onHotkey(Input.Keys.F1, Gdx.input.isKeyPressed(Input.Keys.F1));
        onHotkey(Input.Keys.SPACE, Gdx.input.isKeyPressed(Input.Keys.SPACE));
        onHotkey(Input.Keys.E, Gdx.input.isKeyPressed(Input.Keys.E));
        updateInput(now);
        super.update();
        actors.sort(Comparator.comparingDouble(value -> ((DungeonActorView) value).depth()));
    }

    private void accept(InnApi.DungeonState next) {
        InnApi.DungeonState prior = state;
        InnApi.Combatant priorSelf = self;
        state = next;
        if (next == null) {
            self = null;
            submitted = null;
            showWaiting("던전을 불러오는 중…");
            return;
        }
        if (tiles == null || prior == null || !next.map().equals(prior.map()) || !next.id().equals(prior.id())) {
            buildMap(next.map());
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
                actors.add(view);
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
        InnApi.Action queued = canAct() ? next.queued() : null;
        setText(queueLabel, queued == null ? "예약 없음" : switch (queued.type()) {
            case "move" -> "예약  " + directionName(queued.dx(), queued.dy()) + " 이동";
            case "eat" -> "예약  식량";
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

    private void buildMap(List<String> map) {
        rows = map.size();
        columns = map.getFirst().length();
        tile = Math.min(36, Math.min((boardWidth - 8) / columns, (boardHeight - 8) / rows));
        mapX = align(boardX + (boardWidth - columns * tile) / 2);
        mapY = align(boardY + (boardHeight - rows * tile) / 2);
        if (tiles != null) {
            tiles.remove();
            tiles.destroy();
        }
        String texture = Assets.Environment.TILES_SEWERS;
        tiles = new Tilemap(texture, new TextureFilm(texture, TILE, TILE));
        int[] indices = new int[columns * rows];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                boolean wall = map.get(y).charAt(x) == '#';
                int variation = Math.floorMod(x * 31 + y * 17, 9);
                indices[y * columns + x] = wall
                    ? variation == 0 ? DungeonTileSheet.FLAT_WALL_DECO : DungeonTileSheet.FLAT_WALL
                    : variation == 0 ? DungeonTileSheet.FLOOR_ALT_1 : DungeonTileSheet.FLOOR;
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
        moveLayerUp(loot);
        moveLayerUp(actors);
        moveLayerUp(notice);
        moveLayerUp(waitingCard);
        moveLayerUp(help);
    }

    private void moveLayerUp(com.watabou.noosa.Gizmo layer) {
        remove(layer);
        addToFront(layer);
    }

    private boolean canAct() {
        return !leaving && self != null && self.hp() > 0 && !state.waitingForPartner() &&
            ("active".equals(state.status()) || "won".equals(state.status()));
    }

    private long readyAt(String action) {
        return BattleRules.readyAt(self, action, state.now(), state.inCombat());
    }

    private void updateActionButtons(long now) {
        boolean able = canAct();
        boolean attackEnabled = able && "active".equals(state.status());
        long attackWait = attackEnabled ? Math.max(0, self.attackReadyAt() - now) : 0;
        String attackLabel = "평타 · Space";
        if (attackWait > 0) {
            long tenths = (attackWait + 99) / 100;
            attackLabel = "평타 " + tenths / 10 + "." + tenths % 10 + "초";
        } else if (attackEnabled && readyAt("attack") > now) {
            attackLabel = "평타 · 행동력 1";
        }
        if (!attackLabel.equals(hitButton.text())) hitButton.text(attackLabel);
        hitButton.enable(attackEnabled);
        hitButton.textColor(attackEnabled && readyAt("attack") <= now ? TEXT : MUTED);

        int rations = self == null ? 0 : self.rations();
        String rationLabel = "식량 " + rations + " · E";
        if (!rationLabel.equals(rationButton.text())) rationButton.text(rationLabel);
        boolean rationEnabled = able && rations > 0 && self.hp() < self.maxHp();
        rationButton.enable(rationEnabled);
        rationButton.textColor(rationEnabled && readyAt("eat") <= now ? TEXT : MUTED);
    }

    private boolean passable(int x, int y) {
        return x >= 0 && y >= 0 && x < columns && y < rows && state.map().get(y).charAt(x) != '#';
    }

    private boolean mayStep(Step step, boolean ready) {
        int x = self.x() + step.dx();
        int y = self.y() + step.dy();
        if (!passable(x, y) || step.dx() != 0 && step.dy() != 0 &&
            (!passable(x, self.y()) || !passable(self.x(), y))) return false;
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
        if (!canAct() || !passable(x, y)) return;
        for (InnApi.Combatant actor : state.actors()) {
            if (enemy(actor) && actor.hp() > 0 && actor.x() == x && actor.y() == y) {
                selected = actor.id();
                if (inReach(actor)) attackTarget(actor);
                else tell("대상이 한 칸 안에 있어야 평타를 쓸 수 있습니다.");
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
        if (!canAct() || !"active".equals(state.status())) return;
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
        if (readyAt("attack") <= NativeClient.dungeonNow()) {
            actorViews.get(self.id()).previewAttack(target.x() - self.x(), target.y() - self.y());
        }
        NativeClient.dungeonAttack(target.id());
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
        if (!canAct()) {
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
        return onHotkey(event.code, event.pressed) || movement.onKey(event.code, event.pressed);
    }

    private boolean onHotkey(int key, boolean pressed) {
        if (key == Input.Keys.F1) {
            if (pressed && !helpHeld) toggleHelp();
            helpHeld = pressed;
            return true;
        }
        if (key == Input.Keys.SPACE) {
            if (pressed && !attackHeld) attack();
            attackHeld = pressed;
            return true;
        }
        if (key == Input.Keys.E) {
            if (pressed && !rationHeld) eat();
            rationHeld = pressed;
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
        helpHeld = attackHeld = rationHeld = false;
        super.onPause();
    }

    private void toggleHelp() {
        help.visible = !help.visible;
        helpButton.text(help.visible ? "F1 접기" : "F1 조작법");
    }

    private void leave() {
        if (leaving) return;
        leaving = true;
        NativeClient.returnToInn();
    }

    @Override
    protected void onBackPressed() {
        leave();
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
            String nick = actor.name();
            int length = nick.codePointCount(0, nick.length());
            if (length > 11) nick = nick.substring(0, nick.offsetByCodePoints(0, 10)) + "…";
            name.hardlight(own ? SELF : TEXT);
            setText(name, own ? nick + " (나)" : nick);
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
