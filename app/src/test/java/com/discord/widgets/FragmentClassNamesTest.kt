package com.discord.widgets

import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentClassNamesTest {
    @Test
    fun recoveredXmlFragmentNamesLoad() {
        listOf(
            "com.discord.widgets.status.WidgetChatStatus",
            "com.discord.widgets.status.WidgetThreadStatus",
            "com.discord.widgets.status.WidgetForumPostStatus",
            "com.discord.widgets.chat.overlay.WidgetChatOverlay",
            "com.discord.widgets.voice.call.WidgetVoiceCallInline",
        ).forEach { name ->
            assertTrue(name, androidx.fragment.app.Fragment::class.java.isAssignableFrom(Class.forName(name)))
        }
    }
}
