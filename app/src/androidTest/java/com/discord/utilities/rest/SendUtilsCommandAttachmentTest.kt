package com.discord.utilities.rest

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommandLocalSendData
import com.discord.utilities.attachments.UploadAttachment
import com.discord.widgets.chat.input.models.ApplicationCommandValue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SendUtilsCommandAttachmentTest {
    @Test
    fun commandAttachmentsAreRenamedAndBuiltAsIndexedMultipartParts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = listOf(
            File(context.cacheDir, "command-attachment-a").apply { writeText("a") },
            File(context.cacheDir, "command-attachment-b").apply { writeText("b") },
        )
        val attachments = files.mapIndexed { index, file ->
            UploadAttachment(
                id = index.toLong(),
                uri = Uri.fromFile(file),
                displayName = "photo.png",
                spoiler = false,
                mimeType = "image/png",
                size = file.length(),
                permissionPersisted = false,
            )
        }
        val command = command(attachments.mapIndexed { index, attachment ->
            ApplicationCommandValue(
                name = "file$index",
                value = attachment.uri.toString(),
                type = ApplicationCommandType.ATTACHMENT.type,
            )
        })

        val emissions = SendUtils.getSendCommandPayload(context.contentResolver, command, attachments)
            .toList()
            .toBlocking()
            .single()

        val preprocessing = emissions.first() as SendUtils.SendPayload.Preprocessing
        assertEquals(2, preprocessing.numFiles)
        assertNull(preprocessing.name)
        assertNull(preprocessing.mimeType)

        val ready = emissions.last() as SendUtils.SendPayload.ReadyToSendCommand
        assertEquals(listOf("photo.png", "photo-1.png"), ready.command.applicationCommandsValues.map { it.value })
        assertEquals(listOf("photo.png", "photo-1.png"), ready.uploads.map { it.name })
        assertEquals(
            listOf("form-data; name=\"files[0]\"; filename=\"photo.png\"", "form-data; name=\"files[1]\"; filename=\"photo-1.png\""),
            ready.uploads.map { it.part.headers?.get("Content-Disposition") },
        )

        files.forEach(File::delete)
    }

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
