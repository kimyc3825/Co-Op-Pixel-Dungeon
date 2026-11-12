// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.StartScene;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

final class NativeClient {
    private enum View { INN, ENTERING, DUNGEON, SOLO }

    private static final Preferences SETTINGS = Preferences.userNodeForPackage(NativeClient.class);
    private static InnApi api;
    private static LobbySession inn;
    private static volatile LobbySession.Snapshot innSnapshot;
    private static DungeonSession dungeon;
    private static DungeonSession.Frame dungeonFrame;
    private static View view = View.INN;
    private static String ignoredMatch;
    private static String innNotice = "";
    private static long noticeUntil;
    private static String nickname;
    private static String expeditionTheme = DungeonLayout.SEWERS;
    private static boolean testMode;
    private static boolean leftInn;
    private static volatile boolean shuttingDown;

    private NativeClient() {}

    static void start(InnApi connection) {
        api = connection;
        nickname = SETTINGS.get("nickname", "");
        if (!LobbySession.validName(nickname)) nickname = LobbySession.guestName();
        expeditionTheme = DungeonLayout.themeOrDefault(SETTINGS.get("expeditionTheme", DungeonLayout.SEWERS));
        inn = new LobbySession(api, nickname, snapshot -> {
            if (!shuttingDown) Gdx.app.postRunnable(() -> onInn(snapshot));
        });
    }

    private static void onInn(LobbySession.Snapshot snapshot) {
        innSnapshot = snapshot;
        if (view != View.INN || !snapshot.online() || snapshot.state().match() == null) return;
        String matchId = snapshot.state().match().id();
        if (!matchId.equals(ignoredMatch)) enterDungeon(matchId, null);
    }

    static LobbySession.Snapshot innSnapshot() { return innSnapshot; }
    static LobbySession inn() { return inn; }
    static String nickname() { return nickname; }
    static boolean isTestMode() { return testMode; }
    static void setTestMode(boolean enabled) { testMode = enabled; }

    static String expeditionTheme() { return expeditionTheme; }

    static String expeditionThemeTitle(String theme) {
        return DungeonLayout.PRISON.equals(theme) ? "버려진 감옥" : "하수도";
    }

    static boolean canChooseExpeditionTheme() {
        InnApi.State current = innSnapshot == null ? null : innSnapshot.state();
        return view == View.INN && (current == null || !current.queued() && current.match() == null);
    }

    static void chooseExpeditionTheme(String theme) {
        if (!canChooseExpeditionTheme()) return;
        expeditionTheme = DungeonLayout.themeOrDefault(theme);
        SETTINGS.put("expeditionTheme", expeditionTheme);
    }

    static void rename(String value) {
        if (!LobbySession.validName(value)) {
            notice("닉네임을 1~20자로 입력하세요.");
            return;
        }
        nickname = value;
        SETTINGS.put("nickname", value);
        inn.rename(value);
    }

    static String innNotice() {
        return view == View.ENTERING || System.nanoTime() < noticeUntil ? innNotice : "";
    }

    static void enterPractice() {
        if (view != View.INN) return;
        InnApi.Person player = innSnapshot != null && innSnapshot.online()
            ? innSnapshot.state().self()
            : new InnApi.Person(UUID.randomUUID().toString(), nickname, 0, 0, false);
        enterDungeon(null, player);
    }

    private static void enterDungeon(String matchId, InnApi.Person practicePlayer) {
        ignoredMatch = matchId;
        view = View.ENTERING;
        leftInn = false;
        innNotice = matchId == null ? "길잡이와 던전에 들어가는 중…" : "파티 던전에 들어가는 중…";
        dungeon = new DungeonSession(api, inn.sessionKey(), matchId, practicePlayer, expeditionTheme, (source, frame) -> {
            if (!shuttingDown) Gdx.app.postRunnable(() -> onDungeon(source, frame));
        });
    }

    private static void onDungeon(DungeonSession source, DungeonSession.Frame frame) {
        if (source != dungeon) return;
        if (frame.state() == null) {
            source.close();
            dungeon = null;
            view = View.INN;
            if (ignoredMatch != null) inn.matchmaking(false);
            notice(frame.notice());
            return;
        }
        dungeonFrame = frame;
        if (!leftInn && !frame.state().waitingForPartner() && "active".equals(frame.state().status())) {
            leftInn = true;
            inn.enterDungeon();
        }
        if (view == View.ENTERING) {
            view = View.DUNGEON;
            ShatteredPixelDungeon.switchNoFade(NativeDungeonScene.class);
        }
    }

    static InnApi.DungeonState dungeonState() { return dungeonFrame == null ? null : dungeonFrame.state(); }
    static long dungeonNow() { return dungeonFrame == null ? 0 : dungeonFrame.serverTime(); }
    static long dungeonWallNow() { return dungeonFrame == null ? 0 : dungeonFrame.wallTime(); }
    static String dungeonNotice() { return dungeonFrame == null ? "" : dungeonFrame.notice(); }
    static String dungeonConnection() { return dungeonFrame == null ? "" : dungeonFrame.connection().label; }

    static void dungeonMove(int dx, int dy) {
        if (view == View.DUNGEON) dungeon.action(new InnApi.Action("move", dx, dy, null));
    }

    static void dungeonAttack(String targetId) {
        if (view == View.DUNGEON && targetId != null) dungeon.action(new InnApi.Action("attack", 0, 0, targetId));
    }

    static void dungeonEat() {
        if (view == View.DUNGEON) dungeon.action(new InnApi.Action("eat", 0, 0, null));
    }

    static void toggleDungeonPause() {
        InnApi.DungeonState state = dungeonState();
        if (view == View.DUNGEON && state != null) {
            dungeon.action(new InnApi.Action("pause", 0, 0, state.pausedUntil() > 0 ? "resume" : "begin"));
        }
    }

    static void dungeonChat(String message) {
        if (view == View.DUNGEON && message != null && !message.isBlank()) {
            dungeon.action(new InnApi.Action("chat", 0, 0, message));
        }
    }

    static void enterSolo() {
        if (view != View.INN) return;
        view = View.SOLO;
        leftInn = true;
        inn.enterDungeon();
        ShatteredPixelDungeon.switchNoFade(StartScene.class);
    }

    static boolean inSolo() { return view == View.SOLO; }

    static void returnToInn() {
        returnToInn(null);
    }

    static void returnToInn(String message) {
        if (view == View.INN) return;
        if (dungeon != null) dungeon.close();
        dungeon = null;
        dungeonFrame = null;
        view = View.INN;
        if (leftInn) {
            innSnapshot = null;
            inn.returnFromDungeon(true);
        }
        leftInn = false;
        if (message != null) notice(message);
        ShatteredPixelDungeon.switchNoFade(NativeInnScene.class);
    }

    private static void notice(String text) {
        innNotice = text;
        noticeUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    }

    static void shutdown() {
        shuttingDown = true;
        if (dungeon != null) dungeon.close();
        if (inn != null) inn.close();
    }
}
