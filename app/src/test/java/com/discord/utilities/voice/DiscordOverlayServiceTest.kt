package com.discord.utilities.voice

import com.discord.rtcconnection.RtcConnection
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DiscordOverlayServiceTest {
    @Test
    fun `non reconnecting disconnect selects voice and every other state opens overlay`() {
        assertEquals(
            DiscordOverlayService.ACTION_SELECTOR,
            DiscordOverlayService.actionForConnectionState(change(RtcConnection.State.Disconnected(false))),
        )
        assertEquals(
            DiscordOverlayService.ACTION_OPEN,
            DiscordOverlayService.actionForConnectionState(change(RtcConnection.State.Disconnected(true))),
        )
        assertEquals(
            DiscordOverlayService.ACTION_OPEN,
            DiscordOverlayService.actionForConnectionState(change(RtcConnection.State.RtcConnected)),
        )
    }

    @Test
    fun `manifest registers concrete private special use service`() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val service = (0 until document.getElementsByTagName("service").length)
            .map(document.getElementsByTagName("service")::item)
            .single { it.attributes.getNamedItemNS(ANDROID_NS, "name")?.nodeValue == SERVICE_NAME }

        assertEquals("false", service.attributes.getNamedItemNS(ANDROID_NS, "exported").nodeValue)
        assertEquals("specialUse", service.attributes.getNamedItemNS(ANDROID_NS, "foregroundServiceType").nodeValue)
        val specialUseProperty = (0 until service.childNodes.length)
            .map(service.childNodes::item)
            .singleOrNull {
                it.nodeName == "property" &&
                    it.attributes.getNamedItemNS(ANDROID_NS, "name")?.nodeValue == SPECIAL_USE_PROPERTY
            }
        assertNotNull(specialUseProperty)
        assertEquals(
            "Displays user-enabled voice call controls over other apps",
            specialUseProperty?.attributes?.getNamedItemNS(ANDROID_NS, "value")?.nodeValue,
        )
    }

    private fun change(state: RtcConnection.State) = RtcConnection.StateChange(state, null)

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val SERVICE_NAME = "com.discord.utilities.voice.DiscordOverlayService"
        const val SPECIAL_USE_PROPERTY = "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    }
}
