package co.discord.media_engine

import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import java.nio.ByteBuffer
import org.webrtc.ThreadUtils
import org.webrtc.TimestampAligner

class SoundshareAudioSource {
    private var audioRecord: AudioRecord? = null
    private var audioThread: AudioRecordThread? = null
    private var byteBuffer: ByteBuffer? = null
    val nativeInstance: Long = nativeCreateInstance()
    private var released = false

    private fun channelCountToConfiguration(channels: Int) =
        if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

    @Synchronized
    private fun dataIsRecorded(bytes: Int, timestampNanos: Long) {
        if (!released) nativeDataIsRecorded(nativeInstance, bytes, timestampNanos)
    }

    @Synchronized private external fun nativeCacheDirectBufferAddress(nativeInstance: Long, byteBuffer: ByteBuffer)
    @Synchronized private external fun nativeCreateInstance(): Long
    private external fun nativeDataIsRecorded(nativeInstance: Long, bytes: Int, timestampNanos: Long)
    @Synchronized private external fun nativeDestroyInstance(nativeInstance: Long)
    private external fun nativeSetSampleFormat(nativeInstance: Long, sampleRate: Int, bitDepth: Int, channels: Int)

    fun setSampleFormat(sampleRate: Int, bitDepth: Int, channels: Int) {
        nativeSetSampleFormat(nativeInstance, sampleRate, bitDepth, channels)
    }

    fun startRecording(audioRecord: AudioRecord): Boolean {
        if (this.audioRecord != null) {
            Log.e(TAG, "Init recording error: StartRecording called twice without StopRecording.")
            return false
        }

        this.audioRecord = audioRecord
        val channels = audioRecord.channelCount
        val sampleRate = audioRecord.sampleRate
        setSampleFormat(sampleRate, BITS_PER_SAMPLE, channels)
        val buffer = ByteBuffer.allocateDirect(sampleRate / BUFFERS_PER_SECOND * channels * BUFFER_SIZE_FACTOR)
        byteBuffer = buffer
        nativeCacheDirectBufferAddress(nativeInstance, buffer)

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelCountToConfiguration(channels),
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Init recording error: AudioRecord.getMinBufferSize failed: $minBufferSize")
            return false
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Init recording error: Failed to create a new AudioRecord instance")
            release()
            return false
        }

        return try {
            check(audioThread == null) { "WebrtcAudioRecord.startRecording: audioThread != null!" }
            val timestamp = TimestampAligner.getRtcTimeNanos()
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "Start recording error: incorrect state ${audioRecord.recordingState}")
                false
            } else {
                audioThread = AudioRecordThread("SoundshareThread", audioRecord, buffer, timestamp).also { it.start() }
                true
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Start recording error: ${e.message}")
            false
        }
    }

    fun stopRecording(): Boolean {
        val thread = audioThread ?: return false
        thread.stopThread()
        if (!ThreadUtils.joinUninterruptibly(thread, AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS)) {
            Log.e(TAG, "Join of SoundshareThread timed out")
        }
        audioThread = null
        return true
    }

    @Synchronized
    fun release() {
        if (released) return
        audioRecord?.release()
        audioRecord = null
        nativeDestroyInstance(nativeInstance)
        released = true
    }

    inner class AudioRecordThread(
        name: String,
        private val audioRecord: AudioRecord,
        private val byteBuffer: ByteBuffer,
        private var timestamp: Long,
    ) : Thread(name) {
        @Volatile private var keepAlive = true
        private val emptyBytes = ByteArray(byteBuffer.capacity())

        override fun run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            while (keepAlive) {
                val bytes = audioRecord.read(byteBuffer, byteBuffer.capacity())
                timestamp = TimestampAligner.getRtcTimeNanos()
                if (bytes == byteBuffer.capacity()) {
                    if (microphoneMute) {
                        byteBuffer.clear()
                        byteBuffer.put(emptyBytes)
                    }
                    if (keepAlive) dataIsRecorded(bytes, timestamp)
                } else {
                    val message = "AudioRecord.read failed: $bytes"
                    Log.e(TAG, message)
                    if (bytes == AudioRecord.ERROR_INVALID_OPERATION) {
                        keepAlive = false
                        Log.e(TAG, "Run-time recording error: $message")
                    }
                }
            }
            try {
                audioRecord.stop()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioRecord.stop failed: ${e.message}")
            }
        }

        fun stopThread() {
            keepAlive = false
        }
    }

    companion object {
        private const val AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS = 2000L
        private const val BITS_PER_SAMPLE = 16
        private const val BUFFERS_PER_SECOND = 100
        private const val BUFFER_SIZE_FACTOR = 2
        private const val TAG = "SoundshareAudioSource"
        @Volatile private var microphoneMute = false

        @JvmStatic
        fun setMicrophoneMute(mute: Boolean) {
            Log.w(TAG, "setMicrophoneMute($mute)")
            microphoneMute = mute
        }
    }
}
