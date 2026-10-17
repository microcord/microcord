package com.discord.player

import android.content.Context
import androidx.annotation.MainThread
import com.discord.app.AppLog
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultAllocator
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription

/**
 * The app's single video player: an ExoPlayer instance plus the Rx event surface the UI consumes.
 *
 * Callers never touch ExoPlayer directly. They get [events], a stream of coarse playback facts, and
 * a [play] call that binds a [MediaSource] to a view. That indirection is what lets the position
 * poller exist at all: ExoPlayer has no "position changed" callback, so a 500 ms timer is started
 * on `READY` and stopped on `ENDED`, and nothing outside this class has to know that.
 *
 * One instance is expected per player surface and it owns its ExoPlayer, so [release] is mandatory.
 *
 * Reference: `com.discord.player.AppMediaPlayer`, constructed by `b.a.p.i`.
 */
class AppMediaPlayer(
    private val exoPlayer: ExoPlayer,
    private val rxPlayerEventListener: RxPlayerEventListener,
    private val dataSourceFactory: DataSource.Factory,
    private val directDataSourceFactory: DataSource.Factory = dataSourceFactory,
    private val timerScheduler: Scheduler,
    private val positionPollIntervalMs: Long = DEFAULT_POSITION_POLL_INTERVAL_MS,
) {

    /** Everything the UI needs to know about playback, without exposing ExoPlayer's state ints. */
    sealed class Event {
        /** Enough data is buffered to play. */
        object Ready : Event()

        /** Playback stalled waiting for data. */
        object Buffering : Event()

        /** Emitted every 500 ms while playing, and once more when playback ends. */
        data class CurrentPositionUpdate(val positionMs: Long) : Event()

        /** Playback stopped, either by the user or because it ended. */
        object Paused : Event()

        /** The media reached its end. */
        object Ended : Event()

        /** Playback started or resumed. */
        object Playing : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()
    private val compositeSubscription = CompositeSubscription()
    private val volumeSubject = BehaviorSubject.create(exoPlayer.volume)

    private var positionPollingSubscription: Subscription? = null
    private var mediaSource: MediaSource? = null

    /**
     * Buffered because a PublishSubject errors a subscriber that falls behind, and every listener
     * hops to the main thread through `observeOn`'s 16-slot queue: a main thread stalled for a few
     * seconds (a slow phone joining a call) with position ticks queued crashed the app.
     */
    val events: Observable<Event> = eventSubject.onBackpressureBuffer()
    val volume: Observable<Float> = volumeSubject

    init {
        compositeSubscription.add(
            rxPlayerEventListener.playerStateChanges
                .onBackpressureBuffer()
                .subscribe(
                    { change -> handlePlaybackState(change.playbackState) },
                    { error -> AppLog.e(TAG, "error while listening to player states", error) },
                ),
        )
        compositeSubscription.add(
            rxPlayerEventListener.isPlayingChanges
                .onBackpressureLatest()
                .subscribe(
                    { change -> eventSubject.onNext(if (change.isPlaying) Event.Playing else Event.Paused) },
                    { error -> AppLog.e(TAG, "error while listening to player states", error) },
                ),
        )
        compositeSubscription.add(
            rxPlayerEventListener.playerErrors
                .onBackpressureBuffer()
                .subscribe(
                    { error ->
                        // Attributed to the feature that asked for playback, so a failing surface can
                        // be identified without correlating timestamps.
                        AppLog.e(
                            TAG,
                            "playback error for feature: ${mediaSource?.featureTag.orEmpty()}",
                            error.exoPlaybackException,
                        )
                    },
                    { error -> AppLog.e(TAG, "error while listening to player states", error) },
                ),
        )
    }

    /**
     * Binds [mediaSource] to [playerView] and starts loading it.
     *
     * [loopMedia] maps onto `REPEAT_MODE_ONE`, which is how a GIFV plays forever without the caller
     * re-issuing playback at every end.
     */
    @MainThread
    fun play(
        mediaSource: MediaSource,
        playerView: PlayerView,
        autoPlayWhenReady: Boolean = true,
        loopMedia: Boolean = false,
        initialSeekPositionMs: Long = 0L,
        playerControlView: PlayerControlView? = null,
    ) {
        // Fit rather than crop: the viewer letterboxes, and cropping would silently hide content.
        exoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        playerView.setPlayer(exoPlayer)
        playerControlView?.setPlayer(exoPlayer)

        prepare(mediaSource, autoPlayWhenReady, loopMedia, initialSeekPositionMs)
    }

    /** Audio-only playback avoids constructing or attaching a video surface. */
    @MainThread
    fun playAudio(
        mediaSource: MediaSource,
        autoPlayWhenReady: Boolean = true,
        initialSeekPositionMs: Long = 0L,
    ) = prepare(mediaSource, autoPlayWhenReady, false, initialSeekPositionMs)

    private fun prepare(
        mediaSource: MediaSource,
        autoPlayWhenReady: Boolean,
        loopMedia: Boolean,
        initialSeekPositionMs: Long,
    ) {
        this.mediaSource = mediaSource

        exoPlayer.setMediaSource(
            ProgressiveMediaSource.Factory(dataSourceFor(mediaSource))
                .createMediaSource(MediaItem.fromUri(mediaSource.progressiveMediaUri)),
        )
        exoPlayer.prepare()
        if (autoPlayWhenReady) exoPlayer.playWhenReady = true
        if (initialSeekPositionMs > 0L) exoPlayer.seekTo(exoPlayer.currentWindowIndex, initialSeekPositionMs)
        exoPlayer.repeatMode = if (loopMedia) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /**
     * GIFV loops are small and re-watched, so they read through the media cache; full videos
     * stream direct. Writing a multi-hundred-MB file through the 100 MB LRU cache thrashes the
     * evictor mid-write (disk churn on the loader thread) and is why very large videos lagged.
     */
    internal fun dataSourceFor(source: MediaSource): DataSource.Factory =
        if (source.mediaType == MediaType.GIFV) dataSourceFactory else directDataSourceFactory

    @MainThread
    fun setVolume(volume: Float) {
        exoPlayer.volume = volume
        volumeSubject.onNext(volume)
    }

    @MainThread fun pause() = exoPlayer.pause()

    @MainThread fun resume() = exoPlayer.play()

    @MainThread fun seekTo(positionMs: Long) = exoPlayer.seekTo(positionMs)

    @MainThread fun setPlaybackSpeed(speed: Float) = exoPlayer.setPlaybackSpeed(speed)

    @MainThread fun durationMs(): Long = exoPlayer.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L

    /** Stops playback and releases the ExoPlayer. The instance is unusable afterwards. */
    @MainThread
    fun release() {
        exoPlayer.playWhenReady = false
        positionPollingSubscription?.unsubscribe()
        positionPollingSubscription = null
        compositeSubscription.unsubscribe()
        exoPlayer.release()
    }

    /**
     * Starts and stops the position poller alongside the coarse state events.
     *
     * The final [Event.CurrentPositionUpdate] on `ENDED` is what leaves a seek bar pinned at the end
     * instead of wherever the last 500 ms tick happened to land.
     */
    private fun handlePlaybackState(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> eventSubject.onNext(Event.Buffering)
            Player.STATE_READY -> {
                eventSubject.onNext(Event.Ready)
                positionPollingSubscription?.unsubscribe()
                positionPollingSubscription = Observable
                    .interval(positionPollIntervalMs, positionPollIntervalMs, TimeUnit.MILLISECONDS, timerScheduler)
                    .onBackpressureLatest()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { eventSubject.onNext(Event.CurrentPositionUpdate(exoPlayer.currentPosition)) },
                        { error -> AppLog.e(TAG, "error while polling progress position", error) },
                    )
            }
            Player.STATE_ENDED -> {
                eventSubject.onNext(Event.Ended)
                positionPollingSubscription?.unsubscribe()
                positionPollingSubscription = null
                eventSubject.onNext(Event.CurrentPositionUpdate(exoPlayer.currentPosition))
            }
        }
    }

    companion object {
        private const val TAG = "AppMediaPlayer"
        private const val DEFAULT_POSITION_POLL_INTERVAL_MS = 500L

        /**
         * Buffer tuning, taken from the reference.
         *
         * The playback thresholds (2.5 s to start, 5 s after a rebuffer) are well below ExoPlayer's
         * defaults so an inline GIF starts almost immediately; the 10 s / 30 s buffer window is what
         * keeps memory bounded when a dozen of them are on screen.
         */
        internal const val MIN_BUFFER_MS = 10_000
        internal const val MAX_BUFFER_MS = 30_000

        /** Inline-player caps: bounds high-bitrate buffering to a few MB instead of tens. */
        internal const val VIDEO_MIN_BUFFER_MS = 5_000
        internal const val VIDEO_MAX_BUFFER_MS = 12_000
        internal const val BUFFER_FOR_PLAYBACK_MS = 2_500
        internal const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
        internal const val ALLOCATION_SIZE_BYTES = 64 * 1024

    /**
     * Builds a player with the app's cache and buffer configuration.
     *
     * Must be called on the thread that will drive the player: ExoPlayer binds to the calling
     * looper.
     *
     * [lowMemoryBuffers] caps the buffer window for inline players. At the default 30 s ceiling a
     * high-bitrate video buffers tens of MB of 64 KB allocations on a low-RAM device, and the
     * growth phase (a few seconds in) is exactly when the app started thrashing into ANRs.
     */
    @MainThread
    @JvmStatic
    @JvmOverloads
    fun create(
        context: Context,
        lowMemoryBuffers: Boolean = false,
        positionPollIntervalMs: Long = DEFAULT_POSITION_POLL_INTERVAL_MS,
    ): AppMediaPlayer {
        val dataSourceFactory = CacheDataSourceFactory(
            context = context,
            upstreamFactory = DefaultDataSourceFactory(context),
        )
        val eventListener = RxPlayerEventListener()
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, ALLOCATION_SIZE_BYTES))
            .setBufferDurationsMs(
                if (lowMemoryBuffers) VIDEO_MIN_BUFFER_MS else MIN_BUFFER_MS,
                if (lowMemoryBuffers) VIDEO_MAX_BUFFER_MS else MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()
            val exoPlayer = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
                .apply { addListener(eventListener) }
            return AppMediaPlayer(
                exoPlayer,
                eventListener,
                dataSourceFactory,
                DefaultDataSourceFactory(context),
                Schedulers.computation(),
                positionPollIntervalMs,
            )
        }
    }
}
