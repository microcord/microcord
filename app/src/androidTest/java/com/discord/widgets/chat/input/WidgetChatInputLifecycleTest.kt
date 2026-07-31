package com.discord.widgets.chat.input

import android.content.Intent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.app.AppActivity
import com.discord.app.AppScreenRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetChatInputLifecycleTest {
    @Test
    fun firstEmojiTapOpensTrayAfterPauseAndResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            AppScreenRouter.createScreenIntent(instrumentation.targetContext, WidgetChatInput::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity

        try {
            instrumentation.waitForIdleSync()
            lateinit var chatInput: WidgetChatInput
            lateinit var emojiButton: View
            lateinit var emojiContainer: View
            lateinit var viewModel: AppFlexInputViewModel

            instrumentation.runOnMainSync {
                activity.supportFragmentManager.executePendingTransactions()
                chatInput = activity.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetChatInput
                chatInput.childFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val flexInput = chatInput.childFragmentManager.findFragmentById(R.id.chat_input_widget)
                    as AppFlexInputFragment
                emojiButton = flexInput.requireView().findViewById(R.id.emoji_btn)
                emojiContainer = flexInput.requireView().findViewById(R.id.emoji_container)
                viewModel = ViewModelProvider(activity).get(AppFlexInputViewModel::class.java)
            }

            instrumentation.runOnMainSync { emojiButton.performClick() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(View.VISIBLE, emojiContainer.visibility)
            }
            instrumentation.runOnMainSync { emojiButton.performClick() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(View.GONE, emojiContainer.visibility)
            }
            instrumentation.runOnMainSync {
                activity.supportFragmentManager.beginTransaction()
                    .setMaxLifecycle(chatInput, Lifecycle.State.STARTED)
                    .commitNow()
                activity.supportFragmentManager.beginTransaction()
                    .setMaxLifecycle(chatInput, Lifecycle.State.RESUMED)
                    .commitNow()
            }
            instrumentation.waitForIdleSync()

            instrumentation.runOnMainSync {
                assertFalse(viewModel.requireViewState().expressionTrayShown)
            }
            instrumentation.runOnMainSync { emojiButton.performClick() }
            instrumentation.waitForIdleSync()

            instrumentation.runOnMainSync {
                assertTrue(viewModel.requireViewState().expressionTrayShown)
                assertEquals(View.VISIBLE, emojiContainer.visibility)
            }
        } finally {
            instrumentation.runOnMainSync(activity::finish)
        }
    }
}
