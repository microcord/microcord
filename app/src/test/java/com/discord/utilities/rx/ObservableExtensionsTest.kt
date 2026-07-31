package com.discord.utilities.rx

import com.discord.models.domain.ModelUserRelationship
import com.discord.utilities.error.RestApiError
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import rx.Observable
import rx.Subscription
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers
import rx.schedulers.TestScheduler
import rx.subjects.PublishSubject

/**
 * The error funnel is the point of `appSubscribe`: a stream failure must be classified, offered to
 * the caller, and reported — never silently dropped the way `subscribe({}, {})` drops it.
 */
class ObservableExtensionsTest {

    private val reported = mutableListOf<Pair<String, Throwable>>()

    @Before
    fun setUp() {
        RestApiError.init { tag, throwable, _ -> reported += tag to throwable }
    }

    @After
    fun tearDown() {
        RestApiError.init { _, _, _ -> }
    }

    @Test
    fun successDeliversValuesThenCompletionThenTermination() {
        val events = mutableListOf<String>()
        var subscription: Subscription? = null

        Observable.just(1, 2).appSubscribe(
            errorClass = ObservableExtensionsTest::class.java,
            subscriptionHandler = { subscription = it },
            onCompleted = { events += "completed" },
            onTerminated = { events += "terminated" },
            onNext = { events += "next:$it" },
        )

        assertEquals(listOf("next:1", "next:2", "completed", "terminated"), events)
        assertNotNull(subscription)
    }

    @Test
    fun failuresReachTheErrorHandlerAsAClassifiedErrorAndStillTerminate() {
        var captured: RestApiError? = null
        var terminated = false

        Observable.error<Int>(badRequest())
            .appSubscribe(
                errorClass = ObservableExtensionsTest::class.java,
                errorHandler = { captured = it },
                onTerminated = { terminated = true },
                onNext = { },
            )

        assertNotNull(captured)
        assertEquals(RestApiError.Type.DISCORD_BAD_REQUEST, captured?.type)
        assertEquals(50035, captured?.code)
        assertTrue(terminated)
    }

    @Test
    fun unclassifiedFailuresAreReportedToTheUnhandledSinkUnderTheErrorTag() {
        val boom = IllegalStateException("boom")

        Observable.error<Int>(boom).appSubscribe(errorTag = "SomeFeature", onNext = { })

        assertEquals(listOf("SomeFeature" to boom as Throwable), reported)
    }

    @Test
    fun expectedConnectivityFailuresAreNotReportedAsBugs() {
        Observable.error<Int>(IOException("Canceled")).appSubscribe(errorTag = "SomeFeature", onNext = { })

        assertTrue(reported.isEmpty())
    }

    @Test
    fun anErrorHandlerThatThrowsIsReportedRatherThanPropagated() {
        Observable.error<Int>(badRequest()).appSubscribe(
            errorClass = ObservableExtensionsTest::class.java,
            errorHandler = { error("handler blew up") },
            onNext = { },
        )

        assertEquals(1, reported.size)
        assertEquals("ObservableExtensionsTest", reported.single().first)
    }

    @Test
    fun aFailureWithNoErrorHandlerIsStillClassifiedAndNotSwallowed() {
        var terminated = false

        Observable.error<Int>(badRequest()).appSubscribe(
            errorTag = "SomeFeature",
            onTerminated = { terminated = true },
            onNext = { },
        )

        assertTrue(terminated)
    }

    @Test
    fun filterNullDropsNullsAndRetypesTheStream() {
        val values = mutableListOf<Int>()

        Observable.from(listOf(1, null, 3)).filterNull().subscribe { values += it }

        assertEquals(listOf(1, 3), values)
    }

    @Test
    fun filterIsKeepsOnlyTheRequestedSubtype() {
        val values = mutableListOf<String>()

        Observable.from(listOf<Any>(1, "two", 3.0, "four"))
            .filterIs<String>()
            .subscribe { values += it }

        assertEquals(listOf("two", "four"), values)
    }

    @Test
    fun toMapCollectsPairsIntoASingleEmission() {
        var result: Map<String, Int>? = null

        Observable.from(listOf("a" to 1, "b" to 2)).toMap().subscribe { result = it }

        assertEquals(mapOf("a" to 1, "b" to 2), result)
    }

    @Test
    fun leadingEdgeThrottleRetainsTheLatestStoreChangeInABurst() {
        val scheduler = TestScheduler()
        RxJavaHooks.setOnComputationScheduler { scheduler }
        try {
            val relationships = PublishSubject.create<Map<Long, Int>>()
            val seen = mutableListOf<Map<Long, Int>>()
            relationships.leadingEdgeThrottle(1, TimeUnit.SECONDS).subscribe { seen += it }

            relationships.onNext(emptyMap())
            val incoming = mapOf(42L to ModelUserRelationship.TYPE_INVITE_INCOMING)
            val accepted = mapOf(42L to ModelUserRelationship.TYPE_FRIEND)
            relationships.onNext(incoming)
            assertEquals(listOf(emptyMap<Long, Int>()), seen)

            scheduler.advanceTimeBy(1, TimeUnit.SECONDS)
            assertEquals(listOf(emptyMap(), incoming), seen)

            relationships.onNext(accepted)
            relationships.onNext(emptyMap())
            assertEquals(listOf(emptyMap(), incoming, accepted), seen)

            scheduler.advanceTimeBy(1, TimeUnit.SECONDS)
            assertEquals(listOf(emptyMap(), incoming, accepted, emptyMap()), seen)
        } finally {
            RxJavaHooks.reset()
        }
    }

    @Test
    fun httpFailuresAreNotRetriedAtTheTransportLayerEvenWhenRetryIsRequested() {
        RxJavaHooks.setOnIOScheduler { Schedulers.immediate() }
        try {
            var attempts = 0
            var failed = false

            Observable.fromCallable<Int> { attempts++; throw badRequest() }
                .restSubscribeOn(retry = true)
                .subscribe({ }, { failed = true })

            assertEquals(1, attempts)
            assertTrue(failed)
        } finally {
            RxJavaHooks.reset()
        }
    }

    private fun badRequest() = HttpException(
        Response.error<Any>(
            400,
            """{"code":50035,"message":"Invalid Form Body"}""".toResponseBody("application/json".toMediaType()),
        ),
    )
}
