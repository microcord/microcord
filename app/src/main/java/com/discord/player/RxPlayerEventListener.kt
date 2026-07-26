package com.discord.player

import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Bridges ExoPlayer's callback interface onto Rx streams.
 *
 * ExoPlayer delivers on the application looper it was built with; keeping the raw callbacks behind
 * subjects is what lets [AppMediaPlayer] compose them (throttle, switch schedulers) instead of
 * hand-rolling state in the callbacks.
 *
 * Reference: `b.a.p.k` (`RxPlayerEventListener`).
 */
class RxPlayerEventListener : Player.Listener {

    data class PlayerStateChange(val playbackState: Int)

    data class IsPlayingChange(val isPlaying: Boolean)

    data class PlayerError(val exoPlaybackException: PlaybackException)

    private val playerStateChangeSubject = PublishSubject.create<PlayerStateChange>()
    private val isPlayingChangeSubject = PublishSubject.create<IsPlayingChange>()
    private val playerErrorSubject = PublishSubject.create<PlayerError>()

    val playerStateChanges: Observable<PlayerStateChange> = playerStateChangeSubject
    val isPlayingChanges: Observable<IsPlayingChange> = isPlayingChangeSubject
    val playerErrors: Observable<PlayerError> = playerErrorSubject

    override fun onPlaybackStateChanged(playbackState: Int) {
        playerStateChangeSubject.onNext(PlayerStateChange(playbackState))
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        isPlayingChangeSubject.onNext(IsPlayingChange(isPlaying))
    }

    override fun onPlayerError(error: PlaybackException) {
        playerErrorSubject.onNext(PlayerError(error))
    }
}
