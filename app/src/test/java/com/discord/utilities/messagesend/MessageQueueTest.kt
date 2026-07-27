package com.discord.utilities.messagesend

import com.discord.api.message.Message as ApiMessage
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommandLocalSendData
import com.discord.models.message.Message
import com.discord.utilities.time.Clock
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers
import rx.schedulers.TestScheduler

/**
 * Behavioural contract for the per-channel send queue.
 *
 * The queue subscribes its REST work on `Schedulers.io()` and its delay timers on
 * `Schedulers.computation()`. [setUp] redirects the first to the immediate scheduler and the second
 * to a [TestScheduler], so every assertion below is about ordering and exact delays rather than
 * timing luck.
 */
class MessageQueueTest {

    private val clock = MutableClock()
    private val restApi = RecordingRestApi()
    private val backoffScheduler = ManualBackoffScheduler()
    private val timerScheduler = TestScheduler()
    private val settled = mutableListOf<Pair<String, MessageResult>>()
    private val lastInQueueFlags = mutableListOf<Boolean>()

    private val queue = MessageQueue(
        contentResolver = null,
        executorService = DirectExecutorService(),
        clock = clock,
        api = restApi.api,
        networkBackoff = testBackoff(backoffScheduler),
        retryScheduler = timerScheduler,
    )

    @Before
    fun setUp() {
        RxJavaHooks.setOnIOScheduler { Schedulers.immediate() }
        RxJavaHooks.setOnComputationScheduler { timerScheduler }
    }

    @After
    fun tearDown() {
        RxJavaHooks.reset()
    }

    // region ordering

    @Test
    fun sendsOneRequestAtATimeInFifoOrder() {
        enqueueSend("100")
        enqueueSend("200")
        enqueueSend("300")

        assertEquals(1, restApi.sendCalls.size)
        assertEquals(1, restApi.inFlightCount)
        assertEquals("100", restApi.sendCalls[0].body?.nonce)

        restApi.respondSuccess(apiMessage(nonce = "100"))
        assertEquals(listOf("100"), settled.map { it.first })
        assertEquals("200", restApi.sendCalls[1].body?.nonce)

        restApi.respondSuccess(apiMessage(nonce = "200"))
        restApi.respondSuccess(apiMessage(nonce = "300"))

        assertEquals(listOf("100", "200", "300"), settled.map { it.first })
        // Only the last request sees an empty queue behind it.
        assertEquals(listOf(false, false, true), lastInQueueFlags)
    }

    @Test
    fun successCarriesTheCreatedMessage() {
        enqueueSend("100")
        val created = apiMessage(nonce = "100", id = 987654321L)

        restApi.respondSuccess(created)

        val result = settled.single().second
        assertTrue(result is MessageResult.Success)
        assertSame(created, (result as MessageResult.Success).message)
    }

    @Test
    fun sendBodyCarriesTheNonceForServerSideDeduplication() {
        enqueueSend("4242")

        val call = restApi.sendCalls.single()
        assertEquals("4242", call.body?.nonce)
        assertEquals("hello", call.body?.content)
        assertFalse(call.multipart)
    }

    // endregion

    // region cancellation and timeout

    @Test
    fun cancellingAnInFlightRequestSettlesItExactlyOnceEvenIfTheResponseArrives() {
        enqueueSend("100")

        queue.cancel("100")
        assertEquals(listOf<MessageResult>(MessageResult.UserCancelled), settled.map { it.second })

        // A response that was already on the wire must not settle the request a second time.
        restApi.respondSuccess(apiMessage(nonce = "100"))
        assertEquals(1, settled.size)
    }

    @Test
    fun cancellingAQueuedRequestLeavesTheInFlightOneAlone() {
        enqueueSend("100")
        enqueueSend("200")

        queue.cancel("200")

        assertEquals(listOf("200" to MessageResult.UserCancelled), settled)
        assertEquals(1, restApi.inFlightCount)
    }

    @Test
    fun requestsOlderThanTheDeadlineTimeOutWithoutBeingSent() {
        enqueueSend("100", attemptTimestamp = clock.now - MessageQueue.MESSAGE_TIMEOUT_MS - 1)

        assertEquals(listOf<MessageResult>(MessageResult.Timeout), settled.map { it.second })
        assertEquals(0, restApi.sendCalls.size)

        // A fresh request behind it is still sent.
        enqueueSend("200")
        assertEquals("200", restApi.sendCalls.single().body?.nonce)
    }

