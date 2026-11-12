// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTitledMessage;
import com.watabou.input.KeyEvent;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.PointerArea;
import com.watabou.noosa.TextInput;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.RectF;
import com.watabou.utils.Signal;

import java.util.List;

public final class NativeInnScene extends PixelScene {
    private static final int BACKGROUND = 0xff1b1515;
    private static final int BORDER = 0xff6b4b3d;
    private static final int FIELD = 0xe6221a19;
    private static final int GOLD = 0xefbb77;
    private static final int CREAM = 0xf9e5c8;
    private static final int MUTED = 0xcab29d;
    private static final int GREEN = 0xb1d496;
    private static final float EDGE = 8;

    private record PredictedStep(String playerId, InnMotion.Cell origin, InnMotion.Cell target) {
        boolean awaiting(InnApi.State state) {
            if (!playerId.equals(state.selfId())) return false;
            InnApi.Person self = state.self();
            return self.x() == origin.x() && self.y() == origin.y();
        }
    }

    private final KeyboardMovement keyboard = new KeyboardMovement();
    private final Signal.Listener<KeyEvent> movementKeys = this::onMovementKey;
    private NativeInnBoard board;
    private ChatRows chatRows;
    private ScrollPane chat;
    private Group chatHud;
    private Group help;
    private FlatButton profile;
    private FlatButton expeditions;
    private FlatButton drink;
    private FlatButton compose;
    private FlatButton send;
    private TextInput editor;
    private RenderedTextBlock presence;
    private RenderedTextBlock notice;
    private LobbySession.Snapshot displayed;
    private List<InnApi.Event> displayedChat = List.of();
    private InnMotion.Cell destination;
    private PredictedStep predictedStep;
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
        KeyEvent.addKeyListener(movementKeys);
        float w = Camera.main.width;
        float h = Camera.main.height;
        RectF insets = getCommonInsets();
        float left = insets.left + EDGE;
        float top = insets.top + EDGE;
        float right = w - insets.right - EDGE;
        float bottom = h - insets.bottom - EDGE;
        add(block(0, 0, w, h, BACKGROUND));
        board = new NativeInnBoard(0, 0, w, h,
            cell -> { if (editor == null && !modalOpen) destination = cell; }, this::joinSeeking, this::speakToNpc);
        add(board);

        add(block(left, top, 76, 26, FIELD));
        add(block(left, top, 76, 1, BORDER));
        add(label("여관", 11, GOLD, left + 5, top + 3));
        presence = label("서버 연결 중", 6, MUTED, left + 5, top + 16);
        add(presence);
        profile = button("모험가", right - 89, top, 47, 17, false, this::editProfile);
        button("F1 도움말", right - 40, top, 40, 17, false, this::toggleHelp);

        float toolbarY = bottom - 18;
        expeditions = button("원정", right - 52, toolbarY, 52, 18, true, this::openExpeditions);
        drink = button("술 한 잔", right - 94, toolbarY, 40, 18, false, () -> {
            LobbySession session = NativeClient.inn();
            if (session != null) session.drink();
        });
        button("대화", right - 130, toolbarY, 34, 18, false, this::toggleChat);

        chatHud = new Group();
        add(chatHud);
        float chatWidth = Math.min(165, right - left);
        float chatHeight = Math.min(50, (bottom - top) * .28f);
        float chatY = toolbarY - chatHeight - 28;
        ColorBlock chatBackdrop = block(left, chatY, chatWidth, chatHeight + 26, FIELD);
        chatHud.add(chatBackdrop);
        chatHud.add(new PointerArea(chatBackdrop));
        chatHud.add(block(left, chatY, chatWidth, 1, BORDER));
        chatHud.add(label("여관 대화", 7, GOLD, left + 4, chatY + 3));
        chatRows = new ChatRows(chatWidth - 10);
        chat = new ScrollPane(chatRows);
        chatHud.add(chat);
        chat.setRect(left + 4, chatY + 12, chatWidth - 8, chatHeight);
        compose = button(chatHud, "대화 입력 · Enter", left + 3, toolbarY - 15, chatWidth - 6, 12, false, this::openComposer);
        send = button(chatHud, "보내기", left + chatWidth - 28, toolbarY - 15, 25, 12, true, () -> submitPending = true);
        send.visible = false;
        updateChatVisibility();

