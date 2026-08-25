package com.discord.utilities.messagesend

import android.net.Uri
import com.discord.api.message.Message as ApiMessage
import com.discord.api.message.MessageReference
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerType
import com.discord.api.user.User
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommandLocalSendData
import com.discord.models.message.Message
import com.discord.stores.StorePendingReplies
import com.discord.utilities.captcha.CaptchaSolution
import com.discord.utilities.attachments.UploadAttachment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers
import rx.schedulers.TestScheduler

/**
 * The coordinator owns the optimistic message: it must reconcile a send by nonce, surface the
 * failure payloads the UI needs, and be able to re-send the same message once a captcha is solved.
 */
class MessageSendCoordinatorTest {

    private val restApi = RecordingRestApi()
    private val timerScheduler = TestScheduler()
    private var now = 1_700_000_000_000L
    private val updates = mutableListOf<MessageSendCoordinator.Update>()

    private val coordinator = MessageSendCoordinator(
        api = restApi.api,
        now = { now },
        contentResolver = null,
        queueExecutor = DirectExecutorService(),
        retryScheduler = timerScheduler,
    )

    @Before
    fun setUp() {
        RxJavaHooks.setOnIOScheduler { Schedulers.immediate() }
        RxJavaHooks.setOnComputationScheduler { timerScheduler }
        coordinator.observeUpdates().subscribe { updates += it }
    }

    @After
    fun tearDown() {
        RxJavaHooks.reset()
    }

    @Test
    fun sendEmitsAnOptimisticMessageKeyedByNonceAndReconcilesWithTheServerCopy() {
        val submission = coordinator.send(
            MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "hi", author = User(7L, "me")),
        )

        assertTrue(submission is MessageSendCoordinator.Submission.Accepted)
        val accepted = submission as MessageSendCoordinator.Submission.Accepted
        val nonce = accepted.requestId
        assertEquals(nonce, accepted.optimistic.nonce)
        assertEquals(-1, accepted.optimistic.type)
        assertEquals("me", accepted.optimistic.author?.username)
        assertEquals(nonce, restApi.sendCalls.single().body?.nonce)

        restApi.respondSuccess(ApiMessage(id = 5_000L, channelId = CHANNEL_ID, content = "hi", nonce = nonce))

