package com.discord;

import android.view.Surface;
import java.util.function.Consumer;
import org.webrtc.AndroidVideoDecoder;

@SuppressWarnings("unused")
public final class DiscordDecoderStreamManager {
    private static volatile DiscordDecoderStreamManagerDelegate delegate;
    private static volatile boolean surfaceDirectPathEnabled;

    private DiscordDecoderStreamManager() {}

    public static void addOnConsumerReadyListener(String streamId, Consumer<Surface> consumer) {
        DiscordDecoderStreamManagerDelegate current = delegate;
        if (current != null) current.addOnConsumerReadyListener(streamId, consumer);
    }

    public static Surface getStreamConsumer(String streamId) {
        DiscordDecoderStreamManagerDelegate current = delegate;
        return current == null ? null : current.getStreamConsumer(streamId);
    }

    public static boolean isSurfaceDirectPathEnabled() {
        return surfaceDirectPathEnabled;
    }

    public static void registerStreamProducer(String streamId, AndroidVideoDecoder decoder) {
        DiscordDecoderStreamManagerDelegate current = delegate;
        if (current != null) current.registerStreamProducer(streamId, decoder);
    }

    public static void removeOnConsumerReadyListener(String streamId, Consumer<Surface> consumer) {
        DiscordDecoderStreamManagerDelegate current = delegate;
        if (current != null) current.removeOnConsumerReadyListener(streamId, consumer);
    }

    public static DiscordDecoderStreamManagerDelegate setDelegate(DiscordDecoderStreamManagerDelegate value) {
        delegate = value;
        return value;
    }

    public static void setSurfaceDirectPathEnabled(boolean enabled) {
        surfaceDirectPathEnabled = enabled;
    }
}
