package com.discord.widgets.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsAuthCodeBroadcastReceiverTest {

    @Test fun `extracts digits only from the message before the app hash`() {
        val receiver = SmsAuthCodeBroadcastReceiver {}
        val method = receiver.javaClass.getDeclaredMethod("extractCode", String::class.java).apply {
            isAccessible = true
        }

        assertEquals("123456", method.invoke(receiver, "Your code is 123 456\n\nAb9Cd8"))
    }
}
