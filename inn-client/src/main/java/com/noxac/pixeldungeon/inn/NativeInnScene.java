// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndSettings;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTitledMessage;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.NinePatch;
import com.watabou.noosa.PointerArea;
import com.watabou.noosa.TextInput;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.RectF;

import java.util.List;
import java.util.function.IntConsumer;

public final class NativeInnScene extends PixelScene {
    private static final int BACKGROUND = 0xff1b1515;
    private static final int GOLD = 0xefbb77;
    private static final int CREAM = 0xf9e5c8;
    private static final int MUTED = 0xcab29d;
    private static final int GREEN = 0xb1d496;
    private static final float EDGE = 8;
    private static final int TOOL_HEIGHT = 26;
    private static final int TOOL_CAPTION_HEIGHT = 9;
    private static final int TOOLBAR_WIDTH = 64;

    private record PredictedStep(String playerId, InnMotion.Cell origin, InnMotion.Cell target) {
        boolean startsAt(InnApi.State state) {
            InnApi.Person self = state.self();
            return playerId.equals(self.id()) && self.x() == origin.x() && self.y() == origin.y();
        }
    }

    private final KeyboardMovement keyboard = new KeyboardMovement();
    private final InputAdapter movementKeys = new InputAdapter() {
        @Override public boolean keyDown(int code) { return onMovementKey(code, true); }
        @Override public boolean keyUp(int code) { return onMovementKey(code, false); }
    };
    private NativeInnBoard board;
    private ChatRows chatRows;
    private ScrollPane chat;
    private Group chatHud;
    private Group help;
    private InnButton profile;
    private ToolbarButton expeditions;
    private ToolbarButton drink;
    private InnButton compose;
    private InnButton send;
    private TextInput editor;
    private RenderedTextBlock presence;
    private RenderedTextBlock notice;
    private RenderedTextBlock expeditionStatus;
    private LobbySession.Snapshot displayed;
    private List<InnApi.Event> displayedChat = List.of();
    private InnMotion.Cell destination;
    private PredictedStep sentStep;
    private PredictedStep queuedStep;
    private long nextStepAt;
    private boolean displayedTestMode;
    private boolean hasDisplayed;
    private boolean chatPinned;
    private boolean modalOpen;
    private boolean submitPending;
    private boolean openingEnterHeld;
    private String unsentChat = "";
    private long chatRevision;

