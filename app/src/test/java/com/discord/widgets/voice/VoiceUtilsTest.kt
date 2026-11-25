package com.discord.widgets.voice

import android.content.SharedPreferences
import com.discord.app.AppPermissionsRequests
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreMediaSettings
import com.discord.stores.VoiceConfigurationCache
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceUtilsTest {
    @Test
    fun deniedMicrophoneJoinsMutedWithoutDeafeningAndCannotUnmute() {
        var granted = false
        val mediaSettings = mediaSettings { granted }
        val permissions = PendingMicrophonePermission()
        var joins = 0
        VoiceUtils.requestMicrophoneForVoiceJoin(permissions.requests, mediaSettings) {
            assertTrue(mediaSettings.getVoiceConfigurationBlocking().isSelfMuted())
            assertFalse(mediaSettings.getVoiceConfigurationBlocking().isSelfDeafened)
            joins++
        }

        assertEquals(0, joins)
        permissions.deny()
        assertEquals(1, joins)
        assertEquals(
            StoreMediaSettings.SelfMuteFailure.MICROPHONE_PERMISSION_DENIED,
            mediaSettings.toggleSelfMuted(),
        )
        assertTrue(mediaSettings.getVoiceConfigurationBlocking().isSelfMuted())

        mediaSettings.setVoiceInputMode(MediaEngineConnection.InputMode.PUSH_TO_TALK)
        assertEquals(
            StoreMediaSettings.SelfMuteFailure.MICROPHONE_PERMISSION_DENIED,
            mediaSettings.setSelfMuted(false),
        )
        granted = true
        assertNull(mediaSettings.setSelfMuted(false))
        assertFalse(mediaSettings.getVoiceConfigurationBlocking().isSelfMuted())
    }

    @Test
    fun grantedMicrophonePreservesBothExistingMuteChoices() {
        for (muted in listOf(false, true)) {
            val mediaSettings = mediaSettings { true }
            mediaSettings.setSelfMuted(muted)
            val permissions = PendingMicrophonePermission()
            var joins = 0
            VoiceUtils.requestMicrophoneForVoiceJoin(permissions.requests, mediaSettings) {
                assertEquals(muted, mediaSettings.getVoiceConfigurationBlocking().isSelfMuted())
                joins++
            }
            assertEquals(0, joins)
            permissions.grant()
            assertEquals(1, joins)
        }
    }

    @Test
    fun deniedJoinWhileDeafenedStaysMutedAfterUndeafening() {
        val mediaSettings = mediaSettings { false }
        mediaSettings.setSelfDeafen(true)
        val permissions = PendingMicrophonePermission()
        VoiceUtils.requestMicrophoneForVoiceJoin(permissions.requests, mediaSettings) {
            mediaSettings.setSelfDeafen(false)
        }
        permissions.deny()
        assertTrue(mediaSettings.getVoiceConfigurationBlocking().isSelfMuted())
        assertFalse(mediaSettings.getVoiceConfigurationBlocking().isSelfDeafened)
    }

    private class PendingMicrophonePermission {
        private lateinit var onDenied: () -> Unit
        private lateinit var onGranted: () -> Unit
        val requests = Proxy.newProxyInstance(
            AppPermissionsRequests::class.java.classLoader,
            arrayOf(AppPermissionsRequests::class.java),
        ) { _, method, args ->
            check(method.name == "requestMicrophone")
            @Suppress("UNCHECKED_CAST")
            onDenied = args[0] as () -> Unit
            @Suppress("UNCHECKED_CAST")
            onGranted = args[1] as () -> Unit
            null
        } as AppPermissionsRequests

        fun deny() = onDenied()
        fun grant() = onGranted()
    }

    private fun mediaSettings(hasMicrophonePermission: () -> Boolean): StoreMediaSettings {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: Any
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putBoolean", "putInt", "putFloat", "putStringSet" ->
                    editor.also { values[args[0] as String] = args[1] }
                "apply" -> null
                else -> error("Unexpected editor method: ${method.name}")
            }
        }
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBoolean", "getInt", "getFloat", "getStringSet" -> values[args[0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences
        return StoreMediaSettings(
            voiceConfigurationCache = VoiceConfigurationCache(preferences),
            hasMicrophonePermission = hasMicrophonePermission,
        )
    }
}
