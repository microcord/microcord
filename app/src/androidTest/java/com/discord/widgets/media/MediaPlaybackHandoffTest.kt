package com.discord.widgets.media

import android.content.Intent
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.EmbedVideo
import com.discord.api.message.embed.MessageEmbed
import com.discord.app.AppScreenRouter
import com.discord.widgets.chat.list.InlineMediaView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPlaybackHandoffTest {
    @Test
    fun attachmentEmbedAndSelectedGalleryVideoCarryPositionButStillsDoNot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var launched: Intent? = null
            val context = object : ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark) {
                override fun startActivity(intent: Intent) { launched = intent }
            }
            val video = MessageAttachment("content://fixture/video.mp4", 1, 1,
                "content://fixture/video.mp4", "video.mp4", 160, 90)
            val image = video.copy(url = "content://fixture/image.png", proxyUrl = "content://fixture/image.png",
                filename = "image.png", id = 2)
            fun position() = requireNotNull(launched).getLongExtra("INTENT_INITIAL_POSITION_MS", -1L)

            WidgetMedia.launch(context, video, 42_345L)
            assertEquals(WidgetMedia::class.java, AppScreenRouter.screenOf(launched))
            assertEquals(42_345L, position())
            WidgetMedia.launch(context, video)
            assertEquals(0L, position())
            WidgetMedia.launch(context, image, 42_345L)
            assertEquals(0L, position())
            WidgetMedia.launchGallery(context, listOf(image, video), 1, initialPositionMs = 42_345L)
            assertEquals(1, launched!!.getIntExtra("INTENT_GALLERY_INDEX", -1))
            assertEquals(42_345L, position())
            WidgetMedia.launchGallery(context, listOf(image, video), 0, initialPositionMs = 42_345L)
            assertEquals(0L, position())

            val embed = MessageEmbed(null, EmbedType.VIDEO, null, "https://example.com/page", null, null,
                null, null, null, EmbedVideo(video.url, video.proxyUrl, 90, 160), null, null, null)
            WidgetMedia.launch(context, embed, 42_345L)
            assertEquals(42_345L, position())
            assertEquals(0L, InlineMediaView(context).currentPositionMs)
        }
    }
}
