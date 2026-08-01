package com.discord.stores

import android.content.Context
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityAssets
import com.discord.api.activity.ActivityParty
import com.discord.api.activity.ActivityTimestamps
import com.discord.api.activity.ActivityType
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.spotify.ModelSpotifyTrack
import com.discord.utilities.integrations.SpotifyHelper
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.spotify.SpotifyApiClient
import com.discord.utilities.time.Clock
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription
import rx.subjects.BehaviorSubject

/**
 * Publishes "Listening to Spotify" rich presence from what the local Spotify app is playing.
 *
 * The device only tells us *that* a track id started playing (through Spotify's broadcast
 * intents); the track's name, artists, duration and album art come from a separate Web-API
 * lookup. This store joins the two: [setPlayingStatus] carries the transport state, the injected
 * [SpotifyApiClient] carries the metadata, and the presence is only published once both agree.
 *
 * The state also self-expires. Spotify does not broadcast when a track ends, so a timer fires
 * 5 seconds past the expected end and clears the presence rather than leaving a stale "listening"
 * status up forever.
 */
class StoreSpotify(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val presences: StoreUserPresence,
    private val userConnections: StoreUserConnections,
    private val currentUserId: () -> Long,
    private val spotifyApiClient: SpotifyApiClient = SpotifyApiClient(clock),
    /** `AnalyticsTracker.activityUpdatedSpotify`. */
    private val trackActivityUpdated: (trackId: String?, hasArtwork: Boolean) -> Unit = { _, _ -> },
) {
    data class SpotifyState(
        val track: ModelSpotifyTrack? = null,
        val playing: Boolean = false,
        val position: Int = 0,
        val start: Long = 0L,
    )

    private val publishStateTrigger = BehaviorSubject.create<Unit>()
    private var spotifyState: SpotifyState? = null
    private var expireStateSub: Subscription? = null

    fun init(context: Context) {
        SpotifyHelper.registerSpotifyBroadcastReceivers(context, ::setCurrentTrackId, ::setPlayingStatus)
        Observable.combineLatest(
            publishStateTrigger,
            spotifyApiClient.getSpotifyTrack().distinctUntilChanged(),
        ) { _, track -> track }
            .debounce(TRACK_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .appSubscribe(StoreSpotify::class.java) { track ->
                dispatcher.schedule {
                    spotifyState?.let { state ->
                        spotifyState = state.copy(track = track)
                        expireStateSub?.unsubscribe()
                        if (track != null) {
                            startStateExpiration(track.durationMs + state.start - clock.currentTimeMillis())
                        }
                    }
                    publishState()
                }
            }
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        handleUserConnections(payload.connectedAccounts.orEmpty())
        publishStateTrigger.onNext(Unit)
    }

    @StoreThread
    fun handleConnectionReady(ready: Boolean) {
        if (ready) publishStateTrigger.onNext(Unit)
    }

    @StoreThread
    fun handlePreLogout() {
        spotifyState = null
        expireStateSub?.unsubscribe()
        expireStateSub = null
        spotifyApiClient.setSpotifyAccountId(null)
    }

    @StoreThread
    fun handleUserConnections(accounts: List<ConnectedAccount>) {
        spotifyApiClient.setSpotifyAccountId(
            accounts.firstOrNull { it.type.equals(SPOTIFY_PLATFORM_ID, ignoreCase = true) }?.id,
        )
    }

    /** Called from the Spotify broadcast receiver when a new track id starts. */
    fun setCurrentTrackId(trackId: String) = spotifyApiClient.fetchSpotifyTrack(trackId)

    /** Called from the Spotify broadcast receiver on play/pause/seek. */
    fun setPlayingStatus(playing: Boolean, position: Int) = dispatcher.schedule {
        val state = spotifyState ?: SpotifyState()
        spotifyState = state.copy(
            playing = playing,
            position = position,
            start = clock.currentTimeMillis() - position,
        )
        publishStateTrigger.onNext(Unit)
    }

    @StoreThread
    private fun startStateExpiration(timeLeftMs: Long) {
        Observable.timer(timeLeftMs + EXPIRATION_GRACE_MS, TimeUnit.MILLISECONDS)
            .appSubscribe(
                errorClass = StoreSpotify::class.java,
                subscriptionHandler = { expireStateSub = it },
            ) {
                dispatcher.schedule {
                    spotifyState = null
                    publishState()
                }
            }
    }

    /**
     * The presence is cleared, not just left alone, when the account has Spotify linked but with
     * "display as status" off — otherwise disabling the toggle would leave the last track up.
     */
    @StoreThread
    private fun publishState() {
        val state = spotifyState
        if (state == null) {
            presences.updateActivity(ActivityType.LISTENING, null, force = true)
            return
        }
        // No linked account with "display as status" on: leave whatever presence exists alone,
        // exactly as the reference does — the user opted out of us touching it.
        val showActivity = userConnections.getConnectedAccounts()
            .any { it.type == SPOTIFY_PLATFORM_ID && it.showActivity }
        if (!showActivity) return
        val track = state.track
        if (!state.playing || track == null) {
            presences.updateActivity(ActivityType.LISTENING, null, force = true)
            return
        }
        val albumArtId = track.album?.images?.firstOrNull()?.url?.substringAfterLast('/')
        presences.updateActivity(
            ActivityType.LISTENING,
            createSpotifyListeningActivity(
                createdAt = clock.currentTimeMillis(),
                musicSource = SPOTIFY_PROPER_NAME,
                trackName = track.name,
                trackId = track.id,
                album = track.album?.name,
                albumArtUrl = albumArtId?.let { "$SPOTIFY_PLATFORM_ID:$it" },
                artists = track.artists.joinToString { it.name },
                start = state.start,
                end = track.durationMs + state.start,
                partyId = "$SPOTIFY_PLATFORM_ID:${currentUserId()}",
            ),
            force = true,
        )
        trackActivityUpdated(track.id, track.album?.images?.isNotEmpty() == true)
    }

    companion object {
        private const val SPOTIFY_PLATFORM_ID = "spotify"
        private const val SPOTIFY_PROPER_NAME = "Spotify"
        private const val EXPIRATION_GRACE_MS = 5_000L
        private const val TRACK_DEBOUNCE_SECONDS = 2L

        /** `Activity.flags` value the reference stamps on Spotify presence (INSTANCE | SYNC | PLAY). */
        private const val SPOTIFY_ACTIVITY_FLAGS = 48

        /**
         * TODO(utilities): the reference keeps this in `utilities/presence/ActivityUtilsKt`
         *   alongside `createCustomStatusActivity`. It lives here because that file is owned by
         *   another wave.
         */
        internal fun createSpotifyListeningActivity(
            createdAt: Long,
            musicSource: String?,
            trackName: String?,
            trackId: String?,
            album: String?,
            albumArtUrl: String?,
            artists: String?,
            start: Long,
            end: Long,
            partyId: String?,
        ): Activity? {
            if (musicSource == null) return null
            return Activity(
                name = musicSource,
                type = ActivityType.LISTENING,
                url = null,
                createdAt = createdAt,
                timestamps = ActivityTimestamps(start.toString(), end.toString()),
                applicationId = null,
                details = trackName,
                state = artists,
                emoji = null,
                party = ActivityParty(partyId, null),
                assets = ActivityAssets(albumArtUrl, album, null, null),
                flags = SPOTIFY_ACTIVITY_FLAGS,
                syncId = trackId,
                sessionId = null,
                metadata = null,
                platform = null,
                supportedPlatforms = null,
                buttons = null,
            )
        }
    }
}
