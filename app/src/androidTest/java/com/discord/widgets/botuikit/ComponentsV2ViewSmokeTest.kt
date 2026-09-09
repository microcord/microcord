package com.discord.widgets.botuikit

import android.content.Intent
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.UnfurledMediaItem
import com.discord.app.AppScreenRouter
import com.discord.databinding.BotUikitModalCheckboxBinding
import com.discord.databinding.BotUikitModalLabelBinding
import com.discord.databinding.BotUikitModalSelectBinding
import com.discord.databinding.BotUikitModalTextDisplayBinding
import com.discord.databinding.WidgetBotUiTextDisplayComponentBinding
import com.discord.databinding.WidgetBotUiThumbnailComponentBinding
import com.discord.models.botuikit.ThumbnailMessageComponent
import com.discord.player.MediaSource
import com.discord.player.MediaType
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.widgets.botuikit.views.v2.ThumbnailComponentView
import com.discord.widgets.media.WidgetMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsV2ViewSmokeTest {
    @Test
    fun currentModalAndMessageComponentsInflateAndMeasure() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.AppTheme_Dark,
            )
            val parent = FrameLayout(context)
            val inflater = LayoutInflater.from(context)

            BotUikitModalCheckboxBinding.inflate(inflater, parent, false)
            BotUikitModalLabelBinding.inflate(inflater, parent, false)
            BotUikitModalSelectBinding.inflate(inflater, parent, false)
            BotUikitModalTextDisplayBinding.inflate(inflater, parent, false)

            listOf(
                ComponentType.SECTION,
                ComponentType.TEXT_DISPLAY,
                ComponentType.THUMBNAIL,
                ComponentType.MEDIA_GALLERY,
                ComponentType.FILE,
                ComponentType.SEPARATOR,
                ComponentType.CONTAINER,
            ).forEach { type ->
                val view = ComponentInflater.from(context).inflateComponent(type, parent)
                assertNotNull(type.name, view)
                (view as View).measure(
                    View.MeasureSpec.makeMeasureSpec(dp(context, 360), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                if (type == ComponentType.TEXT_DISPLAY) {
                    val text = WidgetBotUiTextDisplayComponentBinding.bind(view).content
                    val chatText = TextView(context, null, 0, R.style.UiKit_Chat_Text)
                    assertEquals(chatText.textSize, text.textSize, 0.01f)
                    assertEquals(chatText.lineSpacingMultiplier, text.lineSpacingMultiplier, 0.01f)
                }
            }
        }
    }

    @Test
    fun linksKeepTheirColorAndTargetInsideSubtextAndHeadings() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.AppTheme_Dark)
            var openedUrl: String? = null
            val renderContext = MessageRenderContext(context, 0, false,
                onClickUrl = { _, url, _ -> openedUrl = url })
            for ((prefix, textColor) in listOf("-#" to R.attr.colorTextMuted, "##" to R.attr.colorHeaderPrimary)) {
                val text = DiscordParser.parseChannelMessage(
                    context, "$prefix [@sample](https://example.com/profile) supporting text", renderContext,
                    MessagePreprocessor(myUserId = 0, messageState = null),
                )
                fun colorAt(index: Int): Int {
                    val paint = TextPaint()
                    text.getSpans(index, index + 1, CharacterStyle::class.java).forEach { it.updateDrawState(paint) }
                    return paint.color
                }
                val linkIndex = text.indexOf("@sample")
                assertEquals(ColorCompat.getThemedColor(context, R.attr.colorTextLink), colorAt(linkIndex))
                assertEquals(ColorCompat.getThemedColor(context, textColor), colorAt(text.indexOf("supporting")))
                text.getSpans(linkIndex, linkIndex + 1, ClickableSpan::class.java).single().onClick(TextView(context))
                assertEquals("https://example.com/profile", openedUrl)
            }
        }
    }

    @Test
    fun mediaTilesPlayTypedVideosAndResetForImagesAndSpoilers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var launched: Intent? = null
            val context = object : ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark) {
                override fun startActivity(intent: Intent) { launched = intent }
            }
            val view = ThumbnailComponentView.inflate(context, FrameLayout(context))
            val binding = WidgetBotUiThumbnailComponentBinding.bind(view)
            val media = UnfurledMediaItem("content://component-fixture/stream", null, 90, 160, "video/mp4", null)
            val video = ThumbnailMessageComponent(ComponentType.THUMBNAIL, 0, 1, media, "Sample video", false)
            view.configureMedia(video, 1, null, null, null)
            assertEquals(View.VISIBLE, binding.play.visibility)
            assertTrue(binding.image.contentDescription.contains(context.getString(R.string.play_full_video)))
            binding.image.performClick()
            assertEquals(WidgetMedia::class.java, AppScreenRouter.screenOf(launched))
            @Suppress("DEPRECATION")
            val source = launched!!.getParcelableExtra<MediaSource>("INTENT_MEDIA_SOURCE")!!
            assertEquals(MediaType.VIDEO, source.mediaType)
            assertEquals(media.url, source.progressiveMediaUri.toString())
            assertTrue(launched!!.getStringExtra("INTENT_IMAGE_URL")!!.contains("format=webp"))

            launched = null
            view.configureMedia(video.copy(spoiler = true), 1, null, null, null)
            assertEquals(View.GONE, binding.play.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, binding.image.importantForAccessibility)
            view.performClick()
            assertNull(launched)
            assertEquals(View.GONE, binding.mediaSpoiler.visibility)
            assertEquals(View.VISIBLE, binding.play.visibility)
            view.performClick()
            assertEquals(WidgetMedia::class.java, AppScreenRouter.screenOf(launched))

            launched = null
            val image = video.copy(media = media.copy(contentType = "image/png"), description = null)
            view.configureMedia(image, 1, null, null, null)
            assertEquals(View.GONE, binding.play.visibility)
            assertEquals(View.GONE, binding.alt.visibility)
            binding.image.performClick()
            @Suppress("DEPRECATION")
            assertNull(launched!!.getParcelableExtra<MediaSource>("INTENT_MEDIA_SOURCE"))
            assertEquals(media.url, launched!!.getStringExtra("INTENT_IMAGE_URL"))
        }
    }

    private fun dp(context: android.content.Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
