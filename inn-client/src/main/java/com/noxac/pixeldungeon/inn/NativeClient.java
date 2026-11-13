// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.badlogic.gdx.Gdx;
import com.google.gson.Gson;
import com.shatteredpixel.shatteredpixeldungeon.InnAdventure;
import com.shatteredpixel.shatteredpixeldungeon.InnOriginalParty;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InnAdventureScene;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

final class NativeClient {
    private enum View { INN, ENTERING, ORIGINAL_PARTY, DUNGEON, SOLO }
    private record DefeatLine(String text, String author) {}

    private static final Preferences SETTINGS = Preferences.userNodeForPackage(NativeClient.class);
    private static InnApi api;
    private static LobbySession inn;
    private static volatile LobbySession.Snapshot innSnapshot;
    private static DungeonSession party;
    private static DungeonSession dungeon;
    private static DungeonSession.Frame dungeonFrame;
    private static View view = View.INN;
    private static String ignoredMatch;
    private static String innNotice = "";
    private static String defeatNotice;
    private static long noticeUntil;
    private static String nickname;
    private static InnApi.Appearance appearance;
    private static boolean profileConfirmed;
    private static boolean friendlyFire;
    private static boolean testMode;
    private static boolean leftInn;
    private static boolean preparing;
    private static volatile boolean shuttingDown;

    private NativeClient() {}

    static void start(InnApi connection) {
        api = connection;
        AvatarProfile.Saved saved = AvatarProfile.restore(SETTINGS.get("inn-avatar-profile", ""));
        profileConfirmed = saved != null;
        nickname = saved == null ? SETTINGS.get("nickname", "") : saved.name();
        if (!LobbySession.validName(nickname)) nickname = LobbySession.guestName();
        appearance = saved == null ? AvatarProfile.normalize(null) : saved.appearance();
        if (profileConfirmed) connectInn();
    }

    private static void connectInn() {
        inn = new LobbySession(api, nickname, appearance, snapshot -> {
            if (!shuttingDown) Gdx.app.postRunnable(() -> onInn(snapshot));
        });
    }

    private static void onInn(LobbySession.Snapshot snapshot) {
        innSnapshot = snapshot;
        if (view != View.INN || !snapshot.online() || snapshot.state().match() == null) return;
        String matchId = snapshot.state().match().id();
        if (!matchId.equals(ignoredMatch)) enterOriginalParty(matchId);
    }

    static LobbySession.Snapshot innSnapshot() { return innSnapshot; }
    static LobbySession inn() { return inn; }
    static String nickname() { return nickname; }
    static InnApi.Appearance appearance() { return appearance; }
    static boolean profileConfirmed() { return profileConfirmed; }
    static boolean isTestMode() { return testMode; }
    static void setTestMode(boolean enabled) { testMode = enabled; }

    static boolean friendlyFire() { return friendlyFire; }

    static void toggleFriendlyFire() {
        if (canChooseExpeditionTheme()) friendlyFire = !friendlyFire;
    }

    static String expeditionThemeTitle(String theme) {
        return DungeonLayout.PRISON.equals(theme) ? "버려진 감옥" : "하수도";
    }

    static boolean canChooseExpeditionTheme() {
        InnApi.State current = innSnapshot == null ? null : innSnapshot.state();
        return view == View.INN && !preparing && (current == null || !current.queued() && current.match() == null);
    }

    static void rename(String value) {
        saveProfile(value, appearance);
    }

    static void saveProfile(String name, InnApi.Appearance draft) {
        if (!canChooseExpeditionTheme()) return;
        String value = name == null ? "" : name.strip();
        if (!LobbySession.validName(value)) {
            notice("닉네임을 1~20자로 입력하세요.");
            return;
        }
        nickname = value;
        appearance = AvatarProfile.normalize(draft);
        SETTINGS.put("inn-avatar-profile", AvatarProfile.save(nickname, appearance));
        profileConfirmed = true;
        if (inn == null) connectInn();
        else inn.updateProfile(nickname, appearance);
    }

    static String innNotice() {
        return view == View.ENTERING || System.nanoTime() < noticeUntil ? innNotice : "";
    }

    static void enterPractice() {
        if (!canEnterSolo()) return;
        configureOriginal(nickname, appearance, true, null);
        InnAdventure.enablePractice();
        view = View.SOLO;
        leftInn = true;
        inn.enterDungeon(false, null);
        ShatteredPixelDungeon.switchNoFade(InnAdventureScene.class);
    }

    private static void enterOriginalParty(String matchId) {
        ignoredMatch = matchId;
        view = View.ENTERING;
        leftInn = false;
        innNotice = "동료와 원본 던전에 들어가는 중…";
        party = new DungeonSession(api, inn.sessionKey(), matchId, friendlyFire, new DungeonSession.OriginalListener() {
            @Override
            public void start(DungeonSession source, InnApi.LinkState link, boolean host, long seed,
                              boolean partyFriendlyFire) {
                if (!shuttingDown) Gdx.app.postRunnable(() -> startOriginalParty(source, link, host, seed, partyFriendlyFire));
            }

            @Override
            public void receive(DungeonSession source, String payload) {
                if (!shuttingDown) Gdx.app.postRunnable(() -> {
                    if (source == party && view == View.ORIGINAL_PARTY) InnOriginalParty.receive(payload);
                });
            }

            @Override
            public void end(DungeonSession source, String message) {
                if (!shuttingDown) Gdx.app.postRunnable(() -> originalPartyEnded(source, message));
            }
        });
    }

