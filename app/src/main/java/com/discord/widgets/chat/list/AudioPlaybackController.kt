package com.discord.widgets.chat.list

import android.content.Context
import com.discord.player.AppMediaPlayer
import com.discord.player.MediaSource
import com.discord.player.MediaType
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers

internal class AudioPlaybackController(
    private val context: Context,
    private val featureTag: String,
    private val pollIntervalMs: Long,
    private val onPlaying: (Boolean) -> Unit,
    private val onPosition: (Long) -> Unit,
    private val onReady: (Long) -> Unit = {},
) {
    private var url: String? = null
    private var player: AppMediaPlayer? = null
    private var events: Subscription? = null
    private var playing = false
    private var speed = 1f

    fun configure(url: String) {
        if (this.url != url) release()
        this.url = url
    }

    fun toggle() {
        val current = player
        if (current == null) {
            val source = url ?: return
            AppMediaPlayer.create(context, positionPollIntervalMs = pollIntervalMs).also {
                player = it
                it.setPlaybackSpeed(speed)
                events = it.events.observeOn(AndroidSchedulers.mainThread()).subscribe(::handleEvent)
                it.playAudio(MediaSource.create(MediaType.VIDEO, source, featureTag))
            }
        } else if (playing) {
            current.pause()
        } else {
            current.resume()
        }
    }

    fun seekTo(positionMs: Long) = player?.seekTo(positionMs)

    fun setSpeed(speed: Float) {
        this.speed = speed
        player?.setPlaybackSpeed(speed)
    }

    fun release() {
        events?.unsubscribe()
        events = null
        player?.release()
        player = null
        playing = false
        onPlaying(false)
        onPosition(0L)
    }

    private fun handleEvent(event: AppMediaPlayer.Event) {
        when (event) {
            AppMediaPlayer.Event.Ready -> onReady(player?.durationMs() ?: 0L)
            AppMediaPlayer.Event.Playing -> setPlaying(true)
            AppMediaPlayer.Event.Paused -> setPlaying(false)
            AppMediaPlayer.Event.Ended -> release()
            is AppMediaPlayer.Event.CurrentPositionUpdate -> onPosition(event.positionMs)
            AppMediaPlayer.Event.Buffering -> Unit
        }
    }

    private fun setPlaying(value: Boolean) {
        playing = value
        onPlaying(value)
    }
}