    @Override
    public void create() {
        super.create();
        Game.inputHandler.addInputProcessor(movementKeys);
        float w = Camera.main.width;
        float h = Camera.main.height;
        RectF insets = getCommonInsets();
        float left = insets.left + EDGE;
        float top = insets.top + EDGE;
        float right = w - insets.right - EDGE;
        float bottom = h - insets.bottom - EDGE;
        boolean compactHud = right - left < 242;
        add(block(0, 0, w, h, BACKGROUND));
        board = new NativeInnBoard(0, 0, w, h,
            cell -> { if (editor == null && !modalOpen) destination = cell; }, this::joinSeeking, this::speakToNpc,
            this::openExpeditions);
        add(board);

        add(chrome(Chrome.Type.TOAST_TR_HEAVY, left, top, 76, 26));
        add(label("여관", 9, GOLD, left + 5, top + 4));
        presence = label("서버 연결 중", 6, MUTED, left + 5, top + 15);
        add(presence);
        button("F1 배낭", compactHud ? left : right - 123, compactHud ? top + 29 : top,
            41, 16, Icons.BACKPACK.get(), this::openBackpack);
        button("설정", compactHud ? left + 43 : right - 158, compactHud ? top + 29 : top,
            33, 16, Icons.PREFS.get(), this::openSettings);
        profile = button("모험가", right - 80, top, 36, 16, Icons.TALENT.get(), this::editProfile);
        button("F8 도움말", right - 42, top, 42, 16, Icons.INFO.get(), this::toggleHelp);

        float toolbarY = bottom - TOOL_HEIGHT - TOOL_CAPTION_HEIGHT;
        expeditions = tool("원정", right - 24, toolbarY, 0, 24, Icons.STAIRS.get(), this::openExpeditions);
        drink = tool("술 한잔", right - 44, toolbarY, 24, 20, new ItemSprite(ItemSpriteSheet.POTION_AMBER), () -> {
            LobbySession session = NativeClient.inn();
            if (session != null) session.drink();
        });
        tool("대화", right - TOOLBAR_WIDTH, toolbarY, 44, 20, Icons.NEWS.get(), this::toggleChat);
        expeditionStatus = label("", 6, GOLD, right - TOOLBAR_WIDTH, toolbarY - 11);
        add(expeditionStatus);

        chatHud = new Group();
        add(chatHud);
        float chatWidth = Math.min(165, right - left);
        float chatHeight = Math.min(50, (bottom - top) * .28f);
        float chatY = toolbarY - chatHeight - 28;
        NinePatch chatBackdrop = chrome(Chrome.Type.TOAST_TR_HEAVY, left, chatY, chatWidth, chatHeight + 26);
        chatHud.add(chatBackdrop);
        chatHud.add(new PointerArea(chatBackdrop));
        chatHud.add(label("여관 대화", 7, GOLD, left + 4, chatY + 3));
        chatRows = new ChatRows(chatWidth - 10);
        chat = new ScrollPane(chatRows);
        chatHud.add(chat);
        chat.setRect(left + 4, chatY + 12, chatWidth - 8, chatHeight);
        compose = button(chatHud, "대화 입력 · Enter", left + 3, toolbarY - 15, chatWidth - 6, 12, this::openComposer);
        send = button(chatHud, "보내기", left + chatWidth - 28, toolbarY - 15, 25, 12, () -> submitPending = true);
        send.visible = false;
        updateChatVisibility();

        notice = label("", 7, CREAM, left, toolbarY + 2);
        notice.maxWidth((int) Math.max(65, right - left - 140));
        add(notice);
        String defeat = NativeClient.takeDefeatNotice();
        if (defeat != null && !defeat.isBlank()) {
            add(defeatNotice(defeat, left, top + (compactHud ? 50 : 30), right));
        }
        help = buildHelp(left, top + (compactHud ? 50 : 30), Math.min(124, right - left));
        add(help);
        help.visible = help.active = false;
        showSnapshot();
        fadeIn();
    }

