package com.discord.widgets.channels

import android.content.SharedPreferences
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.utilities.cache.SharedPreferencesProvider
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRootContractsTest {
    @After
    fun tearDown() = SharedPreferencesProvider.setInstanceForTesting(null)

    @Test
    fun channelOnboardingPersistsSeenState() {
        SharedPreferencesProvider.setInstanceForTesting(memoryPreferences())
        val manager = ChannelOnboardingManager()

        assertFalse(manager.hasUserSeenChannelOnboarding())
        manager.markSeenUserChannelOnboarding()
        assertTrue(manager.hasUserSeenChannelOnboarding())
    }

    @Test
    fun privateChannelOverwritesDenyEveryoneAndAllowSelectedRoles() {
        val overwrites = WidgetCreateChannel.permissionOverwrites(
            guildId = 1L,
            channelType = Channel.GUILD_TEXT,
            privateSwitchChecked = true,
            forcedPrivate = false,
            selectedRoleIds = linkedSetOf(2L, 3L),
        ).orEmpty()

        assertEquals(listOf("1", "2", "3"), overwrites.map { it.id })
        assertEquals(Permission.VIEW_CHANNEL, overwrites[0].deny)
        assertEquals(listOf(Permission.VIEW_CHANNEL, Permission.VIEW_CHANNEL), overwrites.drop(1).map { it.allow })
    }

    @Test
    fun voicePrivacyUsesConnectAndPublicChannelsHaveNoOverwrites() {
        val voice = WidgetCreateChannel.permissionOverwrites(
            1L,
            Channel.GUILD_VOICE,
            privateSwitchChecked = true,
            forcedPrivate = false,
            selectedRoleIds = setOf(2L),
        ).orEmpty()

        assertEquals(Permission.CONNECT, voice.first().deny)
        assertEquals(Permission.CONNECT, voice.last().allow)
        assertNull(
            WidgetCreateChannel.permissionOverwrites(
                1L,
                Channel.GUILD_TEXT,
                privateSwitchChecked = false,
                forcedPrivate = false,
                selectedRoleIds = emptySet(),
            ),
        )
    }

    private fun memoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: Any
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putBoolean" -> editor.also { values[args[0] as String] = args[1] }
                "apply" -> null
                else -> editor
            }
        }
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> values[args[0]] ?: args[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }
}
