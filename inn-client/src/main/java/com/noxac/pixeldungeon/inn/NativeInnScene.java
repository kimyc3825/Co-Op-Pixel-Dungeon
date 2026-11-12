// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;
import com.watabou.input.KeyEvent;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.TextInput;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.Signal;

import java.util.List;

public final class NativeInnScene extends PixelScene {
    private static final int BACKGROUND = 0xff1b1515;
    private static final int BORDER = 0xff6b4b3d;
    private static final int CARD = 0xff302320;
    private static final int FIELD = 0xff221a19;
    private static final int GOLD = 0xefbb77;
    private static final int CREAM = 0xf9e5c8;
    private static final int MUTED = 0xcab29d;
    private static final int GREEN = 0xb1d496;
    private static final float EDGE = 8;
    private static final float GAP = 6;

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
    private Group help;
    private FlatButton mode;
    private FlatButton nick;
    private FlatButton match;
    private FlatButton solo;
    private FlatButton guide;
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
    private boolean editingName;
    private boolean submitPending;
    private boolean sending;

    @Override
    public void create() {
        super.create();
        KeyEvent.addKeyListener(movementKeys);
        float w = Camera.main.width;
        float h = Camera.main.height;
        add(block(0, 0, w, h, BACKGROUND));

        add(label("NOXAC PIXEL DUNGEON", 6, GOLD, EDGE, 7));
        add(label("여관", 13, CREAM, EDGE, 14));
        presence = label("서버 연결 중", 6, MUTED, EDGE + 31, 21);
        add(presence);
        FlatButton keys = button("F1 도움말", w - 44, 10, 36, 14, false, this::toggleHelp);
        nick = button("닉네임", w - 112, 10, 64, 14, false, this::rename);
        mode = button("테스트 모드", w - 165, 10, 49, 14, false, this::toggleTestMode);
        keys.enabled(true);

        float top = 34;
        float bottom = h - 14;
        float sidebar = Math.max(93, Math.min(154, w * .36f));
        float sideX = w - EDGE - sidebar;
        float boardWidth = sideX - EDGE - GAP;
        float panelHeight = bottom - top;
        panel(EDGE, top, boardWidth, panelHeight);
        board = new NativeInnBoard(EDGE + 1, top + 1, boardWidth - 2, panelHeight - 18,
            cell -> destination = cell, this::joinSeeking);
        add(board);
        drink = button("술 한 잔", EDGE + boardWidth - 40, bottom - 15, 36, 12, false, () -> {
            LobbySession session = NativeClient.inn();
            if (session != null) session.drink();
        });
        add(label("8방향 이동 · F1", 6, MUTED, EDGE + 4, bottom - 12));

        panel(sideX, top, sidebar, panelHeight);
        float panelX = sideX + 5;
        float panelWidth = sidebar - 10;
        match = button("동료 구함", panelX, top + 5, panelWidth, 14, true, this::match);
        solo = button("혼자 던전 입장", panelX, top + 22, panelWidth, 14, false, NativeClient::enterSolo);
        guide = button("길잡이와 입장", panelX + panelWidth / 2 + 1, top + 22, panelWidth / 2 - 1, 14,
            false, NativeClient::enterPractice);
        add(label("여관 대화", 7, GOLD, panelX, top + 42));

        float chatY = top + 52;
        float chatHeight = Math.max(13, bottom - chatY - 20);
        add(block(panelX, chatY, panelWidth, chatHeight, FIELD));
        chatRows = new ChatRows(panelWidth - 5);
        chat = new ScrollPane(chatRows);
        add(chat);
        chat.setRect(panelX + 2, chatY + 1, panelWidth - 4, chatHeight - 2);
        compose = button("대화 입력 · Enter", panelX, bottom - 16, panelWidth, 12, false, this::openComposer);
        send = button("보내기", panelX + panelWidth - 25, bottom - 16, 25, 12, true, () -> submitPending = true);
        send.visible = false;

        notice = label("", 6, MUTED, EDGE, h - 10);
        add(notice);
        help = buildHelp(EDGE + 4, top + 6, Math.min(124, boardWidth - 8));
        add(help);
        help.visible = false;
        showSnapshot();
        fadeIn();
    }

