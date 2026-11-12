// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Call from one serial executor, which also delivers all native callbacks. */
final class PeerTransport implements AutoCloseable {
    private static final long GATHER_LIMIT = Duration.ofSeconds(8).toNanos();
    private static final int MAX_MESSAGE = 32 * 1024;
    private static final int MAX_BUFFERED = 256 * 1024;

    private final boolean host;
    private final Executor executor;
    private final Consumer<String> listener;
    private HeadlessAudioDeviceModule audio;
    private PeerConnectionFactory factory;
    private RTCPeerConnection peer;
    private RTCDataChannel channel;
    private long localSetAt;
    private InnApi.Signal localSignal;
    private boolean remoteApplied;
    private boolean localPublished;
    private boolean failed;
    private boolean closed;

    PeerTransport(boolean host, String stun, Executor callbacks, Consumer<String> listener) {
        this.host = host;
        this.executor = callbacks;
        this.listener = listener;
        try {
            audio = new HeadlessAudioDeviceModule();
            factory = new PeerConnectionFactory(audio);
            RTCConfiguration configuration = new RTCConfiguration();
            if (!stun.isBlank()) {
                RTCIceServer server = new RTCIceServer();
                server.urls = List.of(stun);
                configuration.iceServers.add(server);
            }
            peer = factory.createPeerConnection(configuration, new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    // Signaling exchanges one SDP per peer, including gathered ICE candidates.
                }
            });
            RTCDataChannelInit options = new RTCDataChannelInit();
            options.negotiated = true;
            options.ordered = true;
            options.id = 0;
            channel = peer.createDataChannel("noxac-atb", options);
            channel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long sentDataSize) {}

                @Override
                public void onStateChange() {}

                @Override
                public void onMessage(RTCDataChannelBuffer message) {
                    receive(message);
                }
            });
            if (host) peer.createOffer(new RTCOfferOptions(), descriptionObserver());
        } catch (RuntimeException | LinkageError error) {
            try {
                close();
            } catch (RuntimeException | LinkageError cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    void applyRemote(InnApi.Signal signal) {
        if (signal == null || remoteApplied || closed || failed) return;
        if (!(host ? "answer" : "offer").equals(signal.type()) ||
                signal.sdp() == null || signal.sdp().isBlank()) {
            failed = true;
            return;
        }
        remoteApplied = true;
        RTCSdpType type = host ? RTCSdpType.ANSWER : RTCSdpType.OFFER;
        try {
            peer.setRemoteDescription(new RTCSessionDescription(type, signal.sdp()),
                setObserver(() -> {
                    if (!host) peer.createAnswer(new RTCAnswerOptions(), descriptionObserver());
                }));
        } catch (RuntimeException error) {
            failed = true;
        }
    }

    InnApi.Signal pendingLocal() {
        if (closed || failed || localPublished || localSetAt == 0) return null;
        if (localSignal != null) return localSignal;
        if (peer.getIceGatheringState() != RTCIceGatheringState.COMPLETE &&
                System.nanoTime() - localSetAt < GATHER_LIMIT) return null;
        RTCSessionDescription description = peer.getLocalDescription();
        if (description != null) localSignal = new InnApi.Signal(host ? "offer" : "answer", description.sdp);
        return localSignal;
    }

    void markPublished() {
        localPublished = true;
    }

    boolean connected() {
        return !closed && !failed && channel.getState() == RTCDataChannelState.OPEN &&
            peer.getConnectionState() == RTCPeerConnectionState.CONNECTED;
    }

    boolean send(String message) {
        if (message.isEmpty() || message.length() > MAX_MESSAGE || !connected()) return false;
        ByteBuffer data = StandardCharsets.UTF_8.encode(message);
        if (data.remaining() > MAX_MESSAGE || channel.getBufferedAmount() + data.remaining() > MAX_BUFFERED) {
            return false;
        }
        try {
            channel.send(new RTCDataChannelBuffer(data, false));
            return true;
        } catch (Exception error) {
            failed = true;
            return false;
        }
    }

    boolean failed() {
        return closed || failed || peer.getConnectionState() == RTCPeerConnectionState.FAILED ||
            peer.getConnectionState() == RTCPeerConnectionState.CLOSED ||
            channel.getState() == RTCDataChannelState.CLOSED;
    }

    private void receive(RTCDataChannelBuffer message) {
        if (message.binary || !message.data.hasRemaining() || message.data.remaining() > MAX_MESSAGE) return;
        try {
            // The native message buffer is valid only while this callback is running.
            String value = StandardCharsets.UTF_8.newDecoder().decode(message.data.duplicate()).toString();
            dispatch(() -> listener.accept(value));
        } catch (CharacterCodingException ignored) {
            // Ignore invalid UTF-8 without delivering it to the game.
        }
    }

    private CreateSessionDescriptionObserver descriptionObserver() {
        return new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                dispatch(() -> peer.setLocalDescription(description, setObserver(() -> localSetAt = System.nanoTime())));
            }

            @Override
            public void onFailure(String error) {
                dispatch(() -> failed = true);
            }
        };
    }

    private SetSessionDescriptionObserver setObserver(Runnable success) {
        return new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                dispatch(success);
            }

            @Override
            public void onFailure(String error) {
                dispatch(() -> failed = true);
            }
        };
    }

    private void dispatch(Runnable task) {
        try {
            executor.execute(() -> {
                if (!closed) {
                    try {
                        task.run();
                    } catch (RuntimeException error) {
                        failed = true;
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A native callback may finish while the owner shuts down its executor.
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (channel != null) {
                try {
                    channel.unregisterObserver();
                } finally {
                    try {
                        channel.close();
                    } finally {
                        channel.dispose();
                    }
                }
            }
        } finally {
            try {
                if (peer != null) peer.close();
            } finally {
                try {
                    if (factory != null) factory.dispose();
                } finally {
                    if (audio != null) audio.dispose();
                }
            }
        }
    }
}
