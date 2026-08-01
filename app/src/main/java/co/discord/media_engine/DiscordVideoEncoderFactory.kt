package co.discord.media_engine

import android.media.MediaCodecInfo
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.PredicateSAM
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory

@Suppress("unused")
class DiscordVideoEncoderFactory(context: EglBase.Context) : VideoEncoderFactory {
    private val delegate = HardwareVideoEncoderFactory(
        context,
        false,
        true,
        PredicateSAM<MediaCodecInfo> { codec -> codec.name.lowercase() !in KNOWN_BAD_ENCODERS },
    )

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = delegate.createEncoder(info)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = delegate.supportedCodecs

    override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector? = null

    override fun getImplementations(): Array<out VideoCodecInfo> = supportedCodecs

    private companion object {
        private val KNOWN_BAD_ENCODERS = setOf("c2.mtk.hevc.encoder", "omx.mtk.video.encoder.hevc")
    }
}