        notice = label("", 7, CREAM, left, toolbarY + 2);
        notice.maxWidth((int) Math.max(65, right - left - 140));
        add(notice);
        help = buildHelp(left, top + 30, Math.min(124, right - left));
        add(help);
        help.visible = help.active = false;
        showSnapshot();
        fadeIn();
    }

    @Override
    public void update() {
        showSnapshot();
        if (!modalOpen && Gdx.input.isKeyJustPressed(Input.Keys.F1)) toggleHelp();
        if (editor != null) {
            if (!Gdx.input.isKeyPressed(Input.Keys.ENTER)) openingEnterHeld = false;
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) closeComposer();
            else if (submitPending) submitChat();
        } else if (!modalOpen) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !altHeld()) {
                openComposer();
            } else {
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
        clearPredictedStep();
        super.onPause();
    }

    @Override
    public void destroy() {
        KeyEvent.removeKeyListener(movementKeys);
        predictedStep = null;
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
            if (predictedStep != null && (!online || !predictedStep.awaiting(state))) {
                predictedStep = null;
            }
            board.showState(state, history, testMode, predictedStep == null ? null : predictedStep.target(), NativeClient.appearance());
            replace(presence, online ? "접속 " + state.people().size() + "명" : profileConfirmed ? "서버 연결 중" : "프로필 설정");
            presence.hardlight(online ? GREEN : MUTED);
            expeditions.caption(state != null && state.match() != null ? "연결 중" :
                state != null && state.queued() ? "동료 찾는 중" : "원정");
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
        if (predictedStep != null || session == null || !session.canRequestMove()) return;
        KeyboardMovement.Input input = keyboard.next(0);
        int dx = input == null ? 0 : input.dx();
        int dy = input == null ? 0 : input.dy();
        InnApi.State state = displayed.state();
        InnApi.Person self = state.self();
        InnMotion.Cell from = new InnMotion.Cell(self.x(), self.y());
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
        PredictedStep step = new PredictedStep(self.id(), from, new InnMotion.Cell(from.x() + dx, from.y() + dy));
        if (session.moveBy(dx, dy, accepted -> Gdx.app.postRunnable(() -> finishMove(step, accepted)))) {
            predictedStep = step;
            board.moveSelfVisual(step.target());
        }
    }

    private boolean onMovementKey(KeyEvent event) {
        if (editor != null || modalOpen) return false;
        if (event.pressed && (event.code == Input.Keys.PLUS || event.code == Input.Keys.EQUALS)) {
            board.zoom(1);
            return true;
        }
        if (event.pressed && event.code == Input.Keys.MINUS) {
            board.zoom(-1);
            return true;
        }
        return keyboard.onKey(event.code, event.pressed);
    }

    private void finishMove(PredictedStep step, boolean accepted) {
        if (Game.scene() != this || predictedStep != step) return;
        showSnapshot();
        if (!accepted && predictedStep == step) clearPredictedStep();
    }

    private void clearPredictedStep() {
        if (predictedStep == null) return;
        predictedStep = null;
        board.moveSelfVisual(null);
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
        modalOpen = true;
        add(new WndOptions(Icons.STAIRS.get(), "원정", "지역  " + NativeClient.expeditionThemeTitle(NativeClient.expeditionTheme()),
            current != null && current.queued() ? "동료 찾기 취소" : "동료 찾기 · 2인 원정",
            "1인 원정", "길잡이 연습", "지역 변경", friendlyFireCaption(),
            NativeClient.isTestMode() ? "여관 길잡이 숨기기" : "여관 길잡이 부르기", "원본 1인 저장") {
            @Override
            protected boolean enabled(int index) {
                return switch (index) {
                    case 0 -> canQueue;
                    case 1, 2, 6 -> canEnter;
                    case 3, 4 -> canChoose;
                    default -> true;
                };
            }

            @Override
            protected void onSelect(int index) {
                switch (index) {
                    case 0 -> match();
                    case 1 -> NativeClient.enterSoloAtb();
                    case 2 -> NativeClient.enterPractice();
                    case 3 -> chooseTheme();
                    case 4 -> {
                        NativeClient.toggleFriendlyFire();
                        openExpeditions();
                    }
                    case 5 -> toggleTestMode();
                    case 6 -> NativeClient.enterSolo();
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
        modalOpen = true;
        add(new WndTitledMessage(Icons.INFO.get(), npc.name(), npc.dialogue()) {
            @Override
            public void destroy() {
                modalOpen = false;
                super.destroy();
            }
        });
    }

    private void chooseTheme() {
        if (modalOpen || !NativeClient.canChooseExpeditionTheme()) return;
        closeComposer();
        destination = null;
        keyboard.reset();
        modalOpen = true;
        add(new WndOptions("ATB 원정 지역", "1인 원정과 길잡이 연습에 적용됩니다. 2인 원정은 파티장의 선택을 따릅니다.",
                NativeClient.expeditionThemeTitle(DungeonLayout.SEWERS),
                NativeClient.expeditionThemeTitle(DungeonLayout.PRISON)) {
            @Override
            protected void onSelect(int index) {
                NativeClient.chooseExpeditionTheme(index == 1 ? DungeonLayout.PRISON : DungeonLayout.SEWERS);
                openExpeditions();
            }

            @Override
            public void destroy() {
                modalOpen = false;
                super.destroy();
            }
        });
    }

    private void toggleHelp() {
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
        ColorBlock back = block(x, y, w, 61, 0xe96b4b3d);
        overlay.add(back);
        overlay.add(new PointerArea(back));
        overlay.add(block(x + 1, y + 1, w - 2, 59, 0xe6211918));
        overlay.add(label("조작 방법", 8, GOLD, x + 5, y + 4));
        overlay.add(label("이동  WASD / 방향키 / 숫자패드", 6, CREAM, x + 5, y + 16));
        overlay.add(label("대화  Enter · Esc 취소", 6, CREAM, x + 5, y + 26));
        overlay.add(label("동료 찾기  동료 구함 표지 클릭", 6, CREAM, x + 5, y + 36));
        overlay.add(label("확대/축소  휠 · +/- · NPC 대화  클릭", 6, CREAM, x + 5, y + 46));
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

    private FlatButton button(String text, float x, float y, float w, float h, boolean primary, Runnable click) {
        return button(this, text, x, y, w, h, primary, click);
    }

    private FlatButton button(Group parent, String text, float x, float y, float w, float h, boolean primary, Runnable click) {
        FlatButton button = new FlatButton(text, primary, click);
        parent.add(button);
        button.setRect(x, y, w, h);
        return button;
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

    private static final class FlatButton extends StyledButton {
        private final Runnable click;

        FlatButton(String title, boolean primary, Runnable click) {
            super(primary ? Chrome.Type.RED_BUTTON : Chrome.Type.GREY_BUTTON_TR, title, 7);
            this.click = click;
        }

        void caption(String value) {
            if (!value.equals(text())) text(value);
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
}