        val reconciled = updates.filterIsInstance<MessageSendCoordinator.Update.Reconciled>().single()
        assertEquals(nonce, reconciled.requestId)
        assertEquals(5_000L, reconciled.message.id)
        // The optimistic id is replaced by the server id, but the nonce still ties them together.
        assertEquals(nonce, reconciled.message.nonce)
    }

    @Test
    fun oversizedContentIsRejectedEvenWithAnAttachment() {
        val submission = coordinator.send(
            MessageSendCoordinator.Send(
                channelId = CHANNEL_ID,
                content = "x".repeat(MessageSendCoordinator.DEFAULT_CHARACTER_LIMIT + 1),
                attachments = listOf(
                    UploadAttachment(
                        id = 0L,
                        uri = Uri.parse("file:///message.txt"),
                        displayName = "message.txt",
                        spoiler = false,
                        mimeType = MessageSendCoordinator.TEXT_FILE_MIME_TYPE,
                        size = 1L,
                        permissionPersisted = false,
                    ),
                ),
            ),
        )

        assertTrue(submission is MessageSendCoordinator.Submission.Rejected)
    }

    @Test
    fun aReplyCarriesTheMessageReferenceAndTheMentionToggle() {
        val original = Message(id = 12L, channelId = CHANNEL_ID, content = "original")
        coordinator.send(
            MessageSendCoordinator.Send(
                channelId = CHANNEL_ID,
                content = "reply",
                reply = StorePendingReplies.PendingReply(
                    MessageReference(GUILD_ID, CHANNEL_ID, 12L),
                    original,
                    shouldMention = false,
                ),
            ),
        )

        val body = restApi.sendCalls.single().body
        assertEquals(12L, body?.messageReference?.messageId)
        assertEquals(CHANNEL_ID, body?.messageReference?.channelId)
        assertEquals(listOf("users", "roles", "everyone"), body?.allowedMentions?.parse)
        assertEquals(false, body?.allowedMentions?.repliedUser)
    }

    @Test
    fun enabledReplyMentionLeavesDefaultMentionParsingEnabled() {
        coordinator.send(
            MessageSendCoordinator.Send(
                channelId = CHANNEL_ID,
                content = "reply <@42>",
                reply = StorePendingReplies.PendingReply(
                    MessageReference(GUILD_ID, CHANNEL_ID, 12L),
                    Message(id = 12L, channelId = CHANNEL_ID),
                    shouldMention = true,
                ),
            ),
        )

        assertNull(restApi.sendCalls.single().body?.allowedMentions)
    }

    @Test
    fun aCaptchaChallengeMarksTheMessageAndTheSolvedTokenResendsTheSameMessage() {
        val nonce = acceptedNonce(coordinator.send(MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "hi")))

        restApi.respondApiError(status = 400, json = CAPTCHA_BODY)

        val failed = updates.filterIsInstance<MessageSendCoordinator.Update.Failed>().single()
        assertEquals(nonce, failed.requestId)
        assertEquals(-6, failed.message.type)
        val result = failed.result
        assertTrue(result is MessageResult.CaptchaRequired)
        assertEquals("test-site-key", (result as MessageResult.CaptchaRequired).challenge?.captchaSitekey)
        assertEquals(nonce, result.nonce)

        val resent = coordinator.retry(nonce, CaptchaSolution("solved-token", "test-rqtoken"))

        assertTrue(resent)
        val retry = restApi.sendCalls.last()
        assertEquals(nonce, retry.body?.nonce)
        // Deprecated body fields are gone: the solution rides on the request policy as headers.
        assertEquals(false, retry.captcha?.handleInline)
        assertEquals(
            mapOf("X-Captcha-Key" to "solved-token", "X-Captcha-Rqtoken" to "test-rqtoken"),
            retry.captcha?.solution?.asHeaders(),
        )

        restApi.respondSuccess(ApiMessage(id = 6_000L, channelId = CHANNEL_ID, content = "hi", nonce = nonce))
        assertEquals(1, updates.filterIsInstance<MessageSendCoordinator.Update.Reconciled>().size)
    }

    @Test
    fun anAutoModBlockMarksTheMessageWithTheBlockedType() {
        coordinator.send(MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "hi"))

        restApi.respondApiError(status = 400, json = AUTOMOD_BODY)

        val failed = updates.filterIsInstance<MessageSendCoordinator.Update.Failed>().single()
        assertEquals(-8, failed.message.type)
        assertTrue(failed.result is MessageResult.AutoModBlock)
    }

    @Test
    fun slowModeMarksTheMessageFailedAndSurfacesTheCooldown() {
        coordinator.send(MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "hi"))

        restApi.respondApiError(status = 429, json = SLOW_MODE_BODY)

        val failed = updates.filterIsInstance<MessageSendCoordinator.Update.Failed>().single()
        assertEquals(-2, failed.message.type)
        assertEquals(MessageResult.Slowmode(2_500L), failed.result)
    }

    @Test
    fun cancellingRemovesTheOperationAndTellsTheQueue() {
        val nonce = acceptedNonce(coordinator.send(MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "hi")))

        coordinator.cancel(nonce)

        assertEquals(listOf(nonce), updates.filterIsInstance<MessageSendCoordinator.Update.Cancelled>().map { it.requestId })
        assertFalse(coordinator.retry(nonce))
    }

    @Test
    fun validationRejectionsNeverReachTheNetwork() {
        assertRejected(coordinator.send(MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "   ")))
        assertRejected(coordinator.send(MessageSendCoordinator.Send(channelId = 0L, content = "hi")))
        assertRejected(
            coordinator.send(MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "hi", canSend = false)),
        )
        assertRejected(
            coordinator.send(
                MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "hi", communicationDisabled = true),
            ),
        )
        assertRejected(
            coordinator.send(MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "x".repeat(2001))),
        )

        assertEquals(0, restApi.sendCalls.size)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun editUsesTheEditEndpointAndKeepsAnEditScopedRequestId() {
        val existing = Message(id = 77L, channelId = CHANNEL_ID, content = "old")

        val submission = coordinator.edit(existing, "new")

        val accepted = submission as MessageSendCoordinator.Submission.Accepted
        assertTrue(accepted.requestId.startsWith("edit:$CHANNEL_ID:77:"))
        assertEquals(listOf(RecordingRestApi.EditCall(CHANNEL_ID, 77L, "new")), restApi.editCalls)
        assertEquals(0, restApi.sendCalls.size)
    }

    @Test
    fun aFailedEditRestoresTheOriginalMessage() {
        val existing = Message(id = 77L, channelId = CHANNEL_ID, content = "old")
        coordinator.edit(existing, "new")

        restApi.respondApiError(status = 400, json = """{"code":50035,"message":"Invalid Form Body"}""")

        val failed = updates.filterIsInstance<MessageSendCoordinator.Update.Failed>().single()
        assertEquals("old", failed.message.content)
    }

    @Test
    fun typingIsThrottledAndIgnoresEmptyInput() {
        coordinator.typing(CHANNEL_ID, "")
        assertEquals(0, restApi.typingCalls.size)

        coordinator.typing(CHANNEL_ID, "a")
        coordinator.typing(CHANNEL_ID, "ab")
        assertEquals(listOf(CHANNEL_ID), restApi.typingCalls)

        now += 9_000L
        coordinator.typing(CHANNEL_ID, "abc")
        assertEquals(listOf(CHANNEL_ID, CHANNEL_ID), restApi.typingCalls)
    }

    @Test
    fun resumeRequeuesALocalMessageAndIgnoresOneWithoutANonce() {
        val local = Message(id = 900L, channelId = CHANNEL_ID, content = "queued", nonce = "900", type = -2)

        assertTrue(coordinator.resume(local))
        assertEquals("900", restApi.sendCalls.single().body?.nonce)

        assertFalse(coordinator.resume(local.copy(nonce = null)))
    }

    @Test
    fun uploadEventsAreSeparateFromMessageUpdates() {
        val events = mutableListOf<MessageSendCoordinator.UploadEvent>()
        coordinator.observeUploadEvents().subscribe { events += it }

        coordinator.send(MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "hi"))

        // A text-only send never preprocesses attachments, so the upload stream stays silent.
        assertTrue(events.isEmpty())
        assertNull(updates.filterIsInstance<MessageSendCoordinator.Update.Failed>().firstOrNull())
    }

    @Test
    fun `sticker only send is accepted and uses react native metadata client`() {
        val sticker = Sticker(
            id = 99L,
            packId = 7L,
            guildId = null,
            name = "wave",
            description = "",
            formatType = StickerFormatType.PNG,
            tags = "wave",
            type = StickerType.STANDARD,
        )

        val submission = coordinator.send(
            MessageSendCoordinator.Send(
                channelId = CHANNEL_ID,
                content = "",
                author = User(7L, "me"),
                stickers = listOf(sticker),
            ),
        )

        assertTrue(submission is MessageSendCoordinator.Submission.Accepted)
        assertEquals(listOf(99L), restApi.sendCalls.single().body?.stickerIds)
    }

    @Test
    fun applicationCommandsReuseTheChannelsExistingMessageQueue() {
        val ordinary = coordinator.send(MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "first"))
        val ordinaryNonce = acceptedNonce(ordinary)
        val command = commandData(1234L)
        val commandResults = mutableListOf<MessageResult>()

        coordinator.sendApplicationCommand(
            ApiMessage(id = command.nonce, channelId = CHANNEL_ID, nonce = command.nonceString),
            command,
        ).subscribe { commandResults += it }

        assertTrue(restApi.commandCalls.isEmpty())
        restApi.respondSuccess(ApiMessage(id = 5000L, channelId = CHANNEL_ID, nonce = ordinaryNonce))
        assertEquals(1, restApi.commandCalls.size)

        restApi.respondCommandSuccess()
        assertTrue(commandResults.single() is MessageResult.Success)
    }

    @Test
    fun cancellingAQueuedApplicationCommandRemovesItFromTheSharedChannelQueue() {
        coordinator.send(MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "first"))
        val command = commandData(1234L)
        val commandResults = mutableListOf<MessageResult>()
        coordinator.sendApplicationCommand(
            ApiMessage(id = command.nonce, channelId = CHANNEL_ID, nonce = command.nonceString),
            command,
        ).subscribe { commandResults += it }

        coordinator.cancel(command.nonceString)
        restApi.respondSuccess(ApiMessage(id = 5000L, channelId = CHANNEL_ID))

        assertTrue(restApi.commandCalls.isEmpty())
        assertEquals(listOf<MessageResult>(MessageResult.UserCancelled), commandResults)
    }

    @Test
    fun failedApplicationCommandSettlesAndLetsTheChannelQueueContinue() {
        val command = commandData(1234L)
        val commandResults = mutableListOf<MessageResult>()
        coordinator.sendApplicationCommand(
            ApiMessage(id = command.nonce, channelId = CHANNEL_ID, nonce = command.nonceString),
            command,
        ).subscribe { commandResults += it }

        restApi.respondApiError(400, """{"code":40060,"message":"Interaction failed"}""")

        assertTrue(commandResults.single() is MessageResult.UnknownFailure)
        val next = coordinator.send(MessageSendCoordinator.Send(channelId = CHANNEL_ID, content = "after"))
        assertTrue(next is MessageSendCoordinator.Submission.Accepted)
        assertEquals(1, restApi.sendCalls.size)
    }

    private fun acceptedNonce(submission: MessageSendCoordinator.Submission) =
        (submission as MessageSendCoordinator.Submission.Accepted).requestId

    private fun assertRejected(submission: MessageSendCoordinator.Submission) =
        assertTrue("expected a rejection but got $submission", submission is MessageSendCoordinator.Submission.Rejected)

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

    private companion object {
        const val CHANNEL_ID = 42L
        const val GUILD_ID = 7L
    }
}
