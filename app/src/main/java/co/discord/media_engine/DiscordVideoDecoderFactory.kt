package co.discord.media_engine

import org.webrtc.EglBase
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.PlatformSoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoDecoderFallback

@Suppress("unused")
class DiscordVideoDecoderFactory(context: EglBase.Context) : VideoDecoderFactory {
    private val hardware = HardwareVideoDecoderFactory(context)
    private val platformSoftware = PlatformSoftwareVideoDecoderFactory(context)
    private val software = SoftwareVideoDecoderFactory()

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? {
        val softwareDecoder = software.createDecoder(info) ?: platformSoftware.createDecoder(info)
        val hardwareDecoder = hardware.createDecoder(info)
        return when {
            hardwareDecoder != null && softwareDecoder != null ->
                VideoDecoderFallback(softwareDecoder, hardwareDecoder)
            hardwareDecoder != null -> hardwareDecoder
            else -> softwareDecoder
        }
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = buildSet {
        software.supportedCodecs.filterTo(this) { it.name == "VP8" }
        addAll(hardware.supportedCodecs)
        platformSoftware.supportedCodecs.filterTo(this) { it.name == "H264" }
    }.toTypedArray()
}
