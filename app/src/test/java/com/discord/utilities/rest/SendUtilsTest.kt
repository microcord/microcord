package com.discord.utilities.rest

import com.discord.restapi.RestAPIParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stream shape of the send payload.
 *
 * `MessageQueue.doSend` relies on the ordering here: a `Preprocessing` emission may only appear when
 * there is something to preprocess, and exactly one `ReadyToSend` ever reaches the network stage.
 *
 * The attachment-carrying branches are not covered by a plain JVM unit test because building an
 * `UploadAttachment` needs an `android.net.Uri`, which the framework stubs return as null. That
 * branch is covered by inspection against the reference `SendUtils.getSendPayload` and should get an
 * instrumented test when the store wave moves uploads onto the queue.
 */
class SendUtilsTest {

    private val body = RestAPIParams.Message(content = "hello", nonce = "12345")

    @Test
    fun aTextOnlySendEmitsExactlyOneReadyToSendAndNoPreprocessing() {
        val emissions = mutableListOf<SendUtils.SendPayload>()

        SendUtils.getSendPayload(contentResolver = null, message = body, attachments = null)
            .subscribe { emissions += it }

        val ready = emissions.single()
        assertTrue(ready is SendUtils.SendPayload.ReadyToSend)
        assertSame(body, (ready as SendUtils.SendPayload.ReadyToSend).message)
        assertTrue(ready.uploads.isEmpty())
    }

    @Test
    fun anEmptyAttachmentListBehavesLikeNoAttachments() {
        val emissions = mutableListOf<SendUtils.SendPayload>()

        SendUtils.getSendPayload(contentResolver = null, message = body, attachments = emptyList())
            .subscribe { emissions += it }

        assertEquals(1, emissions.size)
        assertTrue(emissions.single() is SendUtils.SendPayload.ReadyToSend)
    }

    @Test
    fun aTextOnlySendNeedsNoContentResolver() {
        var completed = false

        SendUtils.getSendPayload(contentResolver = null, message = body, attachments = null)
            .subscribe({ }, { throw it }, { completed = true })

        assertTrue(completed)
    }
}