    @Override
    public void update() {
        showSnapshot();
        sendQueuedStep(NativeClient.inn());
        if (editor != null) {
            if (!Gdx.input.isKeyPressed(Input.Keys.ENTER)) openingEnterHeld = false;
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) closeComposer();
            else if (submitPending) submitChat();
        } else if (!modalOpen) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.F1) || Gdx.input.isKeyJustPressed(Input.Keys.I)) {
                openBackpack();
            } else if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !altHeld()) {
                openComposer();
            } else if (Gdx.input.isKeyJustPressed(Input.Keys.F) && board.nearGate()) {
                openExpeditions();
            } else {
                if (Gdx.input.isKeyJustPressed(Input.Keys.F8)) toggleHelp();
                if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) destination = null;
                move();
            }
        }
        super.update();
    }

    @Override
    protected void onBackPressed() {
        if (editor != null) closeComposer();
        else if (help.visible) toggleHelp();
        else if (chatPinned) toggleChat();
    }

    @Override
    public void onPause() {
        destination = null;
        keyboard.reset();
        queuedStep = null;
        nextStepAt = 0;
        board.correctSelfVisual(sentStep == null ? null : sentStep.target());
        super.onPause();
    }

    @Override
    public void destroy() {
        Game.inputHandler.removeInputProcessor(movementKeys);
        queuedStep = sentStep = null;
        super.destroy();
    }

    private void showSnapshot() {
        LobbySession.Snapshot snapshot = NativeClient.innSnapshot();
        boolean testMode = NativeClient.isTestMode();
        boolean profileConfirmed = NativeClient.profileConfirmed();
        profile.enabled(!profileConfirmed || NativeClient.canChooseExpeditionTheme());
        expeditions.enabled(profileConfirmed);
        if (!hasDisplayed || snapshot != displayed || testMode != displayedTestMode) {
            boolean online = snapshot != null && snapshot.online() && snapshot.state() != null;
            InnApi.State state = snapshot == null ? null : snapshot.state();
            List<InnApi.Event> history = snapshot == null ? List.of() : snapshot.history();
            if (!displayedChat.equals(history)) {
                float scroll = chatRows.camera.scroll.y;
                boolean bottom = scroll + chat.height() >= chatRows.height() - 5;
                chatRows.rebuild(history);
                chat.setSize(chat.width(), chat.height());
                chat.scrollTo(0, bottom ? chatRows.height() : scroll);
                displayedChat = history;
            }
            PredictedStep visualStep = visualStep();
            if (visualStep != null && (!online || !visualStep.playerId().equals(state.selfId()))) {
                queuedStep = sentStep = visualStep = null;
                nextStepAt = 0;
            }
            board.showState(state, history, testMode, visualStep == null ? null : visualStep.target(), NativeClient.appearance());
            replace(presence, online ? "접속 " + state.people().size() + "명" : profileConfirmed ? "서버 연결 중" : "프로필 설정");
            presence.hardlight(online ? GREEN : MUTED);
            replace(expeditionStatus, state != null && state.match() != null ? "연결 중" :
                state != null && state.queued() ? "동료 찾는 중" : "");
            drink.enabled(online);
            compose.enabled(online);
            send.enabled(online);
            if (!online) closeComposer();
            displayed = snapshot;
            displayedTestMode = testMode;
            hasDisplayed = true;
        }

        String status = NativeClient.innNotice();
        if (status == null || status.isBlank()) {
            if (!profileConfirmed) status = "프로필을 적용하면 여관에 입장합니다.";
            else if (snapshot == null) status = "여관에 접속하는 중…";
            else if (!snapshot.notice().isBlank()) status = snapshot.notice();
            else if (!snapshot.online()) status = snapshot.networkStatus();
            else status = snapshot.matchStatus();
        }
        replace(notice, status == null ? "" : status);
    }

    private void move() {
        if (displayed == null || !displayed.online() || displayed.state() == null) {
            keyboard.reset();
            return;
        }
        keyboard.sample(Gdx.input::isKeyPressed);
        if (keyboard.active()) destination = null;
        LobbySession session = NativeClient.inn();
        long now = System.nanoTime();
        if (queuedStep != null || session == null || nextStepAt != 0 && now - nextStepAt < 0) return;
        KeyboardMovement.Input input = keyboard.next(0);
        int dx = input == null ? 0 : input.dx();
        int dy = input == null ? 0 : input.dy();
        InnApi.State state = displayed.state();
        InnApi.Person self = state.self();
        PredictedStep previous = visualStep();
        InnMotion.Cell from = previous == null ? new InnMotion.Cell(self.x(), self.y()) : previous.target();
        if (dx == 0 && dy == 0 && destination != null) {
            List<InnMotion.Cell> route = InnMotion.path(from, destination, state);
            if (route.isEmpty()) {
                destination = null;
                return;
            }
            dx = route.getFirst().x() - from.x();
            dy = route.getFirst().y() - from.y();
        }
        if (dx == 0 && dy == 0) return;
        InnMotion.Cell to = new InnMotion.Cell(from.x() + dx, from.y() + dy);
        if (!InnMotion.canStep(state, from, to)) {
            if (dx != 0 && dy != 0 && state.walkable(to.x(), from.y())) dy = 0;
            else if (dx != 0 && dy != 0 && state.walkable(from.x(), to.y())) dx = 0;
            else return;
        }
        queuedStep = new PredictedStep(self.id(), from, new InnMotion.Cell(from.x() + dx, from.y() + dy));
        long cadenceStart = nextStepAt != 0 && now - nextStepAt < InnMotion.LOCAL_FRAME_SLACK ? nextStepAt : now;
        nextStepAt = cadenceStart + LobbySession.MOVE_INTERVAL;
        board.moveSelfVisual(queuedStep.target(), now);
        sendQueuedStep(session);
    }

    private boolean onMovementKey(int code, boolean pressed) {
        if (editor != null || modalOpen) return false;
        if (pressed && (code == Input.Keys.PLUS || code == Input.Keys.EQUALS)) {
            board.zoom(1);
            return true;
        }
        if (pressed && code == Input.Keys.MINUS) {
            board.zoom(-1);
            return true;
        }
        return keyboard.onKey(code, pressed);
    }

    private PredictedStep visualStep() {
        return queuedStep == null ? sentStep : queuedStep;
    }

    private void sendQueuedStep(LobbySession session) {
        if (queuedStep == null || sentStep != null || session == null || !session.canRequestMove()) return;
        PredictedStep step = queuedStep;
        if (!step.startsAt(displayed.state())) {
            correctMovement();
            return;
        }
        if (session.moveTo(step.origin().x(), step.origin().y(), step.target().x(), step.target().y(),
                accepted -> Gdx.app.postRunnable(() -> finishMove(step, accepted)))) {
            sentStep = step;
            queuedStep = null;
        }
    }

    private void finishMove(PredictedStep step, boolean accepted) {
        if (Game.scene() != this || sentStep != step) return;
        sentStep = null;
        if (!accepted) correctMovement();
        showSnapshot();
    }

    private void correctMovement() {
        queuedStep = null;
        nextStepAt = System.nanoTime() + LobbySession.MOVE_INTERVAL;
        board.correctSelfVisual(null);
    }

    private void match() {
        LobbySession session = NativeClient.inn();
        if (session != null && displayed != null && displayed.online()) {
            InnApi.State state = displayed.state();
            session.matchmaking(!state.queued() && state.match() == null);
        }
    }

    private void joinSeeking(String id) {
        LobbySession session = NativeClient.inn();
        if (!modalOpen && editor == null && session != null && displayed != null && displayed.online()) {
            session.joinSeeking(id);
        }
    }

    private void openBackpack() {
        if (modalOpen || editor != null) return;
        destination = null;
        keyboard.reset();
        boolean online = displayed != null && displayed.online();
        InnApi.Preparation preparation = online ? NativeClient.preparation() : null;
        int startingFood = Math.max(0, 1 + InnApi.Preparation.rations(preparation));
        int health = InnApi.Preparation.health(preparation);
        String summary = "다음 ATB 원정 · 미리보기\n시작 식량 " + startingFood + "개";
        if (health != 0) summary += "\n최대 체력 " + (health > 0 ? "+" : "") + health;
        if (!online) summary += "\n연결 후 원정 준비를 반영합니다.";
        modalOpen = true;
        add(new WndOptions(Icons.BACKPACK.get(), "배낭", summary, "닫기") {
            @Override
            public void destroy() {
                modalOpen = false;
                super.destroy();
            }
        });
    }

    private void openSettings() {
        if (modalOpen || editor != null) return;
        destination = null;
        keyboard.reset();
        modalOpen = true;
        add(new WndSettings() {
            @Override
            public void destroy() {
                modalOpen = false;
                super.destroy();
            }
        });
    }

    private void openExpeditions() {
        if (modalOpen || !NativeClient.profileConfirmed()) return;
        closeComposer();
        destination = null;
        keyboard.reset();
        boolean online = displayed != null && displayed.online();
        InnApi.State current = online ? displayed.state() : null;
        boolean canQueue = current != null && current.match() == null;
        boolean canChoose = NativeClient.canChooseExpeditionTheme();
        boolean canEnter = NativeClient.canEnterSolo();
        InnApi.Preparation preparation = current == null ? null : current.preparation();
        String summary = "원본 던전 · 하수도 1층에서 시작";
        if (preparation != null) summary += "\n다음 원정 · " + preparation.description();
        modalOpen = true;
        add(new WndOptions(Icons.STAIRS.get(), "원정", summary,
            current != null && current.queued() ? "동료 찾기 취소" : "동료 찾기 · 원본 2인 ATB",
            "1인 원정 · 원본 ATB", "길잡이 연습", friendlyFireCaption(),
            NativeClient.isTestMode() ? "여관 길잡이 숨기기" : "여관 길잡이 부르기", "원본 1인 저장", "원정 준비") {
            @Override
            protected boolean enabled(int index) {
                return switch (index) {
                    case 0 -> canQueue;
                    case 1, 2, 5 -> canEnter;
                    case 3 -> canChoose;
                    case 6 -> canChoose && current != null && preparation == null;
                    default -> true;
                };
            }

            @Override
            protected void onSelect(int index) {
                switch (index) {
                    case 0 -> match();
                    case 1 -> NativeClient.enterSoloAtb();
                    case 2 -> NativeClient.enterPractice();
                    case 3 -> {
                        NativeClient.toggleFriendlyFire();
                        openExpeditions();
                    }
                    case 4 -> toggleTestMode();
                    case 5 -> NativeClient.enterSolo();
                    case 6 -> NativeClient.prepareExpedition();
                    default -> { }
                }
            }

            @Override
            public void destroy() {
                modalOpen = false;
                super.destroy();
            }
        });
    }

    private void editProfile() {
        boolean required = !NativeClient.profileConfirmed();
        if (modalOpen || !required && !NativeClient.canChooseExpeditionTheme()) return;
        closeComposer();
        destination = null;
        keyboard.reset();
        modalOpen = true;
        add(new AvatarProfileWindow(NativeClient.nickname(), NativeClient.appearance(), required, NativeClient::saveProfile, () -> {
            modalOpen = false;
            hasDisplayed = false;
        }));
    }

    private static String friendlyFireCaption() {
        return NativeClient.friendlyFire() ? "아군 피해 · 켜짐" : "아군 피해 · 꺼짐";
    }

    private void speakToNpc(InnApi.Npc npc) {
        if (modalOpen) return;
        closeComposer();
        destination = null;
        keyboard.reset();
        if (!npc.lessons().isEmpty()) {
            openLessons(npc);
            return;
        }
        modalOpen = true;
        add(new WndTitledMessage(NativeInnBoard.npcPortrait(npc.role()), npc.name(), npc.dialogue()) {
            {
                IconButton close = new IconButton(Icons.CLOSE.get()) {
                    @Override
                    protected void onClick() {
                        hide();
                    }
                };
                close.setRect(width - 15, 0, 15, 15);
                add(close);
            }

            @Override
            public void destroy() {
                modalOpen = false;
                super.destroy();
            }
        });
    }

    private void openLessons(InnApi.Npc npc) {
        List<InnApi.Lesson> lessons = npc.lessons();
        String[] options = new String[lessons.size() + 1];
        for (int i = 0; i < lessons.size(); i++) options[i] = lessons.get(i).title();
        options[lessons.size()] = "닫기";
        showNpcWindow(npc, npc.name(), npc.dialogue(), selection -> {
            if (selection >= lessons.size()) return;
            InnApi.Lesson lesson = lessons.get(selection);
            showNpcWindow(npc, lesson.title(), lesson.text(), action -> {
                if (action == 0) openLessons(npc);
            }, "목록으로", "닫기");
        }, options);
    }

    private void showNpcWindow(InnApi.Npc npc, String title, String text, IntConsumer select, String... options) {
        modalOpen = true;
        add(new WndOptions(NativeInnBoard.npcPortrait(npc.role()), title, text, options) {
            @Override
            protected void onSelect(int index) {
                select.accept(index);
            }

            @Override
            public void destroy() {
                modalOpen = false;
                super.destroy();
            }
        });
    }

    private Group defeatNotice(String message, float left, float top, float right) {
        Group toast = new Group() {
            private float remaining = 8f;

            @Override
            public void update() {
                remaining -= Game.elapsed;
                if (remaining <= 0) {
                    remove();
                    destroy();
                } else {
                    super.update();
                }
            }
        };
        float width = Math.min(222, right - left);
        float x = align((left + right - width) / 2);
        RenderedTextBlock quote = renderTextBlock(8, false);
        quote.setHightlighting(false);
        quote.hardlight(CREAM);
        quote.text(message, Math.max(1, (int) width - 14));
        quote.setPos(x + 7, top + 17);
        ColorBlock backdrop = block(x, top, width, quote.height() + 24, 0xF5261C19);
        toast.add(backdrop);
        toast.add(block(x, top, width, 1, 0xE5AD8057));
        toast.add(label("원정에서 쓰러졌습니다", 8, GOLD, x + 7, top + 4));
        toast.add(quote);
        toast.add(new PointerArea(backdrop) {
            @Override
            protected void onClick(PointerEvent event) {
                toast.remove();
                toast.destroy();
            }
        });
        return toast;
    }

    private void toggleHelp() {
        if (modalOpen || editor != null) return;
        help.visible = help.active = !help.visible;
    }

    private void toggleChat() {
        if (modalOpen) return;
        if (editor != null) {
            chatPinned = false;
            closeComposer();
        } else {
            chatPinned = !chatPinned;
            updateChatVisibility();
        }
    }

    private void updateChatVisibility() {
        chatHud.visible = chatHud.active = chatPinned || editor != null;
    }

    private void toggleTestMode() {
        NativeClient.setTestMode(!NativeClient.isTestMode());
        showSnapshot();
    }

    private Group buildHelp(float x, float y, float w) {
        Group overlay = new Group();
        NinePatch back = chrome(Chrome.Type.TOAST_TR_HEAVY, x, y, w, 81);
        overlay.add(back);
        overlay.add(new PointerArea(back));
        overlay.add(label("조작 방법", 8, GOLD, x + 5, y + 4));
        overlay.add(label("이동  WASD / 방향키 / 숫자패드", 6, CREAM, x + 5, y + 16));
        overlay.add(label("대화  Enter · Esc 취소", 6, CREAM, x + 5, y + 26));
        overlay.add(label("동료 찾기  동료 구함 표지 클릭", 6, CREAM, x + 5, y + 36));
        overlay.add(label("확대/축소  휠 · +/- · NPC 대화  클릭", 6, CREAM, x + 5, y + 46));
        overlay.add(label("배낭  F1 / I · 도움말  F8", 6, CREAM, x + 5, y + 56));
        overlay.add(label("자동 이동 중지  Space", 6, CREAM, x + 5, y + 66));
        return overlay;
    }

    private void openComposer() {
        if (editor != null || modalOpen || displayed == null || !displayed.online()) return;
        destination = null;
        keyboard.reset();
        openingEnterHeld = Gdx.input.isKeyPressed(Input.Keys.ENTER);
        chatRevision++;
        editor = new TextInput(Chrome.get(Chrome.Type.TOAST_WHITE), false, defaultZoom * 7) {
            @Override
            public void enterPressed() {
                if (!openingEnterHeld && !altHeld()) submitPending = true;
            }
        };
        editor.setLeftAligned();
        editor.setMaxLength(160);
        if (!unsentChat.isEmpty()) {
            editor.setText(unsentChat);
            unsentChat = "";
        }
        compose.visible = false;
        send.visible = true;
        chatHud.add(editor);
        editor.setRect(compose.left(), compose.top(), compose.width() - send.width() - 3, compose.height());
        updateChatVisibility();
    }

    private void closeComposer() {
        if (editor == null) return;
        chatHud.erase(editor);
        editor.destroy();
        editor = null;
        submitPending = false;
        openingEnterHeld = false;
        destination = null;
        keyboard.reset();
        compose.visible = true;
        send.visible = false;
        updateChatVisibility();
    }

    private void submitChat() {
        submitPending = false;
        if (editor == null) return;
        String value = editor.getText().strip();
        closeComposer();
        if (value.isEmpty()) return;
        LobbySession session = NativeClient.inn();
        if (session == null) return;
        long revision = chatRevision;
        session.chat(value, sent -> Gdx.app.postRunnable(() -> {
            if (!sent && Game.scene() == this && editor == null && revision == chatRevision) unsentChat = value;
        }));
    }

    private static boolean altHeld() {
        return Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT);
    }

    private InnButton button(String text, float x, float y, float w, float h, Image icon, Runnable click) {
        InnButton button = button(this, Chrome.Type.GREY_BUTTON, text, x, y, w, h, click);
        icon.scale.set(0.5f);
        button.icon(icon);
        return button;
    }

    private InnButton button(Group parent, String text, float x, float y, float w, float h, Runnable click) {
        return button(parent, Chrome.Type.RED_BUTTON, text, x, y, w, h, click);
    }

    private InnButton button(Group parent, Chrome.Type type, String text, float x, float y, float w, float h, Runnable click) {
        InnButton button = new InnButton(type, text, click);
        parent.add(button);
        button.setRect(x, y, w, h);
        return button;
    }

    private ToolbarButton tool(String text, float x, float y, int sourceX, int sourceWidth, Image icon, Runnable click) {
        ToolbarButton button = new ToolbarButton(text, sourceX, sourceWidth, icon, click);
        add(button);
        button.setRect(x, y, sourceWidth, TOOL_HEIGHT + TOOL_CAPTION_HEIGHT);
        return button;
    }

    private static NinePatch chrome(Chrome.Type type, float x, float y, float w, float h) {
        NinePatch panel = Chrome.get(type);
        panel.x = x;
        panel.y = y;
        panel.size(w, h);
        return panel;
    }

    private static ColorBlock block(float x, float y, float w, float h, int color) {
        ColorBlock block = new ColorBlock(w, h, color);
        block.x = x;
        block.y = y;
        return block;
    }

    private static RenderedTextBlock label(String value, int size, int color, float x, float y) {
        RenderedTextBlock text = renderTextBlock(value, size);
        text.setHightlighting(false);
        text.hardlight(color);
        text.setPos(x, y);
        return text;
    }

    private static void replace(RenderedTextBlock label, String text) {
        label.visible = !text.isBlank();
        if (label.visible && !text.equals(label.text())) label.text(text);
    }

    private static final class ChatRows extends Component {
        private final float lineWidth;

        ChatRows(float lineWidth) {
            this.lineWidth = lineWidth;
            setSize(lineWidth, 1);
        }

        void rebuild(List<InnApi.Event> history) {
            for (Gizmo child : members.toArray(new Gizmo[0])) {
                if (child != null) child.destroy();
            }
            clear();
            float y = 1;
            for (InnApi.Event event : history) {
                boolean drink = "drink".equals(event.kind());
                String text = event.name() + (drink ? " 님이 술을 마셨습니다." : "  " + event.text());
                RenderedTextBlock row = label(text, 6, drink ? MUTED : CREAM, 0, y);
                row.maxWidth((int) lineWidth);
                add(row);
                y += row.height() + 3;
            }
            setSize(lineWidth, y);
        }
    }

    private static final class InnButton extends StyledButton {
        private final Runnable click;

        InnButton(Chrome.Type type, String title, Runnable click) {
            super(type, title, 7);
            this.click = click;
        }

        void enabled(boolean enabled) {
            enable(enabled);
        }

        @Override
        protected void onPointerDown() {
            PointerEvent.clearKeyboardThisPress = false;
            super.onPointerDown();
        }

        @Override
        protected void onClick() {
            if (visible && active) click.run();
        }
    }

    private static final class ToolbarButton extends Button {
        private final Image slot;
        private final Image icon;
        private final RenderedTextBlock caption;
        private final Runnable click;

        ToolbarButton(String title, int sourceX, int sourceWidth, Image icon, Runnable click) {
            this.icon = icon;
            this.click = click;
            slot = new Image(Assets.Interfaces.TOOLBAR, sourceX, 0, sourceWidth, TOOL_HEIGHT);
            caption = renderTextBlock(6, false);
            caption.text(title);
            caption.hardlight(CREAM);
            add(slot);
            add(icon);
            add(caption);
        }

        @Override
        protected void layout() {
            super.layout();
            if (slot == null) return;
            slot.x = align(x + (width - slot.width()) / 2);
            slot.y = y;
            icon.x = align(centerX() - icon.width() / 2);
            icon.y = align(y + (TOOL_HEIGHT - icon.height()) / 2);
            caption.setPos(align(centerX() - caption.width() / 2), y + TOOL_HEIGHT + 1);
        }

        void enabled(boolean enabled) {
            active = enabled;
            icon.alpha(enabled ? 1f : 0.4f);
            caption.alpha(enabled ? 1f : 0.4f);
        }

        @Override
        protected void onPointerDown() {
            PointerEvent.clearKeyboardThisPress = false;
            slot.brightness(1.4f);
        }

        @Override
        protected void onPointerUp() {
            slot.resetColor();
        }

        @Override
        protected void onClick() {
            if (visible && active) click.run();
        }
    }
}
