package com.discord.utilities.media

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.discord.app.AppLog
import com.discord.utilities.lifecycle.ApplicationProvider
import java.io.IOException

/**
 * Plays the app's short feedback sounds ([AppSound]).
 *
 * One [MediaPlayer] is created per sound and torn down as soon as it finishes, keyed by raw
 * resource id — so the same sound never overlaps itself, but two different sounds can play at
 * once. Playing a sound that is already playing restarts it.
 *
 * `MediaPlayer` throws `IllegalStateException` from `start()`, `stop()` and `isPlaying()` when it
 * is in an unexpected state, and there is no way to interrogate that state beforehand. The
 * reference wraps each of those calls and logs rather than crashing; feedback audio is never worth
 * taking the process down for, and that behaviour is reproduced here.
 *
 * Reference: `com.discord.utilities.media.AppSoundManager`.
 */
class AppSoundManager(application: Application) {

    private val context: Context = application
    private val soundPlayers: MutableMap<Int, SoundPlayer> = LinkedHashMap()

    fun isPlaying(sound: AppSound): Boolean = soundPlayers.containsKey(sound.resId)

    /** Start [sound], restarting it from the beginning if it is already playing. */
    fun play(sound: AppSound) {
        if (isPlaying(sound)) {
            soundPlayers[sound.resId]?.stop()
            soundPlayers[sound.resId]?.release()
        }
        soundPlayers[sound.resId] = SoundPlayer(context, sound) {
            // A looping sound only ends when the caller stops it, so it must not tear itself down.
            if (!sound.shouldLoop) stop(sound)
        }
        soundPlayers[sound.resId]?.start()
    }

    /** Stop [sound] and release its player. Safe to call when the sound is not playing. */
    fun stop(sound: AppSound) {
        if (soundPlayers[sound.resId]?.isPlaying() == true) {
            soundPlayers[sound.resId]?.stop()
        }
        soundPlayers[sound.resId]?.release()
        soundPlayers.remove(sound.resId)
    }

    /**
     * The process-wide instance.
     *
     * Built lazily from [ApplicationProvider] so that constructing it does not force
     * `MediaPlayer` work during app start.
     */
    object Provider {
        private val instance: AppSoundManager by lazy { AppSoundManager(ApplicationProvider.get()) }

        @JvmStatic
        fun get(): AppSoundManager = instance
    }

    private class SoundPlayer(
        context: Context,
        sound: AppSound,
        onCompletion: () -> Unit,
    ) {
        private var mediaPlayer: MediaPlayer? = MediaPlayer()

        init {
            mediaPlayer?.let { player ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(sound.contentType)
                        .setUsage(sound.usage)
                        .build(),
                )
                val fd = context.resources.openRawResourceFd(sound.resId)
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                player.isLooping = sound.shouldLoop
                player.setOnCompletionListener { onCompletion() }
                try {
                    player.prepare()
                } catch (_: IOException) {
                    // A raw resource that cannot be decoded on this device. Drop the player so
                    // every later call becomes a no-op instead of throwing.
                    mediaPlayer = null
                }
            }
        }

        fun isPlaying(): Boolean = runCatching { mediaPlayer?.isPlaying == true }
            .onFailure { AppLog.w("Error checking if MediaPlayer is playing", it) }
            .getOrDefault(false)

        fun start() {
            runCatching { mediaPlayer?.start() }
                .onFailure { AppLog.e("Error starting MediaPlayer in invalid state", it) }
        }

        fun stop() {
            runCatching { mediaPlayer?.stop() }
                .onFailure { AppLog.w("Called stop on uninitialized MediaPlayer", it) }
        }

        fun release() {
            mediaPlayer?.release()
        }
    }
}
