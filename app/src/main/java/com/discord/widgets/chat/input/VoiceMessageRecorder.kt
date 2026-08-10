package com.discord.widgets.chat.input

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import okio.ByteString.Companion.toByteString

internal class VoiceMessageRecorder(private val context: Context) {
    data class Recording(
        val file: File,
        val durationSecs: Double,
        val waveform: String,
        val mimeType: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val samples = mutableListOf<Int>()
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L
    private var maxDurationCallback: (() -> Unit)? = null
    private var amplitudeCallback: ((Int) -> Unit)? = null

    val elapsedMs: Long get() = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)

    fun start(onMaxDuration: () -> Unit, onAmplitude: (Int) -> Unit = {}) {
        check(recorder == null) { "Already recording" }
        val opus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val file = File.createTempFile("voice-message-", if (opus) ".ogg" else ".m4a", context.cacheDir)
        val mediaRecorder = MediaRecorder()
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(if (opus) MediaRecorder.OutputFormat.OGG else MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(if (opus) MediaRecorder.AudioEncoder.OPUS else MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(48_000)
            mediaRecorder.setAudioEncodingBitRate(64_000)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.setMaxDuration(VOICE_RECORDING_MAX_DURATION_MILLIS)
            mediaRecorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) maxDurationCallback?.invoke()
            }
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (error: Throwable) {
            mediaRecorder.release()
            file.delete()
            throw error
        }
        samples.clear()
        output = file
        recorder = mediaRecorder
        startedAt = SystemClock.elapsedRealtime()
        maxDurationCallback = onMaxDuration
        amplitudeCallback = onAmplitude
        handler.post(sampleAmplitude)
    }

    fun stop(): Recording? {
        val mediaRecorder = recorder ?: return null
        handler.removeCallbacks(sampleAmplitude)
        recorder = null
        maxDurationCallback = null
        amplitudeCallback = null
        runCatching { mediaRecorder.stop() }
        mediaRecorder.release()
        val file = output.also { output = null } ?: return null
        val duration = elapsedMs / 1000.0
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return null
        }
        return Recording(
            file,
            duration,
            encodeVoiceWaveform(samples),
            if (file.extension == "ogg") "audio/ogg" else "audio/mp4",
        )
    }

    fun cancel() {
        stop()?.file?.delete()
    }

    private val sampleAmplitude = object : Runnable {
        override fun run() {
            recorder?.let { mediaRecorder ->
                val amplitude = runCatching { mediaRecorder.maxAmplitude }.getOrDefault(0)
                val sample = voiceAmplitudeSample(amplitude)
                samples += sample
                amplitudeCallback?.invoke(sample)
                handler.postDelayed(this, WAVEFORM_SAMPLE_INTERVAL_MS)
            }
        }
    }

    companion object {
        const val VOICE_RECORDING_MIN_DURATION_MILLIS = 500L
        const val VOICE_RECORDING_MAX_DURATION_MILLIS = 1_199_500
        const val WAVEFORM_MAX_SAMPLES = 256
        internal const val WAVEFORM_GAIN = 1f
        private const val WAVEFORM_SAMPLE_INTERVAL_MS = 100L
    }
}

internal fun voiceAmplitudeSample(amplitude: Int, gain: Float = VoiceMessageRecorder.WAVEFORM_GAIN): Int =
    (amplitude.coerceIn(0, 32767) / 32767f * 255 * gain).toInt().coerceIn(0, 255)

internal fun encodeVoiceWaveform(samples: List<Int>): String {
    if (samples.isEmpty()) return byteArrayOf(0).toByteString().base64()
    val values = if (samples.size <= VoiceMessageRecorder.WAVEFORM_MAX_SAMPLES) samples else {
        List(VoiceMessageRecorder.WAVEFORM_MAX_SAMPLES) { index ->
            val start = index * samples.size / VoiceMessageRecorder.WAVEFORM_MAX_SAMPLES
            val end = (index + 1) * samples.size / VoiceMessageRecorder.WAVEFORM_MAX_SAMPLES
            samples.subList(start, maxOf(start + 1, end)).average().toInt()
        }
    }
    return values.map { it.coerceIn(0, 255).toByte() }.toByteArray().toByteString().base64()
}
