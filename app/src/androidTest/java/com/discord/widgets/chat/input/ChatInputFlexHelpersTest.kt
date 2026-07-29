package com.discord.widgets.chat.input

import android.net.Uri
import android.os.Parcel
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.app.AppFragment
import com.lytefast.flexinput.widget.FlexEditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatInputFlexHelpersTest {
    @Test
    fun truncatedHintOnlyLimitsAnEmptyComposer() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editText = FlexEditText(themedContext()).apply { maxLines = 6 }
            val hint = WidgetChatInputTruncatedHint(editText)
            hint.addBindedTextWatcher(object : AppFragment() {})
            hint.setHint("Message #a-very-long-channel-name")

            assertEquals(1, editText.maxLines)
            assertEquals(TextUtils.TruncateAt.END, editText.ellipsize)

            editText.setText("hello")
            assertEquals(6, editText.maxLines)
            assertNull(editText.ellipsize)
            assertNull(editText.hint)

            editText.maxLines = 4
            editText.layout(0, 0, 100, 100)
            editText.setText("")
            assertEquals(1, editText.maxLines)
            assertEquals("Message #a-very-long-channel-name", editText.hint)

            editText.setText("again")
            assertEquals(4, editText.maxLines)
        }
    }

    @Test
    fun sourcedAttachmentSurvivesFlexInputStateRestoration() {
        val parcel = Parcel.obtain()
        try {
            val original = SourcedAttachment<Any>(
                id = 42L,
                uri = Uri.parse("content://manticord/image"),
                displayName = "image.png",
                source = "keyboard",
                spoiler = true,
            )
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)

            val restored = SourcedAttachment.CREATOR.createFromParcel(parcel)
            assertEquals(original.id, restored.id)
            assertEquals(original.uri, restored.uri)
            assertEquals(original.displayName, restored.displayName)
            assertEquals("keyboard", restored.source)
            assertTrue(restored.spoiler)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun smoothKeyboardReactionInstallsWhenAvailable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = View(themedContext())
            SmoothKeyboardReactionHelper.install(view)
            assertNotNull(view)
        }
    }

    private fun themedContext() = ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
        R.style.AppTheme_Dark,
    )
}
