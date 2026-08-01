package com.discord.utilities.spotify

import com.discord.models.domain.ModelConnectionAccessToken
import com.discord.models.domain.spotify.ModelSpotifyTrack
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.time.Clock
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.Interceptor
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject

/** Resolves Spotify track metadata using the token for an already-linked Discord connection. */
class SpotifyApiClient(
    private val clock: Clock,
    private val accessTokenRequest: (String, String) -> Observable<ModelConnectionAccessToken> = { type, id ->
        RestAPI.apiOrNull?.getConnectionAccessToken(type, id) ?: Observable.empty()
    },
    private val trackRequest: (String, String) -> Observable<ModelSpotifyTrack> = ::requestTrack,
    private val requestScheduler: Scheduler = Schedulers.io(),
) {
    private val spotifyTracks = hashMapOf<String, ModelSpotifyTrack>()
    private val spotifyTrackSubject = BehaviorSubject.create<ModelSpotifyTrack?>()
    private var spotifyAccountId: String? = null
    private var spotifyToken: String? = null
    private var tokenExpiresAt = 0L
    private var tokenSubscription: Subscription? = null
    private var trackIdToFetch: String? = null

    fun getSpotifyTrack(): Observable<ModelSpotifyTrack?> = spotifyTrackSubject

    @Synchronized
    fun setSpotifyAccountId(id: String?) {
        if (spotifyAccountId == id) return
        spotifyAccountId = id
        spotifyToken = null
        tokenExpiresAt = 0L
        trackIdToFetch = null
        tokenSubscription?.unsubscribe()
        tokenSubscription = null
        spotifyTrackSubject.onNext(null)
    }

    fun fetchSpotifyTrack(trackId: String) {
        val cached = synchronized(this) { spotifyTracks[trackId] }
        if (cached != null) {
            spotifyTrackSubject.onNext(cached)
            return
        }
        spotifyTrackSubject.onNext(null)

        val token = synchronized(this) {
            if (spotifyAccountId == null) return
            spotifyToken?.takeUnless { clock.currentTimeMillis() >= tokenExpiresAt - TOKEN_EXPIRY_SKEW_MS }
        }
        if (token == null) refreshSpotifyToken(trackId) else fetchTrack(token, trackId, retryUnauthorized = true)
    }

    private fun refreshSpotifyToken(trackId: String) {
        val accountId = synchronized(this) {
            trackIdToFetch = trackId
            if (tokenSubscription?.isUnsubscribed == false) return
            spotifyAccountId
        } ?: return

        accessTokenRequest(SPOTIFY_PLATFORM_ID, accountId)
            .take(1)
            .subscribeOn(requestScheduler)
            .appSubscribe(
                errorClass = SpotifyApiClient::class.java,
                subscriptionHandler = { tokenSubscription = it },
                errorHandler = {
                    synchronized(this) { trackIdToFetch = null }
                    spotifyTrackSubject.onNext(null)
                },
                onTerminated = { tokenSubscription = null },
            ) { result ->
                val token = result.accessToken.takeIf(String::isNotBlank)
                val pendingTrack = synchronized(this) {
                    spotifyToken = token
                    tokenExpiresAt = if (token == null) 0L else clock.currentTimeMillis() + TOKEN_LIFETIME_MS
                    trackIdToFetch.also { trackIdToFetch = null }
                }
                if (token == null || pendingTrack == null) {
                    spotifyTrackSubject.onNext(null)
                } else {
                    fetchTrack(token, pendingTrack, retryUnauthorized = false)
                }
            }
    }

    private fun fetchTrack(token: String, trackId: String, retryUnauthorized: Boolean) {
        trackRequest(token, trackId)
            .take(1)
            .subscribeOn(requestScheduler)
            .appSubscribe(
                errorClass = SpotifyApiClient::class.java,
                errorHandler = { error ->
                    if (retryUnauthorized && error.statusCode == 401) {
                        synchronized(this) {
                            spotifyToken = null
                            tokenExpiresAt = 0L
                        }
                        refreshSpotifyToken(trackId)
                    } else {
                        spotifyTrackSubject.onNext(null)
                    }
                },
            ) { track ->
                synchronized(this) { spotifyTracks[track.id] = track }
                spotifyTrackSubject.onNext(track)
            }
    }

    private companion object {
        const val SPOTIFY_PLATFORM_ID = "spotify"
        const val SPOTIFY_API_BASE_URL = "https://api.spotify.com/v1/"
        val TOKEN_LIFETIME_MS = TimeUnit.HOURS.toMillis(1)
        const val TOKEN_EXPIRY_SKEW_MS = 10_000L

        fun requestTrack(token: String, trackId: String): Observable<ModelSpotifyTrack> {
            if (token.isBlank()) return Observable.empty()
            val authorization = Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build(),
                )
            }
            return RestAPIBuilder(SPOTIFY_API_BASE_URL, CookieJar.NO_COOKIES)
                .build(
                    RestAPIInterface::class.java,
                    interceptors = listOf(authorization),
                    addVersion = false,
                )
                .getSpotifyTrack(trackId)
        }
    }
}