    @Test
    fun cancelAllDiscardsQueuedAndInFlightRequestsWithoutSendingAnythingElse() {
        enqueueSend("100")
        enqueueSend("200")

        queue.cancelAll()

        assertEquals(setOf("100", "200"), settled.map { it.first }.toSet())
        assertTrue(settled.all { it.second == MessageResult.UserCancelled })
        // Cancelling the in-flight request must not drain the queue it just discarded.
        assertEquals(1, restApi.sendCalls.size)
    }

    // endregion

    // region validation

    @Test
    fun aMessageWithoutAttachmentsAlwaysPassesValidation() {
        val request = MessageRequest.Send(
            message = localMessage("100"),
            onCompleted = { _, _ -> },
            attemptTimestamp = clock.now,
        )

        assertEquals(null, request.validateMessage())
    }

    @Test
    fun theRequestIdOfASendIsItsNonce() {
        val request = MessageRequest.Send(
            message = localMessage("9182736455"),
            onCompleted = { _, _ -> },
            attemptTimestamp = clock.now,
        )

        assertEquals("9182736455", request.requestId)
    }

    @Test
    fun theRequestIdOfAnEditDefaultsToItsMessageId() {
        val request = MessageRequest.Edit(
            channelId = CHANNEL_ID,
            content = "edited",
            messageId = 555L,
            attemptTimestamp = clock.now,
            onCompleted = { _, _ -> },
        )

        assertEquals("555", request.requestId)
    }

    @Test
    fun commandRequestIdFallsBackToTheCommandNonce() {
        val command = commandData(1234L)
        val request = MessageRequest.SendApplicationCommand(
            message = ApiMessage(id = 1234L, channelId = CHANNEL_ID),
            applicationCommandSendData = command,
            attachments = null,
            onPreprocessing = {},
            onProgress = {},
            onCompleted = { _, _ -> },
            attemptTimestamp = clock.now,
        )

        assertEquals("1234", request.requestId)
    }

    @Test
    fun commandSuccessSettlesWithTheOptimisticCommandMessage() {
        val command = commandData(1234L)
        val message = ApiMessage(id = 1234L, channelId = CHANNEL_ID, nonce = "1234")
        queue.enqueue(commandRequest(message, command))

        assertEquals("1234", restApi.commandCalls.single().body.nonce)
        assertFalse(restApi.commandCalls.single().multipart)
        restApi.respondCommandSuccess()

        val result = settled.single().second as MessageResult.Success
        assertSame(message, result.message)
    }

    @Test
    fun commandFailureUsesTheOrdinaryQueueErrorSettlement() {
        val command = commandData(1234L)
        queue.enqueue(commandRequest(ApiMessage(1234L, CHANNEL_ID, nonce = "1234"), command))

        restApi.respondApiError(status = 400, json = """{"code":50035,"message":"Invalid Form Body"}""")

        assertTrue(settled.single().second is MessageResult.UnknownFailure)
    }

    // endregion

    // region failure classification

    @Test
    fun slowModeErrorCodeBecomesATerminalSlowmodeResultCarryingTheServerCooldown() {
        enqueueSend("100")

        restApi.respondApiError(status = 429, json = SLOW_MODE_BODY)

        assertEquals(MessageResult.Slowmode(2_500L), settled.single().second)
        // Slow mode is terminal: nothing is re-queued and no backoff is armed.
        assertEquals(1, restApi.sendCalls.size)
        assertFalse(backoffScheduler.hasPendingAction)
    }

    @Test
    fun slowModeWithoutRetryAfterFallsBackToTheDefaultDelay() {
        enqueueSend("100")

        restApi.respondApiError(status = 429, json = """{"code":20016,"message":"slow mode"}""")

        assertEquals(MessageResult.Slowmode(MessageQueue.DEFAULT_RETRY_MS), settled.single().second)
    }

    @Test
    fun autoModBlockCarriesTheErrorAndTheBlockedMessage() {
        enqueueSend("100")

        restApi.respondApiError(status = 400, json = AUTOMOD_BODY)

        val result = settled.single().second
        assertTrue(result is MessageResult.AutoModBlock)
        val block = result as MessageResult.AutoModBlock
        assertEquals(MessageQueue.AUTOMOD_BLOCK_ERROR_CODE, block.error.code)
        assertEquals("100", block.message?.nonce)
        assertEquals(CHANNEL_ID, block.message?.channelId)
    }

