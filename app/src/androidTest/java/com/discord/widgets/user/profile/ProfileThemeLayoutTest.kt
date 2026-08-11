package com.discord.widgets.user.profile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.api.user.UserProfile
import com.discord.databinding.WidgetUserSheetBinding
import com.discord.widgets.user.presence.ViewHolderUserRichPresence
import com.discord.widgets.user.usersheet.applyUserSheetNoteTheme
import com.discord.widgets.user.usersheet.UserSheetNoteTheme
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.MaterialShapeDrawable
import com.linecorp.apng.ApngDrawable
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileThemeLayoutTest {
    @Test
    fun profileEffectOverlaysContentButStaysBehindLoadingAndIsRounded() {
        val context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.AppTheme_Dark,
        )
        val root = LayoutInflater.from(context).inflate(R.layout.widget_user_sheet, null)
        val content = root.findViewById<android.view.View>(R.id.user_sheet_content)
        val stack = content.parent as FrameLayout
        val effect = root.findViewById<ProfileEffectView>(R.id.user_sheet_profile_effect)
        val loading = root.findViewById<android.view.View>(R.id.user_sheet_loading_container)

        assertTrue(stack.indexOfChild(content) < stack.indexOfChild(effect))
        assertTrue(stack.indexOfChild(effect) < stack.indexOfChild(loading))
        assertTrue(effect.clipToOutline)
        assertTrue(root.findViewById<android.view.View>(R.id.user_sheet_connections_card) != null)
        assertTrue(root.findViewById<android.view.View>(R.id.user_sheet_note_card) != null)
    }

    @Test
    fun profileEffectControllerAdvancesRealApngFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
        val drawable = ApngDrawable.decode(
            ByteArrayInputStream(Base64.decode(TWO_FRAME_APNG, Base64.DEFAULT)),
        )
        val view = ProfileEffectView(context)
        val layer = UserProfile.ProfileEffectLayer(
            src = "fixture",
            loop = true,
            duration = 500,
            loopDelay = 50,
        )
        val effect = UserProfile.ProfileEffect(
            skuId = 1,
            product = UserProfile.ProfileEffectProduct(
                listOf(
                    UserProfile.ProfileEffectItem(
                        skuId = 1,
                        type = UserProfile.ProfileEffectItem.TYPE,
                        animationType = 2,
                    ),
                ),
            ),
        )

        instrumentation.runOnMainSync {
            view.bind(effect, reducedMotion = false)
            view.playDecodedLayers(listOf(layer to drawable), introDelay = 0)
        }
        instrumentation.waitForIdleSync()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var firstFrame = -1
        instrumentation.runOnMainSync {
            drawable.draw(canvas)
            firstFrame = drawable.currentFrameIndex
        }
        SystemClock.sleep(125)
        instrumentation.runOnMainSync { drawable.draw(canvas) }

        assertEquals(2, drawable.frameCount)
        assertNotEquals(firstFrame, drawable.currentFrameIndex)
        instrumentation.runOnMainSync { view.bind(effect, reducedMotion = false) }
        assertTrue(drawable.isRunning)
        assertEquals(View.VISIBLE, view.getChildAt(0).visibility)
        instrumentation.runOnMainSync(view::clear)
        bitmap.recycle()
    }

    @Test
    fun profileEffectDoesNotReplayAndNewPopoutKeepsBoundsAndImageMatrix() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
        val firstDrawable = ApngDrawable.decode(
            ByteArrayInputStream(Base64.decode(TWO_FRAME_APNG, Base64.DEFAULT)),
        )
        val firstView = ProfileEffectView(context)
        val layer = UserProfile.ProfileEffectLayer(
            src = "fixture",
            loop = true,
            duration = 60,
            loopDelay = 40,
        )
        val effect = UserProfile.ProfileEffect(
            skuId = 1,
            product = UserProfile.ProfileEffectProduct(
                listOf(
                    UserProfile.ProfileEffectItem(
                        skuId = 1,
                        type = UserProfile.ProfileEffectItem.TYPE,
                        animationType = 2,
                    ),
                ),
            ),
        )

        instrumentation.runOnMainSync {
            firstView.bind(effect, reducedMotion = false)
            firstView.playDecodedLayers(listOf(layer to firstDrawable), introDelay = 0)
            val width = View.MeasureSpec.makeMeasureSpec(304, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            firstView.measure(width, height)
            firstView.layout(0, 0, 304, 400)
        }
        instrumentation.waitForIdleSync()
        val firstImage = firstView.getChildAt(0) as ImageView
        val firstBounds = Rect(firstImage.drawable.bounds)
        val firstMatrix = Matrix(firstImage.imageMatrix).values()

        SystemClock.sleep(110)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { firstView.bind(effect, reducedMotion = false) }
        assertEquals(0, firstView.childCount)
        assertTrue(!firstDrawable.isRunning)

        val secondDrawable = ApngDrawable.decode(
            ByteArrayInputStream(Base64.decode(TWO_FRAME_APNG, Base64.DEFAULT)),
        )
        val secondView = ProfileEffectView(context)
        instrumentation.runOnMainSync {
            secondView.bind(effect, reducedMotion = false)
            secondView.playDecodedLayers(listOf(layer to secondDrawable), introDelay = 0)
            val width = View.MeasureSpec.makeMeasureSpec(304, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            secondView.measure(width, height)
            secondView.layout(0, 0, 304, 400)
        }
        instrumentation.waitForIdleSync()
        val secondImage = secondView.getChildAt(0) as ImageView

        assertNotSame(firstDrawable, secondDrawable)
        assertEquals(firstBounds, secondDrawable.bounds)
        assertArrayEquals(firstMatrix, Matrix(secondImage.imageMatrix).values(), 0f)
        instrumentation.runOnMainSync(secondView::clear)
    }

    @Test
    fun noteAndActivityViewsApplyAndResetProfileTheme() {
        val context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.AppTheme_Dark,
        )
        val colors = ProfileThemeColors.from(0xFF005A55.toInt(), 0xFF164C4C.toInt(), true)
        val noteSurface = ColorUtils.compositeColors(colors.noteOverlay, colors.bodySurface)
        val binding = WidgetUserSheetBinding.bind(
            LayoutInflater.from(context).inflate(R.layout.widget_user_sheet, null),
        )
        val noteCard = binding.userSheetNoteCard
        val defaultNoteCard = noteCard.cardBackgroundColor.defaultColor
        val defaultNoteCardStroke = (noteCard as? MaterialCardView)?.strokeColor ?: Color.TRANSPARENT
        val defaultNoteBox = binding.userSheetNoteTextFieldWrap.boxBackgroundColor
        val defaultNoteBoxStroke = binding.userSheetNoteTextFieldWrap.boxStrokeColor
        val defaultNoteHeader = binding.userSheetNoteHeader.currentTextColor
        val defaultNoteText = binding.userSheetNoteTextField.currentTextColor
        val defaultNoteTheme = UserSheetNoteTheme.from(binding)
        val defaultEditTextBackground = binding.userSheetNoteTextField.background
        val defaultEditTextBackgroundTint =
            ViewCompat.getBackgroundTintList(binding.userSheetNoteTextField)
        assertTrue(binding.userSheetNoteTextField.text.isNullOrEmpty())
        assertEquals(
            context.getString(R.string.note_placeholder_mobile),
            binding.userSheetNoteTextFieldWrap.hint,
        )
        assertTrue(binding.userSheetNoteTextField.background is MaterialShapeDrawable)

        applyUserSheetNoteTheme(
            binding,
            colors,
            defaultNoteTheme,
        )
        assertEquals(noteSurface, noteCard.cardBackgroundColor.defaultColor)
        assertEquals(colors.borderOverlay, noteCard.strokeColor)
        assertEquals(noteSurface, binding.userSheetNoteTextFieldWrap.boxBackgroundColor)
        assertEquals(colors.borderOverlay, binding.userSheetNoteTextFieldWrap.boxStrokeColor)
        assertEquals(colors.textMuted, binding.userSheetNoteHeader.currentTextColor)
        assertEquals(colors.textDefault, binding.userSheetNoteTextField.currentTextColor)
        assertEquals(
            colors.textMuted,
            binding.userSheetNoteTextFieldWrap.defaultHintTextColor?.defaultColor,
        )
        assertEquals(
            colors.textMuted,
            binding.userSheetNoteTextFieldWrap.counterTextColor?.defaultColor,
        )
        assertSame(defaultEditTextBackground, binding.userSheetNoteTextField.background)
        assertEquals(
            defaultEditTextBackgroundTint,
            ViewCompat.getBackgroundTintList(binding.userSheetNoteTextField),
        )

        binding.userSheetNoteTextField.clearFocus()
        binding.userSheetNoteTextFieldWrap.refreshDrawableState()
        assertEquals(noteSurface, binding.userSheetNoteTextFieldWrap.boxBackgroundColor)
        assertEquals(
            noteSurface,
            (binding.userSheetNoteTextField.background as MaterialShapeDrawable)
                .fillColor?.getColorForState(binding.userSheetNoteTextField.drawableState, 0),
        )
        assertTrue(binding.userSheetNoteTextField.requestFocus())
        binding.userSheetNoteTextFieldWrap.refreshDrawableState()
        assertEquals(noteSurface, binding.userSheetNoteTextFieldWrap.boxBackgroundColor)
        assertEquals(
            noteSurface,
            (binding.userSheetNoteTextField.background as MaterialShapeDrawable)
                .fillColor?.getColorForState(binding.userSheetNoteTextField.drawableState, 0),
        )
        binding.userSheetNoteTextFieldWrap.isEnabled = false
        binding.userSheetNoteTextFieldWrap.refreshDrawableState()
        assertEquals(noteSurface, binding.userSheetNoteTextFieldWrap.boxBackgroundColor)
        assertEquals(
            noteSurface,
            (binding.userSheetNoteTextField.background as MaterialShapeDrawable)
                .fillColor?.getColorForState(binding.userSheetNoteTextField.drawableState, 0),
        )
        binding.userSheetNoteTextFieldWrap.isEnabled = true

        applyUserSheetNoteTheme(
            binding,
            null,
            defaultNoteTheme,
        )
        assertEquals(defaultNoteCard, noteCard.cardBackgroundColor.defaultColor)
        assertEquals(defaultNoteCardStroke, noteCard.strokeColor)
        assertEquals(defaultNoteBox, binding.userSheetNoteTextFieldWrap.boxBackgroundColor)
        assertEquals(defaultNoteBoxStroke, binding.userSheetNoteTextFieldWrap.boxStrokeColor)
        assertEquals(defaultNoteHeader, binding.userSheetNoteHeader.currentTextColor)
        assertEquals(defaultNoteText, binding.userSheetNoteTextField.currentTextColor)
        assertEquals(defaultNoteTheme.inputHintTextColor, binding.userSheetNoteTextField.hintTextColors)
        assertEquals(
            defaultNoteTheme.layoutHintTextColor,
            binding.userSheetNoteTextFieldWrap.defaultHintTextColor,
        )
        assertEquals(
            defaultNoteTheme.counterTextColor,
            binding.userSheetNoteTextFieldWrap.counterTextColor,
        )
        assertSame(defaultEditTextBackground, binding.userSheetNoteTextField.background)
        assertEquals(
            defaultEditTextBackgroundTint,
            ViewCompat.getBackgroundTintList(binding.userSheetNoteTextField),
        )
        binding.userSheetNoteTextField.clearFocus()
        binding.userSheetNoteTextFieldWrap.refreshDrawableState()
        assertEquals(defaultNoteBox, binding.userSheetNoteTextFieldWrap.boxBackgroundColor)
        assertEquals(
            defaultNoteBox,
            (binding.userSheetNoteTextField.background as MaterialShapeDrawable)
                .fillColor?.getColorForState(binding.userSheetNoteTextField.drawableState, 0),
        )
        assertTrue(binding.userSheetNoteTextField.requestFocus())
        binding.userSheetNoteTextFieldWrap.refreshDrawableState()
        assertEquals(defaultNoteBox, binding.userSheetNoteTextFieldWrap.boxBackgroundColor)
        assertEquals(
            defaultNoteBox,
            (binding.userSheetNoteTextField.background as MaterialShapeDrawable)
                .fillColor?.getColorForState(binding.userSheetNoteTextField.drawableState, 0),
        )

        val activityRoot = LayoutInflater.from(context)
            .inflate(R.layout.widget_user_rich_presence, null)
        val activityCard = activityRoot.findViewById<MaterialCardView>(R.id.rich_presence_card)
        val activityHeader = activityRoot.findViewById<TextView>(R.id.rich_presence_header)
        val activityTitle = activityRoot.findViewById<TextView>(R.id.rich_presence_title)
        val activityDetails = activityRoot.findViewById<TextView>(R.id.rich_presence_details)
        val activityState = activityRoot.findViewById<TextView>(R.id.rich_presence_state)
        val activityTime = activityRoot.findViewById<TextView>(R.id.rich_presence_time)
        val primaryButton = activityRoot.findViewById<Button>(R.id.rich_presence_primary_button)
        val secondaryButton = activityRoot.findViewById<Button>(R.id.rich_presence_secondary_button)
        val holder = ViewHolderUserRichPresence(
            activityRoot,
            activityRoot.findViewById<SimpleDraweeView>(R.id.rich_presence_image_large),
            activityRoot.findViewById(R.id.rich_presence_image_small),
            activityHeader,
            activityTitle,
            activityDetails,
            activityTime,
            activityState,
            activityRoot.findViewById(R.id.rich_presence_container_data),
            primaryButton,
            secondaryButton,
        )
        val defaultActivityCard = activityCard.cardBackgroundColor.defaultColor
        val defaultActivityStroke = activityCard.strokeColor
        val defaultActivityHeader = activityHeader.currentTextColor
        val defaultActivityTitle = activityTitle.currentTextColor
        val defaultActivityDetails = activityDetails.currentTextColor
        val defaultPrimaryButton = primaryButton.backgroundTintList

        holder.applyProfileTheme(colors)
        assertEquals(colors.sectionOverlay, activityCard.cardBackgroundColor.defaultColor)
        assertEquals(colors.borderOverlay, activityCard.strokeColor)
        assertEquals(colors.textMuted, activityHeader.currentTextColor)
        assertEquals(colors.textDefault, activityTitle.currentTextColor)
        assertEquals(colors.textSubtle, activityDetails.currentTextColor)
        assertEquals(colors.textSubtle, activityState.currentTextColor)
        assertEquals(colors.textSubtle, activityTime.currentTextColor)
        assertEquals(colors.button, primaryButton.backgroundTintList?.defaultColor)
        assertEquals(colors.button, secondaryButton.backgroundTintList?.defaultColor)

        holder.applyProfileTheme(null)
        assertEquals(defaultActivityCard, activityCard.cardBackgroundColor.defaultColor)
        assertEquals(defaultActivityStroke, activityCard.strokeColor)
        assertEquals(defaultActivityHeader, activityHeader.currentTextColor)
        assertEquals(defaultActivityTitle, activityTitle.currentTextColor)
        assertEquals(defaultActivityDetails, activityDetails.currentTextColor)
        assertEquals(defaultPrimaryButton, primaryButton.backgroundTintList)
    }

    @Test
    fun everyRichPresenceLayoutExposesThemeTargets() {
        val context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.AppTheme_Dark,
        )
        listOf(
            R.layout.widget_user_rich_presence,
            R.layout.widget_music_rich_presence,
            R.layout.widget_platform_rich_presence,
            R.layout.widget_stream_rich_presence,
            R.layout.widget_stage_channel_rich_presence,
        ).forEach { layout ->
            val root = LayoutInflater.from(context).inflate(layout, null)
            assertTrue(root.findViewById<View>(R.id.rich_presence_card) is MaterialCardView)
            assertTrue(root.findViewById<View>(R.id.rich_presence_header) is TextView)
            assertTrue(root.findViewById<View>(R.id.rich_presence_title) is TextView)
            assertTrue(root.findViewById<View>(R.id.rich_presence_time) is TextView)
        }
    }

    private companion object {
        fun Matrix.values() = FloatArray(9).also(::getValues)

        const val TWO_FRAME_APNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACGFjVEwAAAACAAAAAPONk3AAAAAa" +
                "ZmNUTAAAAAAAAAABAAAAAQAAAAAAAAAAAAEACgAAWn8w0AAAAA1JREFUeJxj+M/A8B8ABQAB/4mZ" +
                "PR0AAAAaZmNUTAAAAAEAAAABAAAAAQAAAAAAAAAAAAEACgAAwQzaBAAAABFmZEFUAAAAAnicY2Bg" +
                "+P8fAAMCAf/1e6XXAAAAAElFTkSuQmCC"
    }
}
