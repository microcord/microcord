package com.discord.stores

import com.discord.api.premium.PremiumTier
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.user.User
import com.discord.stores.StoreApplicationStreamPreviews.StreamPreview
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.error.RestApiError
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.time.Clock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import rx.Observable
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers
import rx.schedulers.TestScheduler
import java.util.concurrent.TimeUnit

class StoreApplicationStreamPreviewsTest {
    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val deck = ObservationDeck()
    private var now = 123L
    private var requests = 0
    private var requestedVersion = 0L
    private lateinit var store: StoreApplicationStreamPreviews

    @Before
    fun setUp() {
        RxJavaHooks.setOnIOScheduler { Schedulers.immediate() }
        RxJavaHooks.setOnComputationScheduler { scheduler }
        store = StoreApplicationStreamPreviews(
            dispatcher = dispatcher,
            clock = Clock { now },
            fetchPreview = { _, version ->
                requests++
                requestedVersion = version
                Observable.just("preview-$requests")
            },
            observationDeck = deck,
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
    }

    @After
    fun tearDown() = RxJavaHooks.reset()

    @Test
    fun fetchUsesClockCachesResultAndResolvedContextRefreshes() {
        val context = streamContext(null)

        store.fetchStreamPreviewIfNotFetching(context)
        scheduler.triggerActions()

        val resolved = store.getStreamKeyToPreviewMap()[STREAM_KEY]
        assertEquals(1, requests)
        assertEquals(now, requestedVersion)
        assertEquals(StreamPreview.Resolved("preview-1"), resolved)

        store.fetchStreamPreviewIfNotFetching(context.copy(preview = resolved))
        scheduler.triggerActions()
        assertEquals(2, requests)
    }

    @Test
    fun failuresRetryLinearlyAndResetAttemptsAfterSuccess() {
        fail(500)
        scheduler.advanceTimeBy(9_999, TimeUnit.MILLISECONDS)
        assertEquals(0, requests)
        scheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
        assertEquals(1, requests)

        fail(500)
        scheduler.advanceTimeBy(10, TimeUnit.SECONDS)
        assertEquals(2, requests)
    }

    @Test
    fun retryAfterOverridesDelayAndUnauthorizedFailuresAreTerminal() {
        fail(429, """{"retry_after":0.25}""")
        scheduler.advanceTimeBy(249, TimeUnit.MILLISECONDS)
        assertEquals(0, requests)
        scheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
        assertEquals(1, requests)

        fail(401)
        fail(403)
        scheduler.advanceTimeBy(1, TimeUnit.DAYS)
        assertEquals(1, requests)
        assertEquals(StreamPreview.Resolved(null), store.getStreamKeyToPreviewMap()[STREAM_KEY])
    }

    private fun fail(status: Int, body: String = "{}") {
        dispatcher.schedule { store.handleFetchFailed(STREAM_KEY, httpError(status, body)) }
        scheduler.triggerActions()
    }

    private fun streamContext(preview: StreamPreview?) = StreamContext(
        stream = STREAM,
        guild = null,
        preview = preview,
        joinability = StreamContext.Joinability.CAN_CONNECT,
        user = TEST_USER,
        userNickname = null,
        isCurrentUserParticipating = false,
        isSelfStream = false,
    )

    private fun httpError(status: Int, body: String = "{}") = RestApiError.from(
        HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType()))),
    )

    private companion object {
        val STREAM = ModelApplicationStream.CallStream(1L, 2L)
        val STREAM_KEY = STREAM.encodedStreamKey
        val TEST_USER = object : User {
            override val isSystemUser = false
            override val flags = 0
            override val publicFlags = 0
            override val avatar: String? = null
            override val bio: String? = null
            override val id = 2L
            override val bannerColor: String? = null
            override val banner: String? = null
            override val discriminator = 1
            override val username = "streamer"
            override val isBot = false
            override val premiumTier = PremiumTier.NONE
        }
    }
}