    @Test
    fun captchaRequiredCarriesTheChannelAndNonceNeededToResendThatMessage() {
        enqueueSend("100")

        restApi.respondApiError(status = 400, json = CAPTCHA_BODY)

        val result = settled.single().second
        assertTrue(result is MessageResult.CaptchaRequired)
        val captcha = result as MessageResult.CaptchaRequired
        assertEquals(CHANNEL_ID, captcha.channelId)
        assertEquals("100", captcha.nonce)
        assertEquals("test-site-key", captcha.siteKey)
        assertEquals("test-rqtoken", captcha.rqtoken)
    }

    @Test
    fun editsCannotProduceACaptchaChallengeBecauseTheyCarryNoClientMessage() {
        queue.enqueue(
            MessageRequest.Edit(
                channelId = CHANNEL_ID,
                content = "edited",
                messageId = 555L,
                attemptTimestamp = clock.now,
                onCompleted = { result, _ -> settled += "555" to result },
            ),
        )
        assertEquals(listOf(RecordingRestApi.EditCall(CHANNEL_ID, 555L, "edited")), restApi.editCalls)

        restApi.respondApiError(status = 400, json = CAPTCHA_BODY)

        assertTrue(settled.single().second is MessageResult.UnknownFailure)
    }

    @Test
    fun unclassifiedFailuresKeepTheStructuredError() {
        enqueueSend("100")

        restApi.respondApiError(status = 400, json = """{"code":50035,"message":"Invalid Form Body"}""")

        val result = settled.single().second
        assertTrue(result is MessageResult.UnknownFailure)
        val error = (result as MessageResult.UnknownFailure).error
        assertEquals(50035, error.code)
        assertEquals("Invalid Form Body", error.message)
    }

    // endregion

    // region retry behaviour

    @Test
    fun rateLimitRetriesTheSameRequestAtTheServerDelayAndKeepsItAheadOfTheQueue() {
        enqueueSend("100")
        enqueueSend("200")

        restApi.respondApiError(status = 429, json = RATE_LIMIT_BODY)

        // Nothing settles and nothing new starts while the retry timer is armed.
        assertTrue(settled.isEmpty())
        assertEquals(1, restApi.sendCalls.size)

        timerScheduler.advanceTimeBy(749, TimeUnit.MILLISECONDS)
        assertEquals(1, restApi.sendCalls.size)

        timerScheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
        assertEquals(2, restApi.sendCalls.size)
        // The rate-limited request goes again before the one queued behind it.
        assertEquals("100", restApi.sendCalls[1].body?.nonce)
    }

    @Test
    fun rawConnectivityFailuresAreRetriedByTheTransportLayerBeforeTheQueueSeesThem() {
        enqueueSend("100")

        // Reference RetryWithDelay policy: three retries at 1s, 2s and 4s for a raw IOException.
        restApi.respondError(UnknownHostException("offline"))
        assertEquals(1, restApi.sendCalls.size)

        timerScheduler.advanceTimeBy(1, TimeUnit.SECONDS)
        assertEquals(2, restApi.sendCalls.size)
        restApi.respondError(UnknownHostException("offline"))

        timerScheduler.advanceTimeBy(2, TimeUnit.SECONDS)
        assertEquals(3, restApi.sendCalls.size)
        restApi.respondError(UnknownHostException("offline"))

        timerScheduler.advanceTimeBy(4, TimeUnit.SECONDS)
        assertEquals(4, restApi.sendCalls.size)

        // Still nothing settled: the queue has not been told about the failure yet.
        assertTrue(settled.isEmpty())
        assertFalse(backoffScheduler.hasPendingAction)

        // The fourth failure exhausts the transport retries and reaches the queue.
        restApi.respondError(UnknownHostException("offline"))
        assertTrue(backoffScheduler.hasPendingAction)
    }