    @Override
    public void update() {
        showSnapshot();
        if (!editingName && Gdx.input.isKeyJustPressed(Input.Keys.F1)) toggleHelp();
        if (editor != null) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) closeComposer();
            else if (submitPending) submitChat();
        } else if (!editingName) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) &&
                    !Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) && !Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)) {
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
        else help.visible = false;
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
            board.showState(state, history, testMode, predictedStep == null ? null : predictedStep.target());
            replace(presence, online ? "접속 " + state.people().size() + "명" : "서버 연결 중");
            presence.hardlight(online ? GREEN : MUTED);
            nick.caption(shortName(NativeClient.nickname()));
            mode.caption(testMode ? "테스트 모드 켜짐" : "테스트 모드");
            solo.setSize(testMode ? match.width() / 2 - 1 : match.width(), solo.height());
            guide.visible = testMode;
            guide.enabled(testMode);
            match.enabled(online);
            match.caption(state != null && state.match() != null ? "던전 연결 중" :
                state != null && state.queued() ? "구함 취소" : "동료 구함");
            drink.enabled(online);
            compose.enabled(online);
            send.enabled(online && !sending);
            if (!online) closeComposer();
            displayed = snapshot;
            displayedTestMode = testMode;
            hasDisplayed = true;
        }

        String status = NativeClient.innNotice();
        if (status == null || status.isBlank()) {
            if (snapshot == null) status = "여관에 접속하는 중…";
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
        return editor == null && !editingName && keyboard.onKey(event.code, event.pressed);
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
        if (session != null && displayed != null && displayed.online()) session.joinSeeking(id);
    }

    private void rename() {
        if (editingName) return;
        closeComposer();
        destination = null;
        keyboard.reset();
        editingName = true;
        add(new WndTextInput("닉네임", null, NativeClient.nickname(), 20, false, "변경", "취소") {
            @Override
            public void onSelect(boolean accepted, String value) {
                if (accepted) NativeClient.rename(value.strip());
            }

            @Override
            public void onBackPressed() {
                hide();
            }

            @Override
            public void destroy() {
                editingName = false;
                super.destroy();
            }
        });
    }

    private void toggleHelp() {
        help.visible = !help.visible;
    }

    private void toggleTestMode() {
        NativeClient.setTestMode(!NativeClient.isTestMode());
        showSnapshot();
    }

    private Group buildHelp(float x, float y, float w) {
        Group overlay = new Group();
        overlay.add(block(x, y, w, 51, 0xe96b4b3d));
        overlay.add(block(x + 1, y + 1, w - 2, 49, 0xe6211918));
        overlay.add(label("조작 방법", 8, GOLD, x + 5, y + 4));
        overlay.add(label("이동  WASD / 방향키 / 숫자패드", 6, CREAM, x + 5, y + 16));
        overlay.add(label("대화  Enter", 6, CREAM, x + 5, y + 26));
        overlay.add(label("동료 찾기  동료 구함 표지 클릭", 6, CREAM, x + 5, y + 36));
        return overlay;
    }

    private void openComposer() {
        if (editor != null || editingName || displayed == null || !displayed.online()) return;
        destination = null;
        keyboard.reset();
        editor = new TextInput(Chrome.get(Chrome.Type.TOAST_WHITE), false, defaultZoom * 7) {
            @Override
            public void enterPressed() {
                submitPending = true;
            }
        };
        editor.setMaxLength(160);
        compose.visible = false;
        send.visible = true;
        addToFront(editor);
        editor.setRect(compose.left(), compose.top(), compose.width() - send.width() - 3, compose.height());
    }

    private void closeComposer() {
        if (editor == null) return;
        erase(editor);
        editor.destroy();
        editor = null;
        submitPending = false;
        compose.visible = true;
        send.visible = false;
    }

    private void submitChat() {
        submitPending = false;
        if (editor == null || sending) return;
        String value = editor.getText().strip();
        if (value.isBlank()) return;
        LobbySession session = NativeClient.inn();
        if (session == null) return;
        sending = true;
        TextInput input = editor;
        send.enabled(false);
        session.chat(value, sent -> Gdx.app.postRunnable(() -> {
            sending = false;
            if (editor == input && sent) closeComposer();
            send.enabled(displayed != null && displayed.online());
        }));
    }

    private void panel(float x, float y, float w, float h) {
        add(block(x, y, w, h, BORDER));
        add(block(x + 1, y + 1, w - 2, h - 2, CARD));
    }

    private FlatButton button(String text, float x, float y, float w, float h, boolean primary, Runnable click) {
        FlatButton button = new FlatButton(text, primary, click);
        add(button);
        button.setRect(x, y, w, h);
        return button;
    }

    private static String shortName(String name) {
        if (name == null || name.isBlank()) return "닉네임";
        int length = name.codePointCount(0, name.length());
        return length <= 11 ? name : name.substring(0, name.offsetByCodePoints(0, 10)) + "…";
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

    private static final class FlatButton extends Button {
        private final ColorBlock rim;
        private final ColorBlock fill;
        private final RenderedTextBlock label;
        private final Runnable click;

        FlatButton(String title, boolean primary, Runnable click) {
            this.click = click;
            rim = block(0, 0, 1, 1, primary ? 0xffd49653 : BORDER);
            fill = block(0, 0, 1, 1, primary ? 0xffedb56f : 0xff382925);
            label = label(title, 6, CREAM, 0, 0);
            addToBack(fill);
            addToBack(rim);
            add(label);
        }

        void caption(String text) {
            if (!text.equals(label.text())) {
                label.text(text);
                layout();
            }
        }

        void enabled(boolean enabled) {
            active = enabled;
            label.alpha(enabled ? 1 : .55f);
        }

        @Override
        protected void layout() {
            super.layout();
            rim.x = x;
            rim.y = y;
            rim.size(width, height);
            fill.x = x + 1;
            fill.y = y + 1;
            fill.size(width - 2, height - 2);
            label.setPos(align(x + (width - label.width()) / 2), align(y + (height - label.height()) / 2));
        }

        @Override
        protected void onPointerDown() {
            PointerEvent.clearKeyboardThisPress = false;
            fill.brightness(1.25f);
        }

        @Override
        protected void onPointerUp() {
            fill.resetColor();
        }

        @Override
        protected void onClick() {
            if (visible && active) click.run();
        }
    }
}
