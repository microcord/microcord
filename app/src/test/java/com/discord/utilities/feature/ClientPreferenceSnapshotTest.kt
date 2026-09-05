package com.discord.utilities.feature

import android.content.SharedPreferences
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.feature.ManticordSettingsBackupCodec.Payload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClientPreferenceSnapshotTest {
    @After
    fun tearDown() = SharedPreferencesProvider.setInstanceForTesting(null)

    @Test
    fun `all feature flags and every customization family round trip without repair`() {
        val source = preferences()
        val defaults = source.getValue("default")
        SharedPreferencesProvider.setInstanceForTesting(defaults, source.getValue("durable"))
        listOf<(Boolean) -> Unit>(
            FeatureFlags::setVoiceMessagesEnabled, FeatureFlags::setTapTapEnabled,
            FeatureFlags::setFetchUncachedUsersEnabled, FeatureFlags::setViewProfileImagesEnabled,
            FeatureFlags::setChatboxCustomizationsEnabled, FeatureFlags::setHeaderCustomizationsEnabled,
            FeatureFlags::setTypingUserAvatarsEnabled, FeatureFlags::setChatHeaderStatusIndicatorsEnabled,
            FeatureFlags::setMoreGifProvidersEnabled, FeatureFlags::setGifUrlToInputEnabled,
            FeatureFlags::setQuickMessageActionsEnabled, FeatureFlags::setMessageCustomizationsEnabled,
            FeatureFlags::setMessageLimitCounterEnabled, FeatureFlags::setAvatarDecorationsEnabled,
            FeatureFlags::setNameplatesEnabled, FeatureFlags::setProfileEffectsEnabled,
            FeatureFlags::setProfileFramesEnabled, FeatureFlags::setNitroSpoofEnabled,
            FeatureFlags::setSilentTypingEnabled,
        ).forEachIndexed { index, setter -> setter(index % 2 == 0) }
        assertEquals(19, defaults.values.size)
        ChatInputCustomization.save(ChatInputCustomization.Button.entries.mapIndexed { index, button ->
            ChatInputCustomization.Entry(button, ChatInputCustomization.Zone.entries[index % 5])
        })
        ChannelSidebarCustomization.Surface.entries.forEach { surface ->
            ChannelSidebarCustomization.Button.entries.forEach { button ->
                ChannelSidebarCustomization.setVisible(surface, button, false)
            }
        }
        DmListCustomization.Component.entries.forEach { DmListCustomization.setVisible(it, false) }
        HeaderCustomization.Surface.entries.forEach { surface ->
            HeaderCustomization.save(surface, HeaderCustomization.actions(surface).reversed().map {
                HeaderCustomization.Entry(it, false)
            })
            HeaderCustomization.Identity.entries.forEach { HeaderCustomization.setIdentityVisible(surface, it, false) }
            HeaderCustomization.setPluginVisible(surface, "org.example.plugin", "action.with/slash", false)
        }
        // Do not initialize the restart-bound message caches in a shared JVM test suite.
        defaults.values["message_customization_layout"] = MessageCustomization.encode(
            MessageCustomization.Section.entries.reversed().map { MessageCustomization.Entry(it, false) },
        )
        MessageCustomization.CoreElement.entries.forEach {
            defaults.values["message_customization_core_${it.name.lowercase()}"] = false
        }
        PluginSurfaceCustomization.Surface.entries.forEach { surface ->
            PluginSurfaceCustomization.save(surface, listOf(
                PluginSurfaceCustomization.Entry("plugin:org.example.plugin:action.with/slash", false),
            ))
        }
        GifProviderSettings.set(GifProvider.GIPHY)

        val payload = ClientPreferenceSnapshot.capture(source)
        assertEquals(defaults.values.keys.map { "prefs:default/$it" }.toSet(), payload.booleans.keys + payload.strings.keys)
        assertEquals(7, payload.strings.keys.count { it.startsWith("prefs:default/plugin_surface_") })
        assertEquals(2, payload.booleans.keys.count { it.contains("_plugin_") })
        val destination = preferences()
        ClientPreferenceSnapshot.restore(destination, payload)
        assertEquals(defaults.values, destination.getValue("default").values)
        assertEquals(payload, ClientPreferenceSnapshot.capture(destination))
    }

    @Test
    fun `typed local settings use original storage types and scopes`() {
        val source = preferences()
        source.getValue("default").values.putAll(mapOf(
            "CACHE_KEY_IMAGE_COMPRESSION" to true,
            "CACHE_KEY_USE_CHROME_CUSTOM_TABS" to false,
            "CACHE_KEY_MOBILE_OVERLAY" to true,
            "CACHE_KEY_SHIFT_ENTER_TO_SEND" to true,
            "CACHE_KEY_SYNC_TEXT_AND_IMAGES" to false,
            "CACHE_KEY_INLINE_ATTACHMENT_MEDIA" to false,
            "CACHE_KEY_INLINE_EMBED_MEDIA" to true,
            "CACHE_KEY_RENDER_EMBEDS" to false,
            "CACHE_KEY_LOCALE_SYNC" to false,
            "CACHE_KEY_LOCALE" to "zh-Hant-TW",
            "CACHE_KEY_FONT_SCALE" to 150,
            "developer_premium_tier_override" to 4,
            "screenshot_detection_enabled" to true,
            "plugins_loading_enabled" to true,
            "plugin_enabled_org.example.plugin-v2" to false,
            "plugin_package_enabled_dev.example.plugin" to true,
            "CACHE_KEY_VOICE_SETTINGS_IS_MUTED" to true,
            "CACHE_KEY_VOICE_SETTINGS_IS_DEAFENED" to false,
            "CACHE_KEY_VOICE_SETTINGS_AUTOMATIC_VAD" to false,
            "CACHE_KEY_VOICE_SETTINGS_VAD_USE_KRISP" to true,
            "CACHE_KEY_VOICE_SETTINGS_AUTOMATIC_GAIN_CONTROL" to false,
            "CACHE_KEY_VOICE_SETTINGS_ECHO_CANCELLATION" to true,
            "CACHE_KEY_VOICE_SETTINGS_SIDECHAIN_COMPRESSION" to false,
            "CACHE_KEY_HIDE_VOICE_PARTICIPANTS" to true,
            "CACHE_KEY_HIDE_SELF_VIDEO" to true,
            "CACHE_KEY_VOICE_SETTINGS_NOISE_PROCESSING" to 2,
            "CACHE_KEY_VOICE_SETTINGS_INPUT_MODE" to 2,
            "CACHE_KEY_VOICE_SETTINGS_SENSITIVITY" to -37.125f,
            "CACHE_KEY_VOICE_SETTINGS_OUTPUT_VOLUME" to 99.125f,
            "MUTED_USERS_V2" to linkedSetOf("9223372036854775807,false", "123,true"),
            "USER_OUTPUT_VOLUMES_V2" to setOf("123,200.0", "456,0.0"),
            "USER_TRUSTED_DOMAINS_CACHE_KEY" to setOf("example.test", "[::1]"),
            "forum_sort_order_123" to 1,
            "forum_layout_123" to 2,
            "forum_tag_setting_123" to 1,
            "forum_tag_filter_123" to setOf("456", "789"),
        ))
        source.getValue("durable").values.putAll(mapOf(
            "CACHE_KEY_THEME" to "materialYou",
            "CACHE_KEY_THEME_SYNC" to false,
            "developer_verbose_boot" to true,
            "developer_floating_shortcut" to true,
            "developer_floating_shortcut_x" to 0.12345679f,
            "developer_floating_shortcut_y" to 1f,
            "developer_log_gateway_events" to true,
            "developer_log_gateway_lifecycle" to false,
            "developer_log_gateway_voice_states" to true,
            "developer_log_rest_requests" to true,
            "DEBUG_OPTION_CANARY" to false,
            "CONTACT_SYNC_DISMISS_UPSELL" to true,
            "PUSH_NOTIFICATIONS_DISMISS_UPSELL" to true,
        ))
        source.getValue("plugins").values.putAll(mapOf(
            "org.example.plugin.full.key.with.dots" to "line 1\nline 2: \"quoted\" \\path\t\uD83D\uDE00",
            "org.example.plugin.setting/with:separators" to false,
            "full stored key" to "",
        ))
        source.getValue("content").values["grid_mode"] = true
        source.getValue("engine").values["offloadAdmControls"] = true

        val payload = ClientPreferenceSnapshot.capture(source)
        assertEquals(source.values.sumOf { it.values.size }, payload.booleans.size + payload.strings.size)
        assertEquals("150", payload.strings["prefs:default/CACHE_KEY_FONT_SCALE"])
        assertEquals("-37.125", payload.strings["prefs:default/CACHE_KEY_VOICE_SETTINGS_SENSITIVITY"])
        assertEquals("[\"123,true\",\"9223372036854775807,false\"]", payload.strings["prefs:default/MUTED_USERS_V2"])
        val destination = preferences()
        ClientPreferenceSnapshot.restore(destination, payload)
        source.forEach { (scope, prefs) -> assertEquals(scope, prefs.values, destination.getValue(scope).values) }
        assertTrue(destination.values.all { it.commits == 1 && it.applies == 0 })
    }

    @Test
    fun `absent settings remove overrides while auth caches signer approvals and cloud consent survive`() {
        val destination = preferences()
        val excluded = mapOf<String, Any>(
            "auth_token" to "fixture-auth",
            "CACHE_KEY_AUTH_STATE" to "fixture-state",
            "CACHE_KEY_DEVELOPER_MODE" to true,
            "CACHE_KEY_FINGERPRINT" to "fixture-fingerprint",
            "USER_SETTINGS_PROTO" to "fixture-proto",
            "unknown_setting" to true,
            "feature_cloud_settings_backup" to true,
            "plugin_package_signer_dev.example.plugin" to "fixture-signer",
            "plugin_starting_id" to "org.example.plugin",
            "plugin_starting_package" to "dev.example.plugin",
            "CACHE_KEY_CONTACT_SYNC_LAST_UPLOAD_TIME" to Long.MAX_VALUE,
        )
        destination.getValue("default").values.putAll(excluded + mapOf(
            "feature_voice_messages" to false,
            "feature_chatbox_customizations_layout" to "SEND:HIDDEN",
            "CACHE_KEY_FONT_SCALE" to 80,
            "MUTED_USERS_V2" to setOf("123,true"),
            "plugin_enabled_org.example.plugin" to false,
            "plugin_package_enabled_dev.example.plugin" to true,
            "header_customization_dm_plugin_${"a".repeat(64)}" to false,
            "forum_tag_filter_123" to setOf("456"),
        ))
        destination.getValue("durable").values.putAll(mapOf(
            "CACHE_KEY_THEME" to "dark", "CONTACT_SYNC_DISMISS_UPSELL" to true,
            "CACHE_KEY_ACCOUNTS" to "fixture-accounts", "CACHE_KEY_FINGERPRINT" to "fixture-fingerprint",
        ))
        val pluginSecrets = listOf("token", "Password", "clientSecret", "api_key", "apiKey", "Cookie", "credentials")
            .associate { "org.example.plugin.$it" to "fixture-only" }
        destination.getValue("plugins").values.putAll(pluginSecrets + mapOf("org.example.plugin.visible" to false))
        destination.getValue("content").values.putAll(mapOf("grid_mode" to true, "cached_uri" to "fixture-uri"))
        destination.getValue("engine").values.putAll(mapOf("offloadAdmControls" to true, "device_cache" to "fixture-device"))

        val captured = ClientPreferenceSnapshot.capture(destination)
        assertTrue(excluded.keys.none { "prefs:default/$it" in captured.booleans || "prefs:default/$it" in captured.strings })
        assertTrue(pluginSecrets.keys.none { "prefs:plugins/$it" in captured.strings })
        ClientPreferenceSnapshot.restore(destination, Payload(mapOf("local:other-owner" to true), mapOf("local:anything" to "ignored")))
        assertEquals(excluded, destination.getValue("default").values)
        assertEquals(setOf("CACHE_KEY_ACCOUNTS", "CACHE_KEY_FINGERPRINT"), destination.getValue("durable").values.keys)
        assertEquals(pluginSecrets, destination.getValue("plugins").values)
        assertEquals(mapOf("cached_uri" to "fixture-uri"), destination.getValue("content").values)
        assertEquals(mapOf("device_cache" to "fixture-device"), destination.getValue("engine").values)
        assertTrue(destination.values.all { it.commits == 1 })
    }

    @Test
    fun `media mirrors are exported only with sync disabled and excluded copies stay untouched`() {
        val source = preferences()
        val media = listOf("CACHE_KEY_INLINE_ATTACHMENT_MEDIA", "CACHE_KEY_INLINE_EMBED_MEDIA", "CACHE_KEY_RENDER_EMBEDS")
        media.forEach { source.getValue("default").values[it] = false }
        assertTrue(ClientPreferenceSnapshot.capture(source).booleans.isEmpty())
        source.getValue("default").values["CACHE_KEY_SYNC_TEXT_AND_IMAGES"] = true
        assertEquals(1, ClientPreferenceSnapshot.capture(source).booleans.size)
        source.getValue("default").values["CACHE_KEY_SYNC_TEXT_AND_IMAGES"] = false
        assertEquals(4, ClientPreferenceSnapshot.capture(source).booleans.size)

        ClientPreferenceSnapshot.restore(source, Payload(emptyMap(), emptyMap()))
        assertEquals(media.toSet(), source.getValue("default").values.keys)
        ClientPreferenceSnapshot.restore(source, Payload(mapOf("prefs:default/CACHE_KEY_SYNC_TEXT_AND_IMAGES" to false), emptyMap()))
        assertEquals(setOf("CACHE_KEY_SYNC_TEXT_AND_IMAGES"), source.getValue("default").values.keys)
    }

    @Test
    fun `unknown addresses excluded settings and wrong types fail before any editor is opened`() {
        val invalid = listOf(
            Payload(mapOf("prefs:default/feature_future" to true), emptyMap()),
            Payload(mapOf("prefs:default/feature_cloud_settings_backup" to true), emptyMap()),
            Payload(mapOf("prefs:default/CACHE_KEY_DEVELOPER_MODE" to true), emptyMap()),
            Payload(mapOf("prefs:durable/screenshot_detection_enabled" to true), emptyMap()),
            Payload(mapOf("prefs:engine/other" to true), emptyMap()),
            Payload(mapOf("prefs:default/header_customization_dm_plugin_not-a-sha256" to true), emptyMap()),
            Payload(mapOf("prefs:default/header_customization_other_plugin_${"a".repeat(64)}" to true), emptyMap()),
            Payload(mapOf("prefs:default/channel_sidebar_customization_dm_future" to true), emptyMap()),
            Payload(mapOf("prefs:default/dm_list_customization_future" to true), emptyMap()),
            Payload(mapOf("prefs:default/header_customization_dm_future" to true), emptyMap()),
            Payload(mapOf("prefs:default/message_customization_core_future" to true), emptyMap()),
            Payload(mapOf("prefs:default/plugin_enabled_" to true), emptyMap()),
            Payload(mapOf("prefs:default/plugin_package_enabled_invalid-package" to true), emptyMap()),
            Payload(mapOf("prefs:other/grid_mode" to true), emptyMap()),
            Payload(mapOf("prefs:default/" to true), emptyMap()),
            Payload(mapOf("prefs:default" to true), emptyMap()),
            Payload(mapOf("prefs:plugins/org.example.accessToken" to false), emptyMap()),
            Payload(mapOf("prefs:default/CACHE_KEY_FONT_SCALE" to true), emptyMap()),
            Payload(mapOf("prefs:default/MUTED_USERS_V2" to true), emptyMap()),
            Payload(mapOf("prefs:default/CACHE_KEY_RENDER_EMBEDS" to true), emptyMap()),
            Payload(emptyMap(), mapOf("prefs:default/auth_token" to "fixture-auth")),
            Payload(emptyMap(), mapOf("prefs:default/plugin_starting_id" to "fixture-id")),
            Payload(emptyMap(), mapOf("prefs:default/plugin_package_signer_dev.example.plugin" to "fixture-signer")),
            Payload(emptyMap(), mapOf("prefs:plugins/org.example.api_key" to "fixture-key")),
            Payload(emptyMap(), mapOf("prefs:default/feature_voice_messages" to "true")),
            Payload(emptyMap(), mapOf("prefs:default/plugin_surface_future" to "")),
            Payload(emptyMap(), mapOf("prefs:default/forum_layout_0" to "1")),
            Payload(emptyMap(), mapOf("prefs:default/forum_layout_01" to "1")),
            Payload(emptyMap(), mapOf("prefs:default/forum_layout_9223372036854775808" to "1")),
            Payload(mapOf("prefs:plugins/org.example.setting" to true), mapOf("prefs:plugins/org.example.setting" to "duplicate")),
        )
        val destination = preferences()
        destination.getValue("default").values["feature_voice_messages"] = false
        invalid.forEach { payload ->
            rejected { ClientPreferenceSnapshot.validate(payload) }
            rejected { ClientPreferenceSnapshot.restore(destination, payload) }
            assertTrue(destination.values.all { it.edits == 0 && it.commits == 0 && it.applies == 0 })
            assertEquals(false, destination.getValue("default").values["feature_voice_messages"])
        }
    }

    @Test
    fun `numeric enums locales layouts and strict string sets reject malformed or unsafe values`() {
        val cases = mapOf(
            "default/CACHE_KEY_FONT_SCALE" to listOf("0", "79", "151", "1.0", "2147483648", "080"),
            "default/developer_premium_tier_override" to listOf("-2", "5"),
            "default/CACHE_KEY_VOICE_SETTINGS_INPUT_MODE" to listOf("0", "3"),
            "default/CACHE_KEY_VOICE_SETTINGS_NOISE_PROCESSING" to listOf("-1", "4"),
            "default/CACHE_KEY_VOICE_SETTINGS_SENSITIVITY" to listOf("-101", "1", "NaN", "Infinity", "0x1.0p0", " 0"),
            "default/CACHE_KEY_VOICE_SETTINGS_OUTPUT_VOLUME" to listOf("-1", "1e999", "1e-10000"),
            "durable/developer_floating_shortcut_x" to listOf("-0.01", "1.01", "NaN"),
            "default/CACHE_KEY_LOCALE" to listOf("", "en_US", "en/US", "en--US", "en\nUS"),
            "durable/CACHE_KEY_THEME" to listOf("system", "../../theme"),
            "default/feature_gif_provider" to listOf("giphy", "OTHER"),
            "default/feature_chatbox_customizations_layout" to listOf("SEND:UNKNOWN", "UNKNOWN:LEFT", "SEND:LEFT,SEND:RIGHT"),
            "default/header_customization_channel_layout" to listOf("VOICE:true", "PINS:1"),
            "default/message_customization_layout" to listOf("CORE:TRUE", "OTHER:true"),
            "default/plugin_surface_composer" to listOf("%xx:true", "%FF:true", "plugin%3Ax:false,plugin%3Ax:true"),
            "default/forum_layout_123" to listOf("0", "3"),
            "default/forum_sort_order_123" to listOf("2"),
            "default/forum_tag_setting_123" to listOf("2"),
            "default/forum_tag_filter_123" to listOf("[123]", "[\"0\"]", "[\"-1\"]", "[\"1\",\"1\"]"),
            "default/MUTED_USERS_V2" to listOf(
                "null", "{}", "[true]", "[null]", "['123,true']", "[123,true]", "[\"123,true\",]",
                "[\"123,true\"] trailing", "/*comment*/[]", "[\"123,true\",\"123,false\"]",
                "[\"123,yes\"]", "[\"0,true\"]", "[\"9223372036854775808,true\"]", "[\"01,true\"]",
            ),
            "default/USER_OUTPUT_VOLUMES_V2" to listOf("[\"1,NaN\"]", "[\"1,-1\"]", "[\"1,1e-10000\"]", "[\"1,2,3\"]"),
            "default/USER_TRUSTED_DOMAINS_CACHE_KEY" to listOf("[\"\"]", "[\"user@example.test\"]", "[\"example.test/path\"]"),
        )
        val destination = preferences()
        cases.forEach { (key, values) ->
            values.forEach { value ->
                rejected {
                    ClientPreferenceSnapshot.restore(destination, Payload(
                        mapOf("prefs:content/grid_mode" to true), mapOf("prefs:$key" to value),
                    ))
                }
            }
        }
        assertTrue(destination.values.all { it.edits == 0 })
    }

    @Test
    fun `empty sets system defaults legacy themes and boundary values are preserved`() {
        val valid = mapOf(
            "default/CACHE_KEY_FONT_SCALE" to listOf("-1", "80", "150"),
            "default/developer_premium_tier_override" to listOf("-1", "0", "1", "2", "3", "4"),
            "default/CACHE_KEY_VOICE_SETTINGS_INPUT_MODE" to listOf("1", "2"),
            "default/CACHE_KEY_VOICE_SETTINGS_NOISE_PROCESSING" to listOf("0", "1", "2", "3"),
            "default/CACHE_KEY_VOICE_SETTINGS_SENSITIVITY" to listOf("-100.0", "-0.0", "-12.34567"),
            "default/CACHE_KEY_VOICE_SETTINGS_OUTPUT_VOLUME" to listOf("0.0", "400.0", Float.MIN_VALUE.toString(), Float.MAX_VALUE.toString()),
            "durable/developer_floating_shortcut_y" to listOf("0.0", "1.0", "0.12345679"),
            "default/CACHE_KEY_LOCALE" to listOf("en-US", "tr", "zh-Hant-TW", "es-419"),
            "durable/CACHE_KEY_THEME" to listOf("light", "dark", "darker", "midnight", "pureEvil", "materialYou"),
            "default/feature_gif_provider" to listOf("KLIPY", "TENOR", "GIPHY"),
            "default/feature_chatbox_customizations_layout" to listOf("", "KEYBOARD:INSIDE_END"),
            "default/MUTED_USERS_V2" to listOf("[]", "[\"9223372036854775807,true\"]"),
            "default/USER_OUTPUT_VOLUMES_V2" to listOf("[]", "[\"1,0.0\",\"2,400.0\"]"),
            "default/forum_layout_123" to listOf("-1", "1", "2"),
            "default/forum_tag_filter_123" to listOf("[]"),
        )
        valid.forEach { (key, values) ->
            values.forEach { value -> ClientPreferenceSnapshot.validate(Payload(emptyMap(), mapOf("prefs:$key" to value))) }
        }
        val destination = preferences()
        ClientPreferenceSnapshot.restore(destination, Payload(emptyMap(), mapOf("prefs:default/MUTED_USERS_V2" to "[]")))
        assertEquals(emptySet<String>(), destination.getValue("default").values["MUTED_USERS_V2"])
        assertTrue(destination.getValue("engine").values.isEmpty())
        assertEquals(1, destination.getValue("engine").commits)
    }

    @Test
    fun `bounds fail closed instead of truncating plugin keys strings or sets`() {
        listOf(
            Payload(emptyMap(), mapOf("prefs:plugins/${"k".repeat(1025)}" to "value")),
            Payload(emptyMap(), mapOf("prefs:plugins/p.key" to "x".repeat(256 * 1024 + 1))),
            Payload(emptyMap(), mapOf("prefs:plugins/p.key" to "\uD800")),
            Payload(emptyMap(), mapOf("prefs:plugins/p.\nkey" to "value")),
            Payload((1..4097).associate { "prefs:plugins/p.key$it" to true }, emptyMap()),
            Payload(emptyMap(), (1..5).associate { "prefs:plugins/p.key$it" to "x".repeat(256 * 1024) }),
            Payload(emptyMap(), mapOf("prefs:default/forum_tag_filter_123" to
                (1..4097).joinToString(",", "[", "]") { "\"$it\"" })),
        ).forEach { payload -> rejected { ClientPreferenceSnapshot.validate(payload) } }
        val largeValid = "x".repeat(128 * 1024)
        val source = preferences()
        source.getValue("plugins").values["org.example.full.key"] = largeValid
        val captured = ClientPreferenceSnapshot.capture(source)
        assertEquals(largeValid, captured.strings["prefs:plugins/org.example.full.key"])
    }

    @Test
    fun `capture does not coerce a corrupt preference or accept unsupported plugin types`() {
        listOf(
            Triple("default", "feature_voice_messages", "true"),
            Triple("default", "CACHE_KEY_FONT_SCALE", 100L),
            Triple("default", "CACHE_KEY_VOICE_SETTINGS_OUTPUT_VOLUME", 100),
            Triple("default", "MUTED_USERS_V2", setOf(123)),
            Triple("plugins", "org.example.setting", 42),
            Triple("plugins", "org.example.setting", 42L),
            Triple("plugins", "org.example.setting", 42f),
            Triple("plugins", "org.example.setting", setOf("value")),
        ).forEach { (scope, key, value) ->
            val source = preferences()
            source.getValue(scope).values[key] = value
            rejected { ClientPreferenceSnapshot.capture(source) }
            assertTrue(source.values.all { it.edits == 0 })
        }
        @Suppress("UNCHECKED_CAST")
        val wrongBooleans = mapOf("prefs:content/grid_mode" to "true") as Map<String, Boolean>
        @Suppress("UNCHECKED_CAST")
        val wrongStrings = mapOf("prefs:plugins/p.setting" to false) as Map<String, String>
        rejected { ClientPreferenceSnapshot.validate(Payload(wrongBooleans, emptyMap())) }
        rejected { ClientPreferenceSnapshot.validate(Payload(emptyMap(), wrongStrings)) }
    }

    @Test
    fun `failed commit is reported and a retained snapshot can be retried`() {
        val destination = preferences()
        destination.getValue("durable").commitSucceeds = false
        val payload = Payload(mapOf("prefs:default/feature_voice_messages" to false), mapOf("prefs:durable/CACHE_KEY_THEME" to "dark"))
        try {
            ClientPreferenceSnapshot.restore(destination, payload)
            fail("Commit failure must not be reported as a successful restore")
        } catch (failure: IllegalStateException) {
            assertEquals("Unable to commit client preference snapshot", failure.message)
        }
        assertEquals(1, destination.getValue("default").commits)
        assertEquals(1, destination.getValue("durable").commits)
        destination.getValue("durable").commitSucceeds = true
        ClientPreferenceSnapshot.restore(destination, payload)
        assertEquals(payload, ClientPreferenceSnapshot.capture(destination))
        assertTrue(destination.values.all { it.applies == 0 })
    }

    @Test
    fun `retry commits an empty rollback scope even when failed commit already changed memory`() {
        val destination = preferences()
        val plugin = destination.getValue("plugins")
        val key = "example.plugin.visible"
        ClientPreferenceSnapshot.restore(destination, Payload(mapOf("prefs:plugins/$key" to true), emptyMap()))
        plugin.commitSucceeds = false
        try {
            ClientPreferenceSnapshot.restore(destination, Payload(emptyMap(), emptyMap()))
            fail("Expected failed rollback write")
        } catch (_: IllegalStateException) { }
        assertTrue(plugin.values.isEmpty())
        assertEquals(true, plugin.diskValues[key])
        plugin.commitSucceeds = true
        ClientPreferenceSnapshot.restore(destination, Payload(emptyMap(), emptyMap()))
        assertTrue(plugin.diskValues.isEmpty())
    }

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Expected snapshot rejection")
        } catch (failure: IllegalArgumentException) {
            assertEquals("Invalid client preference snapshot", failure.message)
            assertNull(failure.cause)
        }
    }

    private fun preferences() = listOf("default", "durable", "plugins", "content", "engine")
        .associateWith { MemoryPreferences() }

    @Suppress("UNCHECKED_CAST")
    internal class MemoryPreferences : SharedPreferences {
        val values = linkedMapOf<String, Any>()
        val diskValues = linkedMapOf<String, Any>()
        var edits = 0
        var commits = 0
        var applies = 0
        var commitSucceeds = true
        override fun getAll() = values.toMap()
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = (values[key] as Set<String>?)?.toSet() ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor {
            edits++
            return Editor()
        }

        private inner class Editor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear(): SharedPreferences.Editor = error("Snapshot must never clear a preference file")
            override fun commit(): Boolean {
                commits++
                flush()
                if (commitSucceeds) { diskValues.clear(); diskValues.putAll(values) }
                return commitSucceeds
            }
            override fun apply() {
                applies++
                flush()
            }
            private fun flush() {
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                pending.clear()
            }
        }
    }
}
