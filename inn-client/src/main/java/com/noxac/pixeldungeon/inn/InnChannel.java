// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One WebSocket of the protocol in server/PROTOCOL.md: JSON text frames, the literal `ping`
 * keep-alive, and a single close notification. All handler calls run on the owner's executor,
 * in the order the frames arrived.
 */
final class InnChannel {
    interface Handler {
        void opened(InnChannel channel);
        void frame(InnChannel channel, JsonObject frame);
        /** Called once, whether the socket closed, failed or never opened. */
        void closed(InnChannel channel, int code, String reason);
    }

    /** No close frame was received: the network failed or the handshake did not complete. */
    static final int ABNORMAL = 1006;
    /** PROTOCOL.md §2: every 25 s (a system property shortens it for network tests). */
    private static final long PING_SECONDS = Math.max(1, Long.getLong("noxac.inn.pingSeconds", 25));
    /** A `ping` that is not followed by any frame within this time means the connection is dead. */
    private static final long PONG_TIMEOUT_SECONDS = 10;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final long CLOSE_TIMEOUT_SECONDS = 5;
    /** Longest server frame accepted: above the 148,480-byte link frame limit (PROTOCOL.md §2). */
    private static final int MAX_FRAME_CHARS = 160 * 1024;

    private final ScheduledExecutorService executor;
    private final Handler handler;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket socket;
    private volatile boolean opened;
    private volatile boolean outputClosed;
    /** Set by {@link #close}: the owner wants no further calls. */
    private volatile boolean ownerClosed;
    /** System.nanoTime() of the last fragment received; any frame, `pong` included, is a sign of life. */
    private volatile long lastHeard = System.nanoTime();
    private ScheduledFuture<?> pinger;
    private ScheduledFuture<?> pongCheck;
    private CompletableFuture<?> sends = CompletableFuture.completedFuture(null);

    private InnChannel(ScheduledExecutorService executor, Handler handler) {
        this.executor = executor;
        this.handler = handler;
    }

    static InnChannel open(HttpClient http, URI uri, ScheduledExecutorService executor, Handler handler) {
        InnChannel channel = new InnChannel(executor, handler);
        // java.net.http sends no Origin header unless asked to, which is what the server expects of
        // non-browser clients (PROTOCOL.md §10).
        http.newWebSocketBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .buildAsync(uri, channel.new Listener())
            .whenComplete((socket, error) -> {
                if (error != null) channel.finish(ABNORMAL, describe(error));
            });
        return channel;
    }

    /** True once the handshake completed; a channel that closes without opening never reached the server. */
    boolean wasOpened() { return opened; }

    boolean isOpen() {
        WebSocket current = socket;
        return current != null && !outputClosed && !finished.get() && !current.isOutputClosed();
    }

    boolean send(JsonObject frame) {
        return sendText(frame.toString());
    }

    /**
     * Queues a text frame behind the frames already queued; java.net.http allows one outstanding
     * send at a time, and the protocol relies on the order being kept.
     */
    synchronized boolean sendText(String text) {
        WebSocket current = socket;
        if (current == null || outputClosed || finished.get()) return false;
        sends = sends.handle((ignored, error) -> null)
            .thenCompose(ignored -> current.sendText(text, true));
        return true;
    }

    /**
     * Sends the close frame after every queued frame. The handler is not called again, not even
     * {@link Handler#closed}: the owner already knows why it closed the channel.
     */
    CompletableFuture<?> close(int code, String reason) {
        ownerClosed = true;
        finished.set(true);
        WebSocket current;
        CompletableFuture<?> closed;
        synchronized (this) {
            cancelPinger();
            if (outputClosed) return sends.handle((ignored, error) -> null);
            outputClosed = true;
            current = socket;
            // onOpen closes a socket that completes its handshake later.
            if (current == null) return CompletableFuture.completedFuture(null);
            sends = sends.handle((ignored, error) -> null)
                .thenCompose(ignored -> current.sendClose(code, reason))
                .whenComplete((ignored, error) -> {
                    if (error != null) current.abort();
                });
            closed = sends.handle((ignored, error) -> null);
        }
        abortLater(current);
        return closed;
    }

