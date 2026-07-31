package com.discord.widgets.voice.fullscreen

import android.content.Context
import com.discord.app.AppActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WidgetCallFullscreenRouteTest {
    @Test
    fun dedicatedCallHostAndIntentContractMatch12621() {
        assertTrue(AppActivity::class.java.isAssignableFrom(AppActivity.Call::class.java))
        assertEquals("com.discord.intent.extra.EXTRA_CHANNEL_ID", WidgetCallFullscreen.EXTRA_CHANNEL_ID)
        assertEquals("com.discord.intent.extra.EXTRA_STREAM_KEY", WidgetCallFullscreen.EXTRA_STREAM_KEY)
        assertEquals("INTENT_EXTRA_CONNECT_ON_LAUNCH", WidgetCallFullscreen.EXTRA_CONNECT_ON_LAUNCH)
        assertEquals(
            "com.discord.widgets.voice.fullscreen.WidgetCallFullscreen",
            WidgetCallFullscreen::class.java.name,
        )
        assertNotNull(
            WidgetCallFullscreen::class.java.getDeclaredMethod(
                "launch",
                Context::class.java,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ),
        )
    }

    @Test
    fun callActivityIsSingleTaskAndExcludedFromRecents() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val call = (0 until activities.length)
            .map { activities.item(it) }
            .single { it.attributes.getNamedItem("android:name")?.nodeValue == "com.discord.app.AppActivity\$Call" }
        assertEquals("false", call.attributes.getNamedItem("android:exported").nodeValue)
        assertEquals("true", call.attributes.getNamedItem("android:excludeFromRecents").nodeValue)
        assertEquals("singleTask", call.attributes.getNamedItem("android:launchMode").nodeValue)
    }
}
