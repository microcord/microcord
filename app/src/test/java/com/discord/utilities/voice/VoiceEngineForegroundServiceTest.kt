package com.discord.utilities.voice

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class VoiceEngineForegroundServiceTest {
    @Test
    fun exactActionsBindingAndManifestContract() {
        assertEquals(
            "com.discord.utilities.voice.action.start_foreground",
            VoiceEngineForegroundService.ACTION.START_FOREGROUND,
        )
        assertEquals(
            "com.discord.utilities.voice.action.stage_invite_accept",
            VoiceEngineForegroundService.ACTION.STAGE_INVITE_ACCEPT,
        )
        assertEquals(
            "com.discord.utilities.voice.action.stage_invite_decline",
            VoiceEngineForegroundService.ACTION.STAGE_INVITE_DECLINE,
        )
        assertEquals(
            "com.discord.utilities.voice.action.toggle_muted",
            VoiceEngineForegroundService.ACTION.TOGGLE_MUTED,
        )
        assertEquals(
            "com.discord.utilities.voice.action.toggle_deafened",
            VoiceEngineForegroundService.ACTION.TOGGLE_DEAFENED,
        )
        assertNotNull(VoiceEngineForegroundService.Connection::class.java.getDeclaredField("connection"))
        assertNotNull(VoiceEngineForegroundService.Connection::class.java.getDeclaredField("service"))
        assertNotNull(VoiceEngineForegroundService.Connection::class.java.getDeclaredField("isUnbinding"))
        assertNotNull(VoiceEngineForegroundService.Connection::class.java.getDeclaredField("isBound"))

        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val services = document.getElementsByTagName("service")
        val service = (0 until services.length)
            .map(services::item)
            .single {
                it.attributes.getNamedItemNS(ANDROID_NS, "name")?.nodeValue ==
                    "com.discord.utilities.voice.VoiceEngineForegroundService"
            }
        assertEquals("false", service.attributes.getNamedItemNS(ANDROID_NS, "exported").nodeValue)
        assertEquals(
            "camera|mediaProjection|microphone|specialUse",
            service.attributes.getNamedItemNS(ANDROID_NS, "foregroundServiceType").nodeValue,
        )
        assertEquals(
            "Maintains an ongoing receive-only voice or stage connection",
            (service as Element).getElementsByTagName("property").item(0).attributes
                .getNamedItemNS(ANDROID_NS, "value").nodeValue,
        )

        val permissions = document.getElementsByTagName("uses-permission")
        val permissionNames = (0 until permissions.length)
            .mapNotNull { permissions.item(it).attributes.getNamedItemNS(ANDROID_NS, "name")?.nodeValue }
            .toSet()
        assertTrue("android.permission.FOREGROUND_SERVICE_CAMERA" in permissionNames)
        assertTrue("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" in permissionNames)
        assertTrue("android.permission.FOREGROUND_SERVICE_MICROPHONE" in permissionNames)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