    private static void startOriginalParty(DungeonSession source, InnApi.LinkState link, boolean host, long seed,
                                           boolean partyFriendlyFire) {
        if (source != party || view != View.ENTERING) return;
        InnApi.Appearance own = link.selfAppearance() == null ? appearance : link.selfAppearance();
        InnApi.Appearance remote = AvatarProfile.normalize(link.partnerAppearance());
        InnApi.Preparation preparation = host ? preparation() : null;
        configureOriginal(link.selfName(), own, true, host ? link.selfPreparation() : null);
        InnOriginalParty.configure(host, link.partnerName(), remote.job(), remote.hairColor(), remote.outfitColor(),
            remote.hairStyle(), remote.accessory(), seed, partyFriendlyFire, source::sendOriginal);
        view = View.ORIGINAL_PARTY;
        leftInn = true;
        inn.enterDungeon(true, preparation);
        ShatteredPixelDungeon.switchNoFade(InnAdventureScene.class);
        source.originalConfigured();
    }

    private static void originalPartyEnded(DungeonSession source, String message) {
        if (source != party) return;
        if (view == View.ORIGINAL_PARTY) InnOriginalParty.close();
        else inn.matchmaking(false);
        returnToInn(message);
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

    static void dungeonShoot(int x, int y) {
        if (view == View.DUNGEON) dungeon.action(new InnApi.Action("shoot", 0, 0, x + "," + y));
    }

    static void dungeonEat() {
        if (view == View.DUNGEON) dungeon.action(new InnApi.Action("eat", 0, 0, null));
    }

    static void dungeonUseItem(String item) {
        if (view == View.DUNGEON) dungeon.action(new InnApi.Action("use-item", 0, 0, item));
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

    static boolean canEnterSolo() {
        return inn != null && profileConfirmed && canChooseExpeditionTheme();
    }

    static void enterSolo() {
        enterOriginal(false);
    }

    static void enterSoloAtb() {
        enterOriginal(true);
    }

    static InnApi.Preparation preparation() {
        return innSnapshot == null || innSnapshot.state() == null ? null : innSnapshot.state().preparation();
    }

    static void prepareExpedition() {
        if (!canEnterSolo() || innSnapshot == null || !innSnapshot.online() || preparation() != null) return;
        preparing = true;
        inn.prepare(() -> Gdx.app.postRunnable(() -> {
            preparing = false;
            InnApi.Preparation result = preparation();
            if (result != null) notice("다음 원정 · " + result.description());
        }));
    }

    private static void enterOriginal(boolean atb) {
        if (!canEnterSolo()) return;
        InnApi.Preparation preparation = atb ? preparation() : null;
        configureOriginal(nickname, appearance, atb, preparation);
        view = View.SOLO;
        leftInn = true;
        inn.enterDungeon(false, preparation);
        ShatteredPixelDungeon.switchNoFade(InnAdventureScene.class);
    }

    private static void configureOriginal(String name, InnApi.Appearance profile, boolean atb,
                                          InnApi.Preparation preparation) {
        InnAdventure.configure(name, profile.job(), profile.hairColor(), profile.outfitColor(),
            profile.hairStyle(), profile.accessory(), atb,
            InnApi.Preparation.health(preparation), InnApi.Preparation.rations(preparation));
    }

    static boolean inSolo() { return view == View.SOLO; }

    static boolean inOriginal() { return inSolo() || view == View.ORIGINAL_PARTY; }

    static void returnToInn() {
        returnToInn(null);
    }

    static void returnDefeated() {
        if (view == View.INN) return;
        defeatNotice = chooseDefeatLine();
        returnToInn(null);
    }

    static String takeDefeatNotice() {
        String notice = defeatNotice;
        defeatNotice = null;
        return notice;
    }

    private static String chooseDefeatLine() {
        try {
            String source = Gdx.files.internal("inn/defeat-lines.json").readString("UTF-8");
            DefeatLine[] lines = new Gson().fromJson(source, DefeatLine[].class);
            DefeatLine line = lines[ThreadLocalRandom.current().nextInt(lines.length)];
            return "“" + line.text() + "”\n— " + line.author();
        } catch (RuntimeException unavailable) {
            return "여관으로 돌아왔습니다.";
        }
    }

    static void returnToInn(String message) {
        if (view == View.INN) return;
        InnApi.DungeonState outcome = dungeonState();
        if (outcome != null && ("lost".equals(outcome.status()) || outcome.actors().stream()
                .anyMatch(actor -> outcome.selfId().equals(actor.id()) && actor.hp() <= 0))) {
            defeatNotice = chooseDefeatLine();
        }
        if (inOriginal()) {
            InnAdventure.retreat();
            InnAdventure.clear();
        }
        if (party != null) party.close();
        party = null;
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
        if (inOriginal()) InnAdventure.clear();
        if (party != null) party.close();
        if (dungeon != null) dungeon.close();
        if (inn != null) inn.close();
    }
}
