package com.discord.widgets.chat.input

import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.stores.StoreStream
import com.lytefast.flexinput.widget.FlexEditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetChatInputEditTextTest {
    @Test
    fun draftsEmptyStateAndSendKeysMatchComposerBehavior() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
            val editText = FlexEditText(context, null)
            val drafts = MessageDraftsRepo()
            val holder = WidgetChatInputEditText(editText, drafts)
            val emptyStates = mutableListOf<Boolean>()
            val subscription = holder.observeEmptyText().subscribe(emptyStates::add)
            var sends = 0
            holder.onSendListener = { sends++ }

            holder.channelId = 41L
            editText.setText("/draft")
            assertEquals("/draft", holder.getText())
            assertEquals("/draft", drafts.getTextChannelInput(41L))
            assertEquals(listOf(true, false), emptyStates)

            editText.setText("")
            assertEquals(listOf(true, false, true), emptyStates)

            editText.onEditorAction(EditorInfo.IME_ACTION_SEND)
            assertEquals(1, sends)
            editText.onEditorAction(EditorInfo.IME_ACTION_DONE)
            assertEquals(1, sends)

            val settings = StoreStream.getUserSettings()
            val previousShiftBehavior = settings.getIsShiftEnterToSendEnabled()
            settings.setIsShiftEnterToSendEnabled(false)
            try {
                assertTrue(editText.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                assertEquals(1, sends)
                assertTrue(editText.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
                assertEquals(2, sends)

                settings.setIsShiftEnterToSendEnabled(true)
                assertFalse(editText.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
                val shiftEnterDown = KeyEvent(
                    0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON,
                )
                val shiftEnterUp = KeyEvent(
                    0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON,
                )
                assertTrue(editText.dispatchKeyEvent(shiftEnterDown))
                assertTrue(editText.dispatchKeyEvent(shiftEnterUp))
                assertEquals(3, sends)

                val softEnter = KeyEvent(
                    0L,
                    0L,
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_ENTER,
                    0,
                    0,
                    KeyEvent.KEYCODE_UNKNOWN,
                    0,
                    KeyEvent.FLAG_SOFT_KEYBOARD,
                )
                assertFalse(editText.dispatchKeyEvent(softEnter))
                assertEquals(3, sends)
            } finally {
                settings.setIsShiftEnterToSendEnabled(previousShiftBehavior)
                subscription.unsubscribe()
            }
        }
    }
}
