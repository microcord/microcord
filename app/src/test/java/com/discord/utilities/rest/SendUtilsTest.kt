package com.discord.utilities.rest

import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommandLocalSendData
import com.discord.restapi.RestAPIParams
import com.discord.utilities.attachments.uniqueUploadFileNamesFromSanitized
import com.discord.widgets.chat.input.models.ApplicationCommandValue
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
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

    @Test
    fun commandAttachmentUrisAreRewrittenRecursivelyToUniqueUploadNames() {
        val names = uniqueUploadFileNamesFromSanitized(listOf("photo.png", "photo.png"))
        val command = command(
            listOf(
                ApplicationCommandValue(
                    name = "group",
                    type = ApplicationCommandType.SUBCOMMAND.type,
                    options = listOf(
                        attachmentValue("first", "content://one"),
                        attachmentValue("second", "content://two"),
                    ),
                ),
            ),
        )

        val rewritten = SendUtils.rewriteCommandAttachmentNames(
            command,
            listOf("content://one" to names[0], "content://two" to names[1]),
        )

        assertEquals(listOf("photo.png", "photo-1.png"), names)
        assertEquals(
            names,
            rewritten.applicationCommandsValues.single().options.orEmpty().map { it.value },
        )
        val wireAttachments = rewritten.toRestApiParam().data.attachments.orEmpty()
        assertEquals(listOf("photo.png", "photo-1.png"), wireAttachments.map { it.filename })
        assertEquals(listOf("0", "1"), wireAttachments.map { it.id })
    }

    @Test(expected = NoSuchElementException::class)
    fun commandAttachmentWithoutAMatchingUploadKeepsTheReferenceFailure() {
        SendUtils.rewriteCommandAttachmentNames(
            command(listOf(attachmentValue("file", "content://missing"))),
            listOf("content://other" to "other.png"),
        )
    }

    @Test(expected = NoSuchElementException::class)
    fun emptyCommandAttachmentListStillValidatesAttachmentOptions() {
        SendUtils.getSendCommandPayload(
            contentResolver = null,
            command = command(listOf(attachmentValue("file", "content://missing"))),
            attachments = emptyList(),
        )
    }

    @Test
    fun modernNestedCommandPayloadPreservesTypedValuesFocusAndIdentity() {
        val leaves = listOf(
            ApplicationCommandValue("user", "11", ApplicationCommandType.USER.type),
            ApplicationCommandValue("role", "12", ApplicationCommandType.ROLE.type),
            ApplicationCommandValue("mentionable", "13", ApplicationCommandType.MENTIONABLE.type),
            ApplicationCommandValue("channel", "14", ApplicationCommandType.CHANNEL.type),
            ApplicationCommandValue("file", "content://fixture", ApplicationCommandType.ATTACHMENT.type),
            ApplicationCommandValue("query", "typed", ApplicationCommandType.STRING.type, focused = true),
            ApplicationCommandValue("count", 2L, ApplicationCommandType.INTEGER.type),
        )
        val nested = ApplicationCommandValue(
            "resource",
            type = ApplicationCommandType.SUBCOMMAND_GROUP.type,
            options = listOf(
                ApplicationCommandValue(
                    "create",
                    type = ApplicationCommandType.SUBCOMMAND.type,
                    options = leaves,
                ),
            ),
        )
        val body = command(listOf(nested)).copy(
            guildId = 20L,
            commandGuildId = 21L,
            sessionId = "session-fixture",
            applicationCommandName = "manage",
            applicationCommandId = "200",
            version = "201",
        ).toRestApiParam()
        val json = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create().toJsonTree(body).asJsonObject

        assertEquals("123", json["nonce"].asString)
        assertEquals("session-fixture", json["session_id"].asString)
        assertEquals("9", json["application_id"].asString)
        assertEquals("20", json["guild_id"].asString)
        val data = json["data"].asJsonObject
        assertEquals("201", data["version"].asString)
        assertEquals("21", data["guild_id"].asString)
        assertEquals("200", data["id"].asString)
        assertEquals(1, data["type"].asInt)
        assertEquals("slash_ui", json["analytics_location"].asString)
        val wireLeaves = data["options"].asJsonArray[0].asJsonObject["options"].asJsonArray[0]
            .asJsonObject["options"].asJsonArray
        assertEquals(listOf("11", "12", "13", "14"), wireLeaves.take(4).map { it.asJsonObject["value"].asString })
        assertEquals("0", wireLeaves[4].asJsonObject["value"].asString)
        assertTrue(wireLeaves[5].asJsonObject["focused"].asBoolean)
        assertEquals(2L, wireLeaves[6].asJsonObject["value"].asLong)
        assertEquals("content://fixture", data["attachments"].asJsonArray[0].asJsonObject["filename"].asString)
    }

    private fun attachmentValue(name: String, uri: String) = ApplicationCommandValue(
        name = name,
        value = uri,
        type = ApplicationCommandType.ATTACHMENT.type,
    )

    private fun command(values: List<ApplicationCommandValue>) = ApplicationCommandLocalSendData(
        nonce = 123L,
        channelId = 42L,
        guildId = null,
        commandGuildId = null,
        application = Application(id = 9L, name = "test", commandCount = 1),
        sessionId = "session",
        applicationCommandName = "upload",
        applicationCommandId = "10",
        applicationCommandsValues = values,
        version = "1",
    )
}
