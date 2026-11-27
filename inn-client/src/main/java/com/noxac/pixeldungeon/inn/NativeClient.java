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
    private enum View { INN, ENTERING, ORIGINAL_PARTY, SOLO }
    private record DefeatLine(String text, String author) {}

    private static final Preferences SETTINGS = Preferences.userNodeForPackage(NativeClient.class);
    private static InnApi api;
    private static LobbySession inn;
    private static volatile LobbySession.Snapshot innSnapshot;
    private static DungeonSession party;
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
        nickname = LobbySession.cleanName(nickname);
        if (nickname == null) nickname = LobbySession.guestName();
        appearance = saved == null ? AvatarProfile.normalize(null) : saved.appearance();
        if (profileConfirmed) connectInn();
    }

    private static void connectInn() {
        inn = new LobbySession(api, nickname, appearance, friendlyFire, snapshot -> {
            if (!shuttingDown) Gdx.app.postRunnable(() -> onInn(snapshot));
        });
    }

    private static void onInn(LobbySession.Snapshot snapshot) {
        LobbySession.Snapshot previous = innSnapshot;
        if (view == View.ENTERING && snapshot.state() == null && previous != null && previous.state() != null) {
            // Left the room view for the party (`away` on paired) but the dungeon has not started:
            // keep showing the room as it was, read-only, instead of an empty hall.
            snapshot = new LobbySession.Snapshot(previous.state(), snapshot.history(), false,
                snapshot.networkStatus(), "", snapshot.notice(), snapshot.stopped());
        }
        innSnapshot = snapshot;
        if (view != View.INN || !snapshot.online() || snapshot.state().match() == null) return;
        InnApi.Match match = snapshot.state().match();
        if (!match.id().equals(ignoredMatch)) enterOriginalParty(match);
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
        if (!canChooseExpeditionTheme()) return;
        friendlyFire = !friendlyFire;
        if (inn != null) inn.setFriendlyFire(friendlyFire);
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
        String value = LobbySession.cleanName(name);
        if (value == null) {
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

    /** Between a match and the start of the party dungeon. */
    static boolean preparingParty() { return view == View.ENTERING; }

    static String innNotice() {
        return view == View.ENTERING || System.nanoTime() < noticeUntil ? innNotice : "";
    }

    static void enterPractice() {
        if (!canEnterSolo()) return;
        configureOriginal(nickname, appearance, true, null);
        InnAdventure.enablePractice();
        view = View.SOLO;
        leftInn = true;
        inn.enterDungeon(null);
        ShatteredPixelDungeon.switchNoFade(InnAdventureScene.class);
    }

    private static void enterOriginalParty(InnApi.Match match) {
        ignoredMatch = match.id();
        view = View.ENTERING;
        // Returning always sends `enter`, which also clears a match that never reached the dungeon.
        leftInn = true;
        innNotice = "동료와 파티 연결을 여는 중…";
        party = new DungeonSession(api, inn.sessionKey(), match.id(), match.role(), new DungeonSession.OriginalListener() {
            @Override
            public void paired(DungeonSession source) {
                if (!shuttingDown) Gdx.app.postRunnable(() -> {
                    if (source != party || view != View.ENTERING) return;
                    // The guest starts at once; the host waits until the guest's dungeon is ready,
                    // which can take a while when the guest loads the web runtime first.
                    innNotice = "host".equals(match.role())
                        ? "동료가 던전을 불러오는 중입니다. 준비되면 함께 출발합니다…"
                        : "파티 던전을 불러오는 중…";
                    inn.leaveRoom();
                });
            }

            @Override
            public void start(DungeonSession source, InnApi.Link link, boolean host, long seed,
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

    private static void startOriginalParty(DungeonSession source, InnApi.Link link, boolean host, long seed,
                                           boolean partyFriendlyFire) {
        if (source != party || view != View.ENTERING) return;
        InnApi.Appearance own = link.selfAppearance() == null ? appearance : link.selfAppearance();
        InnApi.Appearance remote = AvatarProfile.normalize(link.partnerAppearance());
        // The match consumed both preparations on the server; the link hands this side its own.
        configureOriginal(link.selfName(), own, true, host ? link.selfPreparation() : null);
        InnOriginalParty.configure(host, link.partnerName(), remote.job(), remote.hairColor(), remote.outfitColor(),
            remote.hairStyle(), remote.accessory(), seed, partyFriendlyFire, source::sendOriginal);
        view = View.ORIGINAL_PARTY;
        inn.leaveRoom();
        ShatteredPixelDungeon.switchNoFade(InnAdventureScene.class);
        source.originalConfigured();
    }

    private static void originalPartyEnded(DungeonSession source, String message) {
        if (source != party) return;
        if (view == View.ORIGINAL_PARTY) InnOriginalParty.close();
        returnToInn(message);
    }

    static boolean canEnterSolo() {
        return inn != null && profileConfirmed && canChooseExpeditionTheme();
    }

    /** The original single-player game with its own save slots. */
    static void enterSolo() {
        enterOriginal(false);
    }

    /** A one-off inn expedition: temporary save slot, the preparation applies, retreat ends the run. */
    static void enterSoloExpedition() {
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

    private static void enterOriginal(boolean expedition) {
        if (!canEnterSolo()) return;
        InnApi.Preparation preparation = expedition ? preparation() : null;
        configureOriginal(nickname, appearance, expedition, preparation);
        view = View.SOLO;
        leftInn = true;
        inn.enterDungeon(preparation);
        ShatteredPixelDungeon.switchNoFade(InnAdventureScene.class);
    }

    private static void configureOriginal(String name, InnApi.Appearance profile, boolean expedition,
                                          InnApi.Preparation preparation) {
        InnAdventure.configure(name, profile.job(), profile.hairColor(), profile.outfitColor(),
            profile.hairStyle(), profile.accessory(), expedition,
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
        if (inOriginal()) {
            InnAdventure.retreat();
            InnAdventure.clear();
        }
        if (party != null) party.close();
        party = null;
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
        if (inn != null) inn.close();
        // Give the partner a `leave` and the inn a close frame rather than a dropped connection.
        try {
            if (party != null) party.awaitClosed(500, TimeUnit.MILLISECONDS);
            if (inn != null) inn.awaitClosed(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