    /** Lets an owner wait, briefly, for its last close frame before the process exits. */
    static final class Closing {
        private volatile CompletableFuture<?> pending = CompletableFuture.completedFuture(null);

        void track(CompletableFuture<?> closing) {
            pending = closing;
        }

        boolean awaitDone(long deadlineNanos) {
            try {
                pending.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
                return true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
                return false;
            }
        }
    }

    private static void abortLater(WebSocket current) {
        // A peer that never answers the close frame must not keep the connection open.
        CompletableFuture.delayedExecutor(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (!current.isInputClosed()) current.abort();
        });
    }

    private void finish(int code, String reason) {
        if (!finished.compareAndSet(false, true)) return;
        // After a close frame java.net.http answers it itself; after an error the socket is already gone.
        synchronized (this) {
            cancelPinger();
            outputClosed = true;
        }
        // Queued behind the frames that arrived before the close, which are still delivered.
        deliver(() -> {
            if (!ownerClosed) handler.closed(this, code, reason == null ? "" : reason);
        });
    }

    /** Called with the lock held, so it cannot interleave with the pinger being scheduled in onOpen. */
    private void cancelPinger() {
        ScheduledFuture<?> current = pinger;
        if (current != null) current.cancel(false);
        ScheduledFuture<?> check = pongCheck;
        if (check != null) check.cancel(false);
    }

    /**
     * Sends the keep-alive `ping` (PROTOCOL.md §2) and checks that something comes back: the server
     * answers `pong` at once, so silence means a half-open connection that TCP has not noticed yet.
     */
    private void keepAlive() {
        long sentAt = System.nanoTime();
        if (!sendText("ping")) return;
        try {
            ScheduledFuture<?> check = executor.schedule(() -> {
                WebSocket current = socket;
                if (finished.get() || lastHeard - sentAt >= 0 || current == null) return;
                finish(ABNORMAL, "pong_timeout");
                current.abort();
            }, PONG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            synchronized (this) {
                pongCheck = check;
                if (outputClosed) check.cancel(false); // closed meanwhile: nothing left to check
            }
        } catch (RejectedExecutionException ignored) {
            // The owner shut down.
        }
    }

    private void deliver(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // The owner shut down while a frame was arriving.
        }
    }

    private static String describe(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof WebSocketHandshakeException handshake) {
            return "http_" + handshake.getResponse().statusCode();
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            synchronized (InnChannel.this) {
                socket = webSocket;
                opened = true;
                lastHeard = System.nanoTime();
                if (outputClosed) {
                    // Closed by the owner while the handshake was still running.
                    webSocket.sendClose(1000, "").whenComplete((ignored, error) -> webSocket.abort());
                    abortLater(webSocket);
                    return;
                }
                // Scheduled under the lock: close() and finish() cancel it under the same lock.
                try {
                    pinger = executor.scheduleAtFixedRate(InnChannel.this::keepAlive, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
                } catch (RejectedExecutionException ignored) {
                    // The owner shut down during the handshake.
                }
            }
            webSocket.request(1);
            deliver(() -> {
                if (!ownerClosed) handler.opened(InnChannel.this);
            });
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            lastHeard = System.nanoTime();
            // A message can arrive split into several fragments; parse it only once it is complete.
            if (partial.length() + data.length() > MAX_FRAME_CHARS) {
                partial.setLength(0);
                finish(1009, "too_big");
                webSocket.abort();
                return null;
            }
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                if (!text.equals("pong")) {
                    JsonObject frame = parse(text);
                    if (frame != null) deliver(() -> {
                        if (!ownerClosed) handler.frame(InnChannel.this, frame);
                    });
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            // java.net.http answers protocol pings itself.
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            finish(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            finish(ABNORMAL, describe(error));
        }
    }

    private static JsonObject parse(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) return null;
            JsonObject frame = element.getAsJsonObject();
            JsonElement type = frame.get("t");
            return type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString() ? frame : null;
        } catch (JsonParseException | IllegalStateException invalid) {
            // Unknown or malformed server frames are ignored (forward compatibility).
            return null;
        }
    }
}
