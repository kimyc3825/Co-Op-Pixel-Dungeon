// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class LobbySession implements AutoCloseable {
    record Snapshot(InnApi.State state, List<InnApi.Event> history, boolean online,
                    String networkStatus, String matchStatus, String notice) {}

    @FunctionalInterface
    private interface Action {
        void run() throws IOException, InterruptedException;
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    static final long MOVE_INTERVAL = Duration.ofMillis(150).toNanos();
    private final InnApi api;
    private final Consumer<Snapshot> listener;
    private final ScheduledThreadPoolExecutor network = new ScheduledThreadPoolExecutor(1,
        task -> new Thread(task, "inn-network"));
    private final AtomicBoolean moving = new AtomicBoolean();
    private final Deque<InnApi.Event> history = new ArrayDeque<>();
    private volatile String key = newKey();
    private String pendingDeparture;
    private String name;
    private InnApi.Appearance appearance;
    private InnApi.State state;
    private long after;
    private long retryAt;
    private volatile long nextMoveAt;
    private long positionRevision;
    private long rosterGeneration;
    private boolean rosterInFlight;
    private boolean online;
    private boolean renamePending;
    private boolean suspended;
    private String connectionMessage = "여관에 접속하는 중…";
    private String notice = "";
    private long noticeUntil;
    LobbySession(InnApi api, String name, InnApi.Appearance appearance, Consumer<Snapshot> listener) {
        this.api = api;
        this.name = name;
        this.appearance = appearance;
        this.listener = listener;
        network.scheduleWithFixedDelay(this::periodicPoll, 0, 700, TimeUnit.MILLISECONDS);
    }

    static String guestName() {
        return "모험가-" + HexFormat.of().withUpperCase().formatHex(randomBytes(2));
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String newKey() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    static boolean validName(String name) {
        return !name.isEmpty() && name.codePointCount(0, name.length()) <= 20 &&
            !name.matches("(?s).*[\\p{C}\\p{Zl}\\p{Zp}].*");
    }

    private void periodicPoll() {
        if (suspended || System.nanoTime() < retryAt) return;
        if (!online || state == null || renamePending || pendingDeparture != null) {
            poll();
            return;
        }
        if (rosterInFlight) return;
        rosterInFlight = true;
        String requestKey = key;
        long generation = rosterGeneration;
        long positionAtRequest = positionRevision;
        api.syncAsync(requestKey, after).whenComplete((fresh, error) -> submit(() -> {
            rosterInFlight = false;
            if (suspended || !requestKey.equals(key) || generation != rosterGeneration) return;
            if (error != null) {
                pollFailed(error instanceof CompletionException ? error.getCause() : error);
            } else {
                InnApi.State merged = fresh;
                if (state != null && positionAtRequest != positionRevision && fresh.selfId().equals(state.selfId())) {
                    InnApi.Person self = state.self();
                    // This read was started before the most recent acknowledged move.
                    merged = fresh.withSelfPosition(self.x(), self.y());
                }
                accept(merged);
            }
            publish();
        }));
    }

    private void poll() {
        if (suspended || System.nanoTime() < retryAt) return;
        rosterGeneration++;
        try {
            if (pendingDeparture != null) {
                api.leave(pendingDeparture);
                pendingDeparture = null;
            }
            accept(state == null || renamePending ? api.enter(name, appearance, key, after) : api.sync(key, after));
            renamePending = false;
        } catch (IOException error) {
            pollFailed(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        publish();
    }

    private void pollFailed(Throwable error) {
        if (error instanceof InnApi.Problem problem) {
            if (problem.status == 404) {
                state = null;
                after = 0;
                history.clear();
                offline("여관에 다시 입장하는 중…");
            } else if (problem.code.equals("rate_limited")) {
                retryAt = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                offline("접속 요청이 많습니다. 잠시 뒤 다시 연결합니다.");
            } else if (problem.code.equals("inn_full")) {
                retryAt = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                offline("여관이 가득 찼습니다. 자리가 나면 입장합니다.");
            } else {
                offline("여관 서버에 다시 연결하는 중…");
            }
        } else {
            offline("여관 서버에 다시 연결하는 중…");
        }
    }

    private void accept(InnApi.State fresh) {
        String prior = state != null && state.match() != null ? state.match().id() : null;
        String current = fresh.match() == null ? null : fresh.match().id();
        if (!Objects.equals(prior, current)) {
            if (prior != null && current == null) notice("파티가 해제되었습니다.");
        }
        state = fresh;
        online = true;
        connectionMessage = "";
        for (InnApi.Event event : fresh.events()) {
            if (event.id() <= after) continue;
            history.addLast(event);
            if (history.size() > 60) history.removeFirst();
        }
        after = fresh.after();
    }

    void updateProfile(String value, InnApi.Appearance avatar) {
        submit(() -> {
            name = value;
            appearance = avatar;
            renamePending = true;
            poll();
        });
    }

    boolean canRequestMove() {
        return !moving.get() && System.nanoTime() - nextMoveAt >= 0;
    }

    boolean moveTo(int fromX, int fromY, int targetX, int targetY, Consumer<Boolean> result) {
        if (!canRequestMove() || !moving.compareAndSet(false, true)) return false;
        boolean scheduled = submit(() -> {
            boolean accepted = false;
            try {
                long started = System.nanoTime();
                if (!online || suspended || state == null) return;
                InnApi.Person current = state.self();
                if (current.x() != fromX || current.y() != fromY ||
                        !state.canStep(fromX, fromY, targetX, targetY)) return;
                nextMoveAt = started + MOVE_INTERVAL;
                accepted = perform(() -> api.move(key, targetX, targetY));
                if (accepted) {
                    positionRevision++;
                    state = state.withSelfPosition(targetX, targetY);
                    publish();
                }
            } finally {
                moving.set(false);
                result.accept(accepted);
            }
        });
        if (!scheduled) moving.set(false);
        return scheduled;
    }

    void chat(String message, Consumer<Boolean> result) {
        submit(() -> {
            boolean sent = perform(() -> api.chat(key, message));
            result.accept(sent);
            if (sent) poll();
        });
    }

    void drink() {
        submit(() -> {
            if (perform(() -> api.drink(key))) poll();
        });
    }

    void matchmaking(boolean queued) {
        submit(() -> {
            if (perform(() -> api.matchmaking(key, queued))) poll();
        });
    }

    void joinSeeking(String targetId) {
        submit(() -> {
            if (perform(() -> api.joinSeeking(key, targetId))) poll();
        });
    }

    String sessionKey() {
        return key;
    }

    private boolean perform(Action action) {
        if (suspended || !online) return false;
        try {
            action.run();
            return true;
        } catch (InnApi.Problem error) {
            switch (error.code) {
                case "move_rejected", "session_ended" -> { }
                case "partner_unavailable" -> notice("이 모험가는 지금 참가할 수 없습니다.");
                case "activity_rate_limited" -> notice("잠시 뒤 다시 시도해 주세요.");
                case "invalid_message" -> notice("대화는 한 줄, 160자까지 보낼 수 있습니다.");
                default -> notice("여관 요청을 처리하지 못했습니다.");
            }
            publish();
        } catch (IOException error) {
            offline("여관 서버에 다시 연결하는 중…");
            publish();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    void enterDungeon() {
        submit(() -> {
            suspended = true;
            leave();
            key = newKey();
            publish();
        });
    }

    void returnFromDungeon(boolean normalExit) {
        submit(() -> {
            suspended = false;
            retryAt = 0;
            if (!normalExit) notice("던전을 정상적으로 열거나 종료하지 못했습니다.");
            poll();
        });
    }

    private void leave() {
        if (state != null) {
            try {
                api.leave(key);
            } catch (IOException ignored) {
                pendingDeparture = key;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        state = null;
        offline("여관에 접속하는 중…");
    }

    private void offline(String message) {
        online = false;
        connectionMessage = message;
    }

    private void notice(String message) {
        notice = message;
        noticeUntil = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    }

    private String matchStatus() {
        if (state == null) return "";
        if (state.queued()) return "동료를 찾는 중…";
        if (state.match() == null) return "";
        String partner = state.match().partnerName();
        return partner + " 님과 매칭됨";
    }

    private void publish() {
        listener.accept(new Snapshot(state, List.copyOf(history), online, connectionMessage, matchStatus(),
            System.nanoTime() < noticeUntil ? notice : ""));
    }

    private boolean submit(Runnable task) {
        try {
            network.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            // A network reply can arrive while the window closes.
            return false;
        }
    }

    @Override
    public void close() {
        submit(this::leave);
        network.shutdown();
    }
}
