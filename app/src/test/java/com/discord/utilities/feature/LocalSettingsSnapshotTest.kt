package com.discord.utilities.feature

import com.discord.utilities.feature.ManticordSettingsBackupCodec.Payload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalSettingsSnapshotTest {
    @Test
    fun fullLocalFixtureValidatesWithoutPersistenceInitialization() {
        LocalSettingsSnapshot.validate(fixture())
        LocalSettingsSnapshot.validate(fixture().copy(
            booleans = fixture().booleans + ("prefs:other" to true),
            strings = fixture().strings + ("plugin:other" to "not local JSON"),
        ))
        for (tab in listOf("EMOJI", "GIF", "STICKER")) {
            LocalSettingsSnapshot.validate(changed(TAB, "\"$tab\""))
        }
        LocalSettingsSnapshot.validate(changed(COUNTRY, """{"name":"","alpha2":"","phoneCountryCode":""}"""))
        LocalSettingsSnapshot.validate(changed(COUNTRY,
            """{"name":"American Samoa","alpha2":"AS","phoneCountryCode":"+1 684"}"""))
        LocalSettingsSnapshot.validate(changed(FOLDERS, "[-9223372036854775808,0,9223372036854775807]"))
        LocalSettingsSnapshot.validate(changed(VOICE, "[9223372036854775807]"))
        LocalSettingsSnapshot.validate(changed(EXPERIMENTS, """{"minimum":-2147483648,"maximum":2147483647}"""))
        LocalSettingsSnapshot.validate(changed(CAMERA, "\"\""))
        for (key in listOf(HIDE_MUTED, VOICE, FOLDERS)) LocalSettingsSnapshot.validate(changed(key, "[]"))
        for (key in listOf(EXPERIMENTS, CATEGORIES)) LocalSettingsSnapshot.validate(changed(key, "{}"))
    }

    @Test
    fun allLocalKeysAreRequiredAndUnknownOrWrongNamespaceTypesAreRejected() {
        val payload = fixture()
        payload.booleans.keys.filter { it.startsWith("local:") }.forEach { key ->
            rejected(payload.copy(booleans = payload.booleans - key))
            rejected(payload.copy(booleans = payload.booleans - key, strings = payload.strings + (key to "true")))
        }
        payload.strings.keys.forEach { key ->
            rejected(payload.copy(strings = payload.strings - key))
            rejected(payload.copy(booleans = payload.booleans + (key to false)))
        }
        rejected(Payload(emptyMap(), emptyMap()))
        rejected(payload.copy(booleans = payload.booleans + ("local:unknown" to false)))
        for (key in listOf("STORE_AUTHED_TOKEN", "NOTIFICATION_CLIENT_SETTINGS_V3", "STORE_USERS_ME_V13",
            "STORE_MULTI_ACCOUNT_TOKENS", "STORE_SETTINGS_USER_GUILD_V6", "GIF_FRECENTS_V1")) {
            rejected(payload.copy(strings = payload.strings + ("local:$key" to "\"excluded\"")))
        }
    }

    @Test
    fun notificationsContainOnlyFiveLocalBooleans() {
        val valid = fixture().strings.getValue("local:$NOTIFICATIONS")
        for (key in listOf("isEnabledInApp", "token", "locale", "sendBlockedChannels", "unknown")) {
            rejected(changed(NOTIFICATIONS, valid.dropLast(1) + ",\"$key\":false}"))
            rejected(changed(NOTIFICATIONS, valid.replace("isWake", key)))
        }
        for (value in listOf("\"true\"", "1", "null", "[]", "{}")) {
            rejected(changed(NOTIFICATIONS, valid.replace("\"isEnabled\":true", "\"isEnabled\":$value")))
        }
        rejected(changed(NOTIFICATIONS, valid.replace("\"isWake\":false,", "")))
        rejected(changed(NOTIFICATIONS, valid.dropLast(1) + ",\"isEnabled\":false}"))
    }

    @Test
    fun animationKeysAreRequiredOnlyWhenSourceSyncIsExplicitlyDisabled() {
        val local = fixture().let {
            it.copy(
                booleans = it.booleans + mapOf(SYNC_TEXT_PREF to false,
                    "local:$ANIMATED_EMOJIS" to false, "local:$AUTO_PLAY_GIFS" to true),
                strings = it.strings + ("local:$STICKER_ANIMATION" to "0"),
            )
        }
        for (setting in 0..2) LocalSettingsSnapshot.validate(changed(STICKER_ANIMATION, "$setting", local))
        for (key in listOf(ANIMATED_EMOJIS, AUTO_PLAY_GIFS)) {
            rejected(local.copy(booleans = local.booleans - "local:$key"))
            rejected(local.copy(booleans = local.booleans - "local:$key",
                strings = local.strings + ("local:$key" to "true")))
        }
        rejected(local.copy(strings = local.strings - "local:$STICKER_ANIMATION"))
        rejected(local.copy(booleans = local.booleans + ("local:$STICKER_ANIMATION" to true)))
        rejected(fixture().let { it.copy(booleans = it.booleans + (SYNC_TEXT_PREF to false)) })
        for (sourceSync in listOf<Boolean?>(null, true)) {
            val synced = fixture().let {
                if (sourceSync == null) it else it.copy(booleans = it.booleans + (SYNC_TEXT_PREF to sourceSync))
            }
            LocalSettingsSnapshot.validate(synced)
            rejected(synced.copy(booleans = synced.booleans + ("local:$ANIMATED_EMOJIS" to true)))
            rejected(synced.copy(booleans = synced.booleans + ("local:$AUTO_PLAY_GIFS" to true)))
            rejected(changed(STICKER_ANIMATION, "0", synced))
        }
        for (invalid in listOf("-1", "3", "2147483648", "\"0\"", "0.0", "1e0", "null", "true", "[]", "{}")) {
            rejected(changed(STICKER_ANIMATION, invalid, local))
        }
    }

    @Test
    fun contactPermanentDismissalMustMatchTheSuppliedDurablePreference() {
        val dismissed = fixture()
        LocalSettingsSnapshot.validate(dismissed)
        rejected(dismissed.copy(booleans = dismissed.booleans - CONTACT_UPSELL_PREF))
        rejected(dismissed.copy(booleans = dismissed.booleans + (CONTACT_UPSELL_PREF to false)))
        val notDismissed = changed(CONTACTS,
            """{"CONTACT_SYNC_DISMISS_UPSELL":false,"CONTACT_SYNC_DISMISS_FRIENDS_UPSELL":true}""")
        rejected(notDismissed)
        LocalSettingsSnapshot.validate(notDismissed.copy(booleans = notDismissed.booleans - CONTACT_UPSELL_PREF))
        LocalSettingsSnapshot.validate(notDismissed.copy(booleans = notDismissed.booleans + (CONTACT_UPSELL_PREF to false)))
    }

    @Test
    fun strictJsonRejectsCoercionsDuplicatesTrailingDataAndUnknownEnums() {
        for (key in fixture().strings.keys) {
            for (value in listOf("null", "true", "0", "", " ")) {
                rejected(changed(key.removePrefix("local:"), value))
            }
        }
        for (value in listOf("GIF", "\"gif\"", "\"UNKNOWN\"", "0", "[]", "\"EMOJI\" {}")) {
            rejected(changed(TAB, value))
        }
        for (value in listOf("{}", "[]", "\"camera\\npath\"", "\"\\uD800\"")) {
            rejected(changed(CAMERA, value))
        }
        for (value in listOf(
            """{"experiment":1,"experiment":2}""", """{"experiment":"1"}""",
            """{"experiment":1.0}""", """{"experiment":1e2}""", """{"experiment":null}""",
            """{"experiment":2147483648}""", """{"experiment":-2147483649}""",
            """{"":1}""", """{"with space":1}""", "{experiment:1}", "{'experiment':1}",
            "/* comment */{}", "{} {}", "{\"experiment\":01}",
        )) rejected(changed(EXPERIMENTS, value))
    }

    @Test
    fun countryAndContactObjectsRequireExactMembersAndTypes() {
        val country = fixture().strings.getValue("local:$COUNTRY")
        for (value in listOf(
            "{}", "[]", country.replace("\"name\":\"United States\",", ""),
            country.replace("\"US\"", "null"), country.replace("\"US\"", "12"),
            country.replace("\"US\"", "\"us\""), country.replace("\"US\"", "\"\""),
            country.replace("\"+1\"", "\"1\""), country.replace("\"+1\"", "\"+1x\""),
            country.dropLast(1) + ",\"extra\":\"value\"}",
            country.replace("\"alpha2\":\"US\"", "\"alpha2\":\"US\",\"alpha2\":\"GB\""),
        )) rejected(changed(COUNTRY, value))
        val contacts = fixture().strings.getValue("local:$CONTACTS")
        for (value in listOf(
            "{}", "[]", contacts.replace("true", "\"true\""), contacts.replace("false", "null"),
            contacts.replace("CONTACT_SYNC_DISMISS_UPSELL", "CONTACT_SYNC_LAST_UPLOAD_TIME"),
            contacts.dropLast(1) + ",\"unknown\":true}",
        )) rejected(changed(CONTACTS, value))
    }

    @Test
    fun idsAreExactBoundedIntegersAndCollectionsAreBounded() {
        for (key in listOf(HIDE_MUTED, VOICE, FOLDERS)) {
            for (value in listOf("{}", "[\"1\"]", "[true]", "[null]", "[1.0]", "[1e2]", "[1,1]",
                "[9223372036854775808]", "[-9223372036854775809]", "[01]", "[-0]")) {
                rejected(changed(key, value))
            }
        }
        rejected(changed(VOICE, "[0]"))
        rejected(changed(VOICE, "[-1]"))
        rejected(changed(HIDE_MUTED, "[-1]"))
        for (value in listOf("[]", """{"01":[2]}""", """{"-1":[2]}""", """{"1":[0]}""",
            """{"1":[2,2]}""", """{"1":[2],"1":[3]}""", """{"1":["2"]}""")) {
            rejected(changed(CATEGORIES, value))
        }
        LocalSettingsSnapshot.validate(changed(VOICE, (1..4096).joinToString(",", "[", "]")))
        rejected(changed(VOICE, (1..4097).joinToString(",", "[", "]")))
        LocalSettingsSnapshot.validate(changed(EXPERIMENTS, (1..1000).joinToString(",", "{", "}") { "\"e$it\":1" }))
        rejected(changed(EXPERIMENTS, (1..1001).joinToString(",", "{", "}") { "\"e$it\":1" }))
        val categories = (1..2049).joinToString(",", "[", "]")
        rejected(changed(CATEGORIES, """{"1":$categories,"2":$categories}"""))
        rejected(changed(CAMERA, "\"${"a".repeat(1025)}\""))
        rejected(changed(CAMERA, "\"${"a".repeat(256 * 1024)}\""))
    }

    private fun changed(key: String, value: String, payload: Payload = fixture()): Payload =
        payload.copy(strings = payload.strings + ("local:$key" to value))

    private fun rejected(payload: Payload) {
        val error = assertThrows(IllegalArgumentException::class.java) { LocalSettingsSnapshot.validate(payload) }
        assertEquals("Invalid local settings snapshot", error.message)
        assertNull(error.cause)
    }

    internal companion object {
        fun fixture() = Payload(
            booleans = mapOf(
                "local:CACHE_KEY_ACCESSIBILITY_REDUCED_MOTION_ENABLED" to true,
                "local:CACHE_KEY_ACCESSIBILITY_SYNC_PROFILE_THEME_WITH_USER_THEME" to false,
                "local:CACHE_KEY_ACCESSIBILITY_DISPLAY_NAME_STYLES_ENABLED" to true,
                "local:guild_scheduled_events_header_dismissed" to false,
                "local:CACHE_KEY_STICKER_SUGGESTIONS" to true,
                CONTACT_UPSELL_PREF to true,
            ),
            strings = mapOf(
                "local:$NOTIFICATIONS" to """{"isEnabled":true,"isWake":false,"isDisableBlink":false,"isDisableSound":true,"isDisableVibrate":false}""",
                "local:$EXPERIMENTS" to """{"2026_local_test":-1}""",
                "local:$CAMERA" to "\"camera-0\"",
                "local:$COUNTRY" to """{"name":"United States","alpha2":"US","phoneCountryCode":"+1"}""",
                "local:$HIDE_MUTED" to "[1,2]",
                "local:$CATEGORIES" to """{"1":[2,3]}""",
                "local:$VOICE" to "[4,5]",
                "local:$FOLDERS" to "[-6,7]",
                "local:$TAB" to "\"GIF\"",
                "local:$CONTACTS" to """{"CONTACT_SYNC_DISMISS_UPSELL":true,"CONTACT_SYNC_DISMISS_FRIENDS_UPSELL":false}""",
            ),
        )

        const val NOTIFICATIONS = "STORE_NOTIFICATIONS_SETTINGS_V2"
        const val EXPERIMENTS = "EXPERIMENT_OVERRIDES_CACHE_KEY"
        const val CAMERA = "PREFERRED_VIDEO_INPUT_DEVICE_GUID"
        const val COUNTRY = "CACHE_KEY_PHONE_COUNTRY_CODE_V2"
        const val HIDE_MUTED = "STORE_SHOW_HIDE_MUTED_CHANNELS_V2"
        const val CATEGORIES = "STORE_COLLAPSED_CATEGORIES_V2"
        const val VOICE = "STORE_COLLAPSED_VOICE_CHANNELS"
        const val FOLDERS = "CACHE_KEY_OPEN_FOLDERS"
        const val TAB = "CACHE_KEY_SELECTED_EXPRESSION_TRAY_TAB"
        const val CONTACTS = "CONTACT_SYNC_DISMISS_STATE"
        const val ANIMATED_EMOJIS = "STORE_SETTINGS_ALLOW_ANIMATED_EMOJIS"
        const val AUTO_PLAY_GIFS = "STORE_SETTINGS_AUTO_PLAY_GIFS"
        const val STICKER_ANIMATION = "CACHE_KEY_STICKER_ANIMATION_SETTINGS_V1"
        const val SYNC_TEXT_PREF = "prefs:default/CACHE_KEY_SYNC_TEXT_AND_IMAGES"
        const val CONTACT_UPSELL_PREF = "prefs:durable/CONTACT_SYNC_DISMISS_UPSELL"
    }
}
