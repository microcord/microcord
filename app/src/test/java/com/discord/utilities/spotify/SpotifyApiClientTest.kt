package com.discord.utilities.spotify

import com.discord.models.domain.ModelConnectionAccessToken
import com.discord.models.domain.spotify.ModelSpotifyTrack
import com.discord.utilities.integrations.SpotifyHelper
import com.discord.utilities.time.Clock
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import rx.Observable
import rx.observers.TestSubscriber
import rx.schedulers.Schedulers

class SpotifyApiClientTest {
    private val track = ModelSpotifyTrack(
        id = "track-id",
        name = "Track",
        durationMs = 123_000L,
        album = null,
        artists = emptyList(),
        isLocal = false,
    )

    @Test
    fun `playback broadcast accepts only Spotify track identifiers`() {
        assertEquals("0123456789abcdefghijkl", SpotifyHelper.spotifyTrackId("spotify:track:0123456789abcdefghijkl"))
        assertNull(SpotifyHelper.spotifyTrackId("spotify:album:0123456789abcdefghijkl"))
        assertNull(SpotifyHelper.spotifyTrackId("spotify:track:../not-a-track"))
    }

    @Test
    fun `missing linked account fails closed`() {
        var tokenRequests = 0
        var trackRequests = 0
        val client = SpotifyApiClient(
            clock = Clock { 1_000L },
            accessTokenRequest = { _, _ ->
                tokenRequests++
                Observable.just(ModelConnectionAccessToken("token"))
            },
            trackRequest = { _, _ ->
                trackRequests++
                Observable.just(track)
            },
            requestScheduler = Schedulers.immediate(),
        )
        val states = TestSubscriber<ModelSpotifyTrack?>()
        client.getSpotifyTrack().subscribe(states)

        client.fetchSpotifyTrack(track.id)

        assertEquals(listOf<ModelSpotifyTrack?>(null), states.onNextEvents)
        assertEquals(0, tokenRequests)
        assertEquals(0, trackRequests)
    }

    @Test
    fun `linked account exchanges token and caches track`() {
        var tokenRequests = 0
        var trackRequests = 0
        val client = SpotifyApiClient(
            clock = Clock { 1_000L },
            accessTokenRequest = { type, accountId ->
                assertEquals("spotify", type)
                assertEquals("account-id", accountId)
                tokenRequests++
                Observable.just(ModelConnectionAccessToken("token"))
            },
            trackRequest = { token, trackId ->
                assertEquals("token", token)
                assertEquals(track.id, trackId)
                trackRequests++
                Observable.just(track)
            },
            requestScheduler = Schedulers.immediate(),
        )
        val states = TestSubscriber<ModelSpotifyTrack?>()
        client.getSpotifyTrack().subscribe(states)

        client.setSpotifyAccountId("account-id")
        client.fetchSpotifyTrack(track.id)
        client.fetchSpotifyTrack(track.id)

        assertEquals(track, states.onNextEvents.last())
        assertEquals(1, tokenRequests)
        assertEquals(1, trackRequests)
    }
}
