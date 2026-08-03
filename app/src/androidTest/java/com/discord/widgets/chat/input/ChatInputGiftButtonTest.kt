package com.discord.widgets.chat.input

import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.app.AppActivity
import com.discord.app.AppScreenRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatInputGiftButtonTest {
    @Test
    fun formerGiftSlotOpensApplicationDirectory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            AppScreenRouter.createScreenIntent(instrumentation.targetContext, WidgetChatInput::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity

        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                activity.supportFragmentManager.executePendingTransactions()
                val chatInput = activity.supportFragmentManager.findFragmentById(R.id.app_root) as WidgetChatInput
                chatInput.childFragmentManager.executePendingTransactions()
                val flexInput = chatInput.childFragmentManager.findFragmentById(R.id.chat_input_widget)
                    as AppFlexInputFragment
                val appsButton = flexInput.requireView().findViewById<View>(R.id.gift_btn)
                val addButton = flexInput.requireView().findViewById<View>(R.id.add_btn)

                assertEquals(View.VISIBLE, appsButton.visibility)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, appsButton.importantForAccessibility)
                assertTrue(appsButton.isClickable)
                assertTrue(appsButton.isFocusable)
                assertTrue(appsButton.isEnabled)
                assertTrue(appsButton.hasOnClickListeners())
                assertEquals(activity.getString(R.string.apps), appsButton.contentDescription)
                assertEquals(addButton.width, appsButton.width)
                assertEquals(addButton.height, appsButton.height)
                assertEquals(addButton.paddingLeft, appsButton.paddingLeft)
                assertEquals(addButton.paddingTop, appsButton.paddingTop)
                assertEquals(addButton.background.javaClass, appsButton.background.javaClass)

            }
        } finally {
            instrumentation.runOnMainSync(activity::finish)
        }
    }
}
