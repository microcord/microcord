package com.discord;

import android.view.Surface;
import java.util.function.Consumer;
import org.webrtc.AndroidVideoDecoder;

public interface DiscordDecoderStreamManagerDelegate {
    void addOnConsumerReadyListener(String streamId, Consumer<Surface> consumer);
    Surface getStreamConsumer(String streamId);
    void registerStreamProducer(String streamId, AndroidVideoDecoder decoder);
    void removeOnConsumerReadyListener(String streamId, Consumer<Surface> consumer);
}
