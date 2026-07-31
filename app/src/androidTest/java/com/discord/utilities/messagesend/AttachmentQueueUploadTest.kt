package com.discord.utilities.messagesend

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.utilities.attachments.UploadAttachment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentQueueUploadTest {
    @Test
    fun localImageIsReadAndSentThroughTheMessageQueue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.cacheDir, "attachment-queue-test.png")
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).run {
            image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("{\"id\":1,\"channel_id\":42}"))
            start()
        }
        try {
            val api = RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
                .build(RestAPIInterface::class.java)
            val reconciled = CountDownLatch(1)
            val coordinator = MessageSendCoordinator(api, contentResolver = context.contentResolver)
            coordinator.observeUpdates().subscribe {
                if (it is MessageSendCoordinator.Update.Reconciled) reconciled.countDown()
            }

            coordinator.send(
                MessageSendCoordinator.Send(
                    channelId = 42,
                    content = "",
                    attachments = listOf(
                        UploadAttachment(
                            id = 1,
                            uri = Uri.fromFile(image),
                            displayName = image.name,
                            spoiler = false,
                            mimeType = "image/png",
                            size = image.length(),
                            permissionPersisted = false,
                        ),
                    ),
                ),
            )

            assertTrue(reconciled.await(10, TimeUnit.SECONDS))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("name=\"payload_json\""))
            assertTrue(body.contains("name=\"files[0]\"; filename=\"attachment-queue-test.png\""))
        } finally {
            server.shutdown()
            image.delete()
        }
    }
}
