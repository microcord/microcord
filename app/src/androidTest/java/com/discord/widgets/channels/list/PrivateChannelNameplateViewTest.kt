package com.discord.widgets.channels.list

import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.api.user.Nameplate
import com.discord.views.NameplateView
import com.discord.views.VitoImageView
import com.linecorp.apng.ApngDrawable
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateChannelNameplateViewTest {
    @Test
    fun selectedChannelAnimationStopsAndRejectsStaleRebinds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.AppTheme_Dark,
            )
            val content = View(context)
            val view = NameplateView(context)
            val firstPlate = Nameplate("asset", 9, "plate", "sky")
            val secondPlate = firstPlate.copy(skuId = 10)

            view.bind(firstPlate, NameplateView.Placement.CHANNEL, selected = true)
            val firstRequest = requireNotNull(view.currentAnimationRequest())
            val firstDrawable = drawable()
            assertTrue(view.bindDecodedAnimation(firstDrawable, firstRequest))
            assertTrue(firstDrawable.isRunning)
            assertEquals(View.VISIBLE, view.getChildAt(0).visibility)
            assertEquals(View.VISIBLE, (view.getChildAt(1) as ImageView).visibility)

            view.bind(firstPlate, NameplateView.Placement.CHANNEL, selected = false)
            assertTrue(firstDrawable.isRecycled)
            assertEquals(View.VISIBLE, view.getChildAt(0).visibility)
            assertEquals(View.INVISIBLE, view.getChildAt(1).visibility)
            assertNull(view.currentAnimationRequest())
            view.setNameplateHovered(true)
            assertNull(view.currentAnimationRequest())

            view.bind(firstPlate, NameplateView.Placement.CHANNEL, selected = true)
            val staleRequest = requireNotNull(view.currentAnimationRequest())
            view.bind(secondPlate, NameplateView.Placement.CHANNEL, selected = true)
            val replacementRequest = requireNotNull(view.currentAnimationRequest())
            val staleDrawable = drawable()
            assertFalse(view.bindDecodedAnimation(staleDrawable, staleRequest))
            assertTrue(staleDrawable.isRecycled)

            val replacementDrawable = drawable()
            assertTrue(view.bindDecodedAnimation(replacementDrawable, replacementRequest))
            assertTrue(replacementDrawable.isRunning)
            assertEquals(ApngDrawable.LOOP_FOREVER, replacementDrawable.loopCount)

            view.bind(secondPlate, NameplateView.Placement.MEMBER_LIST, selected = true)
            assertTrue(replacementDrawable.isRecycled)
            assertNull(view.currentAnimationRequest())
            assertEquals(View.INVISIBLE, view.getChildAt(1).visibility)
        }
    }

    @Test
    fun nameplateMatchesMemberRendererWithoutChangingPrivateRowHeight() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listOf(1f, 1.5f).forEach(::assertEqualHeight)
        }
    }

    private fun assertEqualHeight(fontScale: Float) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val configuredContext = targetContext.createConfigurationContext(
            Configuration(targetContext.resources.configuration).apply { this.fontScale = fontScale },
        )
        val context = ContextThemeWrapper(configuredContext, R.style.AppTheme_Dark)
        val parent = FrameLayout(context)
        val root = LayoutInflater.from(context).inflate(
            R.layout.widget_channels_list_item_channel_private,
            parent,
            false,
        ) as RelativeLayout
        val nameplate = root.findViewById<NameplateView>(R.id.channels_list_item_private_nameplate)
        val content = root.findViewById<View>(R.id.channels_list_item_private_content)
        val image = nameplate.getChildAt(0) as VitoImageView
        val width = View.MeasureSpec.makeMeasureSpec(
            (360 * context.resources.displayMetrics.density).toInt(),
            View.MeasureSpec.EXACTLY,
        )
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        nameplate.visibility = View.GONE
        root.measure(width, height)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val undecoratedHeight = root.height

        nameplate.bind(
            Nameplate("asset", 9, "plate", "sky"),
            NameplateView.Placement.CHANNEL,
            selected = true,
        )
        root.measure(width, height)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        assertEquals(View.VISIBLE, nameplate.visibility)
        assertTrue(nameplate.isSelected)
        assertTrue(root.indexOfChild(nameplate) < root.indexOfChild(content))
        assertEquals(undecoratedHeight, root.height)
        assertEquals((8 * context.resources.displayMetrics.density + 0.5f).toInt(), root.paddingTop)
        assertFalse(root.clipToPadding)
        assertEquals((4 * context.resources.displayMetrics.density + 0.5f).toInt(), (root.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
        assertEquals(0, nameplate.layoutParams.width)
        assertEquals(0, nameplate.layoutParams.height)
        assertTrue(nameplate.width > 0)
        assertTrue(nameplate.height > 0)
        assertEquals(0, nameplate.left)
        assertEquals(0, nameplate.top)
        assertEquals(root.width, nameplate.right)
        assertEquals(root.height, nameplate.bottom)
        val source = RectF(0f, 0f, 448f, 84f)
        val bounds = Rect(0, 0, 360, 64)
        requireNotNull(image.hierarchy.actualImageScaleType)
            .getTransform(Matrix(), bounds, 448, 84, 0f, 0f)
            .mapRect(source)
        assertEquals(RectF(360f - 448f * 64f / 84f, 0f, 360f, 64f), source)
        assertTrue(source.left >= bounds.left && source.right <= bounds.right)
        assertTrue(source.top >= bounds.top && source.bottom <= bounds.bottom)

        val memberRoot = LayoutInflater.from(context).inflate(
            R.layout.widget_channel_members_list_item_user,
            parent,
            false,
        ) as ViewGroup
        val memberNameplate = memberRoot.findViewById<NameplateView>(R.id.channel_members_list_item_nameplate)
        val memberContentEnd = memberRoot.findViewById<View>(R.id.channel_members_list_item_content_end)
        val memberImage = memberNameplate.getChildAt(0) as VitoImageView
        memberRoot.measure(
            width,
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY),
        )
        memberRoot.layout(0, 0, root.width, root.height)
        memberNameplate.bind(
            Nameplate("asset", 9, "plate", "sky"),
            NameplateView.Placement.MEMBER_LIST,
            selected = true,
        )

        // Compare renderer output with equal artwork and content-end bounds; only parent layout differs.
        memberNameplate.layout(0, 0, root.width, root.height)
        memberContentEnd.layout(content.right, 0, content.right, 0)
        val memberDestination = RectF(0f, 0f, 448f, 84f)
        requireNotNull(memberImage.hierarchy.actualImageScaleType)
            .getTransform(Matrix(), bounds, 448, 84, 0f, 0f)
            .mapRect(memberDestination)

        assertEquals(source, memberDestination)
        assertEquals(image.alpha, memberImage.alpha)
        assertEquals(image.hierarchy.actualImageScaleType, memberImage.hierarchy.actualImageScaleType)
        assertTrue(memberRoot.indexOfChild(memberNameplate) < memberRoot.indexOfChild(memberContentEnd))
        assertTrue(memberRoot.clipToPadding)
    }

    private fun drawable() = ApngDrawable.decode(
        ByteArrayInputStream(Base64.decode(TWO_FRAME_APNG, Base64.DEFAULT)),
    )

    private companion object {
        const val TWO_FRAME_APNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACGFjVEwAAAACAAAAAPONk3AAAAAa" +
                "ZmNUTAAAAAAAAAABAAAAAQAAAAAAAAAAAAEACgAAWn8w0AAAAA1JREFUeJxj+M/A8B8ABQAB/4mZ" +
                "PR0AAAAaZmNUTAAAAAEAAAABAAAAAQAAAAAAAAAAAAEACgAAwQzaBAAAABFmZEFUAAAAAnicY2Bg" +
                "+P8fAAMCAf/1e6XXAAAAAElFTkSuQmCC"
    }
}
