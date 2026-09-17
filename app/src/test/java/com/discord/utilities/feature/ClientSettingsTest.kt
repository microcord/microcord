package com.discord.utilities.feature

import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.feature.ClientPreferenceSnapshotTest.MemoryPreferences
import com.discord.utilities.feature.ClientSettings.BackupPolicy
import com.discord.utilities.feature.ClientSettings.Scope
import com.discord.utilities.feature.ClientSettings.Type
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSettingsTest {
    @After
    fun tearDown() = SharedPreferencesProvider.setInstanceForTesting(null)

    @Test
    fun `typed getters and setters retain native types defaults and nullable removal`() {
        val preferences = MemoryPreferences()
        fun <T> roundTrip(setting: ClientSettings.Setting<T>, value: T) {
            assertEquals(setting.default, setting.get(preferences))
            assertFalse(preferences.contains(setting.key))
            setting.set(preferences, value)
            assertEquals(value, setting.get(preferences))
            assertEquals(value, preferences.values[setting.key])
        }

        // These calls also compile-check the value types accepted by Setting<T>.
        roundTrip(ClientSettings.voiceMessages, false)
        roundTrip(ClientSettings.chatInputLayout, "SEND:HIDDEN")
        roundTrip(ClientSettings.resolve("default", "CACHE_KEY_FONT_SCALE") as ClientSettings.IntSetting, 125)
        roundTrip(ClientSettings.resolve("default", "CACHE_KEY_VOICE_SETTINGS_SENSITIVITY") as ClientSettings.FloatSetting, -25.5f)
        roundTrip(ClientSettings.resolve("default", "MUTED_USERS_V2") as ClientSettings.StringSetSetting, setOf("123,true"))
        assertEquals(5, preferences.applies)
        assertEquals(0, preferences.commits)

        ClientSettings.chatInputLayout.set(preferences, null)
        assertNull(ClientSettings.chatInputLayout.get(preferences))
        assertFalse(preferences.contains(ClientSettings.chatInputLayout.key))
    }

    @Test
    fun `every public feature flag uses a registered definition and only consent stays local`() {
        SharedPreferencesProvider.setInstanceForTesting(null)
        val getters = FeatureFlags::class.java.declaredMethods.filter {
            it.name.endsWith("Enabled") && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
        }
        val defaults = getters.associateWith { it.invoke(FeatureFlags) as Boolean }
        assertEquals(setOf("voiceMessagesEnabled", "avatarDecorationsEnabled", "nameplatesEnabled",
            "profileEffectsEnabled", "profileFramesEnabled", "maximumRefreshRateEnabled"), defaults.filterValues { it }.keys.map { it.name }.toSet())
        assertEquals(23, getters.size)
        assertFalse(FeatureFlags.disableServerProfilesEnabled())

        val scopes = Scope.entries.associate { it.id to MemoryPreferences() }
        val preferences = scopes.getValue(Scope.DEFAULT.id)
        SharedPreferencesProvider.setInstanceForTesting(preferences)
        val writtenKeys = hashSetOf<String>()
        getters.forEach { getter ->
            val setter = FeatureFlags::class.java.getDeclaredMethod(
                "set${getter.name.replaceFirstChar(Char::uppercaseChar)}", Boolean::class.javaPrimitiveType,
            )
            val before = preferences.values.keys.toSet()
            val default = defaults.getValue(getter)
            assertEquals(default, getter.invoke(FeatureFlags))
            setter.invoke(FeatureFlags, !default)
            assertEquals(!default, getter.invoke(FeatureFlags))
            val key = (preferences.values.keys - before).single()
            writtenKeys += key
            val definition = requireNotNull(ClientSettings.resolve("default", key))
            assertEquals(Type.BOOLEAN, definition.type)
            assertEquals(default, definition.default)
        }
        val flags = ClientSettings.registered.filter { it.type == Type.BOOLEAN && it.key.startsWith("feature_") }
        assertEquals(flags.map { it.key }.toSet(), writtenKeys)
        assertEquals(listOf(ClientSettings.cloudSettingsBackup), flags.filter { it.backupPolicy == BackupPolicy.EXCLUDE })
        val captured = ClientPreferenceSnapshot.capture(scopes)
        assertTrue(captured.strings.isEmpty())
        assertEquals(flags.filter { it.backupPolicy == BackupPolicy.INCLUDE }.map { "prefs:default/${it.key}" }.toSet(),
            captured.booleans.keys)
        assertTrue(FeatureFlags.cloudSettingsBackupEnabled())
        assertNull(ClientSettings.backupType("default", ClientSettings.cloudSettingsBackup.key))
    }

    @Test
    fun `customization families preserve stored keys and default visibility without initializing message caches`() {
        val preferences = MemoryPreferences()
        SharedPreferencesProvider.setInstanceForTesting(preferences)
        assertEquals("feature_chatbox_customizations_layout", ChatInputCustomization.LAYOUT_KEY)
        assertNull(ClientSettings.chatInputLayout.get(preferences))
        assertEquals("PROFILE:LEFT,ATTACHMENT:LEFT,APPS:LEFT,EXPAND:HIDDEN,EXPRESSION:INSIDE_END,SEND:RIGHT,KEYBOARD:INSIDE_END",
            ChatInputCustomization.encode(ChatInputCustomization.get()))
        ChannelSidebarCustomization.Surface.entries.forEach { surface ->
            ChannelSidebarCustomization.Button.entries.forEach { button ->
                val setting = ClientSettings.sidebarButton(surface, button)
                assertEquals("channel_sidebar_customization_${surface.name.lowercase()}_${button.name.lowercase()}", setting.key)
                assertEquals(button != ChannelSidebarCustomization.Button.PINS, setting.default)
                assertEquals(setting.default, ChannelSidebarCustomization.visible(surface, button))
                assertSame(setting, ClientSettings.resolve("default", setting.key))
            }
        }
        DmListCustomization.Component.entries.forEach { component ->
            val setting = ClientSettings.dmComponent(component)
            assertEquals("dm_list_customization_${component.name.lowercase()}", setting.key)
            assertEquals(component !in setOf(DmListCustomization.Component.MESSAGE, DmListCustomization.Component.TIMESTAMP), setting.default)
            assertEquals(setting.default, DmListCustomization.visible(component))
            assertSame(setting, ClientSettings.resolve("default", setting.key))
        }
        HeaderCustomization.Surface.entries.forEach { surface ->
            assertEquals("header_customization_${surface.name.lowercase()}_layout", surface.key)
            assertNull(ClientSettings.headerLayout(surface).get(preferences))
            assertTrue(HeaderCustomization.get(surface).all { it.visible })
            HeaderCustomization.Identity.entries.forEach { identity ->
                val setting = ClientSettings.headerIdentity(surface, identity)
                assertEquals("header_customization_${surface.name.lowercase()}_${identity.name.lowercase()}", setting.key)
                assertTrue(HeaderCustomization.identityVisible(surface, identity))
                assertSame(setting, ClientSettings.resolve("default", setting.key))
            }
        }
        assertEquals("message_customization_layout", ClientSettings.messageLayout.key)
        assertNull(ClientSettings.messageLayout.get(preferences))
        MessageCustomization.CoreElement.entries.forEach { element ->
            val setting = ClientSettings.messageCore(element)
            assertEquals("message_customization_core_${element.name.lowercase()}", setting.key)
            assertTrue(setting.get(preferences))
            assertSame(setting, ClientSettings.resolve("default", setting.key))
        }
        PluginSurfaceCustomization.Surface.entries.forEach { surface ->
            assertEquals("plugin_surface_${surface.name.lowercase()}", surface.key)
            assertNull(ClientSettings.pluginLayout(surface).get(preferences))
            assertSame(ClientSettings.pluginLayout(surface), ClientSettings.resolve("default", surface.key))
        }
        assertEquals("feature_gif_provider", ClientSettings.gifProvider.key)
        assertNull(ClientSettings.gifProvider.get(preferences))
        assertEquals(GifProvider.KLIPY, GifProviderSettings.get())
        assertTrue(preferences.values.isEmpty())
        assertEquals(0, preferences.edits)
    }

    @Test
    fun `DM and channel visibility remain independent and GIF encoding and gating are unchanged`() {
        val preferences = MemoryPreferences()
        SharedPreferencesProvider.setInstanceForTesting(preferences)
        ChannelSidebarCustomization.setVisible(ChannelSidebarCustomization.Surface.DM, ChannelSidebarCustomization.Button.SEARCH, false)
        assertFalse(ChannelSidebarCustomization.visible(ChannelSidebarCustomization.Surface.DM, ChannelSidebarCustomization.Button.SEARCH))
        assertTrue(ChannelSidebarCustomization.visible(ChannelSidebarCustomization.Surface.CHANNEL, ChannelSidebarCustomization.Button.SEARCH))
        HeaderCustomization.setIdentityVisible(HeaderCustomization.Surface.DM, HeaderCustomization.Identity.STATUS, false)
        assertTrue(HeaderCustomization.identityVisible(HeaderCustomization.Surface.CHANNEL, HeaderCustomization.Identity.STATUS))
        HeaderCustomization.setPluginVisible(HeaderCustomization.Surface.DM, "org.example.plugin", "action.with/slash", false)
        assertFalse(HeaderCustomization.pluginVisible(HeaderCustomization.Surface.DM, "org.example.plugin", "action.with/slash"))
        assertTrue(HeaderCustomization.pluginVisible(HeaderCustomization.Surface.CHANNEL, "org.example.plugin", "action.with/slash"))
        val dm = ClientSettings.headerPlugin(HeaderCustomization.Surface.DM, "org.example.plugin", "action.with/slash")
        val channel = ClientSettings.headerPlugin(HeaderCustomization.Surface.CHANNEL, "org.example.plugin", "action.with/slash")
        assertEquals(dm.key.removePrefix("header_customization_dm_"), channel.key.removePrefix("header_customization_channel_"))
        assertEquals(Type.BOOLEAN, ClientSettings.backupType("default", dm.key))

        GifProviderSettings.set(GifProvider.GIPHY)
        assertEquals("GIPHY", preferences.values[ClientSettings.gifProvider.key])
        assertEquals(GifProvider.KLIPY, GifProviderSettings.get())
        FeatureFlags.setMoreGifProvidersEnabled(true)
        assertEquals(GifProvider.GIPHY, GifProviderSettings.get())
        ClientSettings.gifProvider.set(preferences, "unknown")
        assertEquals(GifProvider.KLIPY, GifProviderSettings.get())
    }

    @Test
    fun `saving a plugin surface still invalidates its cached layout`() {
        val preferences = MemoryPreferences()
        SharedPreferencesProvider.setInstanceForTesting(preferences)
        val surface = PluginSurfaceCustomization.Surface.COMPOSER
        val entry = PluginSurfaceCustomization.Entry("plugin:org.example:action/with:separator")
        try {
            PluginSurfaceCustomization.save(surface, listOf(entry))
            assertEquals(listOf(entry), PluginSurfaceCustomization.get(surface, listOf(entry.key)))
            PluginSurfaceCustomization.save(surface, listOf(entry.copy(visible = false)))
            assertEquals(listOf(entry.copy(visible = false)), PluginSurfaceCustomization.get(surface, listOf(entry.key)))
            assertEquals("plugin%3Aorg.example%3Aaction%2Fwith%3Aseparator:false", preferences.values[surface.key])
        } finally {
            PluginSurfaceCustomization.save(surface, emptyList())
        }
    }

    @Test
    fun `legacy metadata and dynamic keys preserve native defaults scopes and backup policies`() {
        assertEquals(setOf("default", "durable", "plugins", "content", "engine"), Scope.entries.map { it.id }.toSet())
        val registered = ClientSettings.registered
        assertEquals(registered.size, registered.map { it.scope to it.key }.toSet().size)
        registered.forEach { assertSame(it, ClientSettings.resolve(it.scope.id, it.key)) }
        assertEquals(setOf("CACHE_KEY_INLINE_ATTACHMENT_MEDIA", "CACHE_KEY_INLINE_EMBED_MEDIA", "CACHE_KEY_RENDER_EMBEDS"),
            registered.filter { it.backupPolicy == BackupPolicy.WHEN_TEXT_SYNC_DISABLED }.map { it.key }.toSet())
        assertTrue(ClientSettings.syncTextAndImages.default)
        assertEquals(-1, ClientSettings.resolve("default", "CACHE_KEY_FONT_SCALE")?.default)
        assertEquals(-50f, ClientSettings.resolve("default", "CACHE_KEY_VOICE_SETTINGS_SENSITIVITY")?.default)
        assertEquals(100f, ClientSettings.resolve("default", "CACHE_KEY_VOICE_SETTINGS_OUTPUT_VOLUME")?.default)
        assertEquals(2, ClientSettings.resolve("default", "CACHE_KEY_VOICE_SETTINGS_NOISE_PROCESSING")?.default)
        assertEquals(1, ClientSettings.resolve("default", "CACHE_KEY_VOICE_SETTINGS_INPUT_MODE")?.default)
        assertEquals(1f, ClientSettings.resolve("durable", "developer_floating_shortcut_x")?.default)
        assertNull(ClientSettings.resolve("default", "CACHE_KEY_THEME"))
        assertEquals(Type.STRING, ClientSettings.resolve("durable", "CACHE_KEY_THEME")?.type)
        assertNull(ClientSettings.resolve("durable", "CACHE_KEY_THEME")?.default)
        assertEquals(false, ClientSettings.resolve("content", "grid_mode")?.default)
        assertEquals(false, ClientSettings.resolve("engine", "offloadAdmControls")?.default)
        assertEquals(true, ClientSettings.resolve("default", "plugin_enabled_org.example.plugin")?.default)
        assertEquals(false, ClientSettings.resolve("default", "plugin_package_enabled_org.example.plugin")?.default)
        assertEquals(-1, ClientSettings.resolve("default", "forum_layout_123")?.default)
        assertEquals(emptySet<String>(), ClientSettings.resolve("default", "forum_tag_filter_123")?.default)
        assertEquals(Type.PLUGIN, ClientSettings.backupType("plugins", "org.example.setting.with/slashes"))
        assertNull(ClientSettings.backupType("plugins", "org.example.apiKey"))
        assertNull(ClientSettings.backupType("default", "plugin_package_signer_org.example.plugin"))
        assertNull(ClientSettings.backupType("default", "plugin_starting_id"))
        assertNull(ClientSettings.backupType("default", "auth_token"))
    }
}