    @Test
    fun networkFailuresArmTheBackoffGateAndBlockTheWholeQueue() {
        enqueueSend("100")
        enqueueSend("200")

        failWithNetworkError()

        assertTrue(settled.isEmpty())
        // Growth is `current -> 3 * current` with jitter disabled, seeded at 5s.
        assertEquals(listOf(15_000L), backoffScheduler.scheduledDelays)
        // The pending gate stops the queued request from being attempted while offline.
        val callsBeforeRetry = restApi.sendCalls.size
        assertTrue(restApi.sendCalls.none { it.body?.nonce == "200" })

        backoffScheduler.fire()
        assertEquals(callsBeforeRetry + 1, restApi.sendCalls.size)
        // The failed request keeps its place at the front.
        assertEquals("100", restApi.sendCalls.last().body?.nonce)
    }

    @Test
    fun repeatedNetworkFailuresGrowTheBackoffDelay() {
        enqueueSend("100")

        failWithNetworkError()
        backoffScheduler.fire()
        failWithNetworkError()

        assertEquals(listOf(15_000L, 45_000L), backoffScheduler.scheduledDelays)
    }

    @Test
    fun reconnectCancelsThePendingBackoffAndDrainsImmediately() {
        enqueueSend("100")
        failWithNetworkError()
        val callsWhileOffline = restApi.sendCalls.size

        queue.handleConnected()

        assertEquals(callsWhileOffline + 1, restApi.sendCalls.size)
        assertFalse(backoffScheduler.hasPendingAction)
    }

    @Test
    fun reconnectWithNoPendingBackoffChangesNothing() {
        enqueueSend("100")

        queue.handleConnected()

        assertEquals(1, restApi.sendCalls.size)
    }

    @Test
    fun aSuccessfulSendResetsTheAccumulatedBackoff() {
        enqueueSend("100")
        failWithNetworkError()
        backoffScheduler.fire()
        restApi.respondSuccess(apiMessage(nonce = "100"))

        enqueueSend("200")
        failWithNetworkError()

        // The delay restarts from the seed rather than continuing to grow.
        assertEquals(listOf(15_000L, 15_000L), backoffScheduler.scheduledDelays)
    }

    // endregion

    /**
     * Fails the in-flight request the way a device with no connectivity would: the transport layer
     * burns its three retries first, then the queue's own backoff takes over.
     */
    private fun failWithNetworkError() {
        restApi.respondError(UnknownHostException("offline"))
        listOf(1L, 2L, 4L).forEach { delaySeconds ->
            timerScheduler.advanceTimeBy(delaySeconds, TimeUnit.SECONDS)
            restApi.respondError(UnknownHostException("offline"))
        }
    }

    private fun enqueueSend(nonce: String, attemptTimestamp: Long = clock.now) {
        queue.enqueue(
            MessageRequest.Send(
                message = localMessage(nonce),
                onCompleted = { result, isLast ->
                    settled += nonce to result
                    lastInQueueFlags += isLast
                },
                attemptTimestamp = attemptTimestamp,
            ),
        )
    }

    private fun localMessage(nonce: String) = Message(
        id = nonce.toLong(),
        channelId = CHANNEL_ID,
        content = "hello",
        nonce = nonce,
        type = -1,
    )

    private fun apiMessage(nonce: String, id: Long = nonce.toLong()) =
        ApiMessage(id = id, channelId = CHANNEL_ID, content = "hello", nonce = nonce)

    private fun commandRequest(message: ApiMessage, command: ApplicationCommandLocalSendData) =
        MessageRequest.SendApplicationCommand(
            message = message,
            applicationCommandSendData = command,
            attachments = null,
            onPreprocessing = {},
            onProgress = {},
            onCompleted = { result, isLast ->
                settled += command.nonceString to result
                lastInQueueFlags += isLast
            },
            attemptTimestamp = clock.now,
        )

    private fun commandData(nonce: Long) = ApplicationCommandLocalSendData(
        nonce = nonce,
        channelId = CHANNEL_ID,
        guildId = null,
        commandGuildId = null,
        application = Application(id = 9L, name = "test", commandCount = 1),
        sessionId = "session",
        applicationCommandName = "ping",
        applicationCommandId = "10",
        applicationCommandsValues = emptyList(),
        version = "1",
    )

    private class MutableClock(var now: Long = 1_700_000_000_000L) : Clock {
        override fun currentTimeMillis() = now
    }

    private companion object {
        const val CHANNEL_ID = 42L
    }
}
