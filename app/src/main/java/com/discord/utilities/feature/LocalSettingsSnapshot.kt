package com.discord.utilities.feature

import com.discord.models.notification.NotificationSettingsV2
import com.discord.models.phone.PhoneCountryCode
import com.discord.utilities.feature.ManticordSettingsBackupCodec.Payload
import com.discord.utilities.persister.Persister
import com.discord.widgets.chat.input.expression.ExpressionTrayTab
import com.google.gson.Gson
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Typed local settings only. Restore before any store or notification-client Persister is created. */
object LocalSettingsSnapshot {
    private const val PREFIX = "local:"
    private const val NOTIFICATIONS = "STORE_NOTIFICATIONS_SETTINGS_V2"
    private const val EXPERIMENTS = "EXPERIMENT_OVERRIDES_CACHE_KEY"
    private const val CAMERA = "PREFERRED_VIDEO_INPUT_DEVICE_GUID"
    private const val COUNTRY = "CACHE_KEY_PHONE_COUNTRY_CODE_V2"
    private const val HIDE_MUTED = "STORE_SHOW_HIDE_MUTED_CHANNELS_V2"
    private const val CATEGORIES = "STORE_COLLAPSED_CATEGORIES_V2"
    private const val VOICE = "STORE_COLLAPSED_VOICE_CHANNELS"
    private const val FOLDERS = "CACHE_KEY_OPEN_FOLDERS"
    private const val TAB = "CACHE_KEY_SELECTED_EXPRESSION_TRAY_TAB"
    private const val CONTACTS = "CONTACT_SYNC_DISMISS_STATE"
    private const val HEADER = "guild_scheduled_events_header_dismissed"
    private const val STICKER_SUGGESTIONS = "CACHE_KEY_STICKER_SUGGESTIONS"
    private const val ANIMATED_EMOJIS = "STORE_SETTINGS_ALLOW_ANIMATED_EMOJIS"
    private const val AUTO_PLAY_GIFS = "STORE_SETTINGS_AUTO_PLAY_GIFS"
    private const val STICKER_ANIMATION = "CACHE_KEY_STICKER_ANIMATION_SETTINGS_V1"
    private const val SYNC_TEXT_PREF = "prefs:default/CACHE_KEY_SYNC_TEXT_AND_IMAGES"
    private const val CONTACT_UPSELL = "CONTACT_SYNC_DISMISS_UPSELL"
    private const val CONTACT_UPSELL_PREF = "prefs:durable/CONTACT_SYNC_DISMISS_UPSELL"
    private const val MAX_ITEMS = 4096
    private const val MAX_MAP_ENTRIES = 1000
    private const val MAX_JSON_CHARS = 256 * 1024
    private const val INVALID = "Invalid local settings snapshot"

    private val booleanDefaults = linkedMapOf(
        "CACHE_KEY_ACCESSIBILITY_REDUCED_MOTION_ENABLED" to false,
        "CACHE_KEY_ACCESSIBILITY_SYNC_PROFILE_THEME_WITH_USER_THEME" to false,
        "CACHE_KEY_ACCESSIBILITY_DISPLAY_NAME_STYLES_ENABLED" to true,
        HEADER to false,
        STICKER_SUGGESTIONS to true,
    )
    private val animationBooleanDefaults = mapOf(ANIMATED_EMOJIS to true, AUTO_PLAY_GIFS to true)
    private val stringKeys = setOf(NOTIFICATIONS, EXPERIMENTS, CAMERA, COUNTRY, HIDE_MUTED,
        CATEGORIES, VOICE, FOLDERS, TAB, CONTACTS)
    private val notificationKeys = setOf("isEnabled", "isWake", "isDisableBlink", "isDisableSound", "isDisableVibrate")
    private val contactKeys = setOf(CONTACT_UPSELL, "CONTACT_SYNC_DISMISS_FRIENDS_UPSELL")
    private val deviceKeys = (booleanDefaults.keys - setOf(HEADER, STICKER_SUGGESTIONS)) +
        setOf(NOTIFICATIONS, EXPERIMENTS, COUNTRY)
    private val gson = Gson()
    internal val persistedKeys = booleanDefaults.keys + animationBooleanDefaults.keys + stringKeys + STICKER_ANIMATION

    /**
     * Returns only local keys, using the supplied preference snapshot for sync and dismissal state.
     * Caller must finish store dispatches and successfully flush [persistedKeys] first.
     */
    fun capture(clientPreferences: Payload): Payload {
        val localAnimations = clientPreferences.booleans[SYNC_TEXT_PREF] == false
        val defaults = if (localAnimations) booleanDefaults + animationBooleanDefaults else booleanDefaults
        val notifications = preference(NOTIFICATIONS, NotificationSettingsV2()).get()
        val contacts = preference<Map<String, Boolean>>(CONTACTS, emptyMap()).get()
        val strings = linkedMapOf(
            NOTIFICATIONS to gson.toJson(mapOf(
                "isEnabled" to notifications.isEnabled,
                "isWake" to notifications.isWake,
                "isDisableBlink" to notifications.isDisableBlink,
                "isDisableSound" to notifications.isDisableSound,
                "isDisableVibrate" to notifications.isDisableVibrate,
            )),
            EXPERIMENTS to gson.toJson(preference<Map<String, Int>>(EXPERIMENTS, emptyMap()).get()),
            CAMERA to gson.toJson(preference(CAMERA, "").get()),
            COUNTRY to gson.toJson(preference(COUNTRY, PhoneCountryCode.MISSING_COUNTRY_CODE).get()),
            HIDE_MUTED to gson.toJson(preference<Set<Long>>(HIDE_MUTED, emptySet()).get().sorted()),
            CATEGORIES to gson.toJson(preference<Map<Long, Set<Long>>>(CATEGORIES, emptyMap()).get()
                .toSortedMap().mapValues { it.value.sorted() }),
            VOICE to gson.toJson(preference<Set<Long>>(VOICE, emptySet()).get().sorted()),
            FOLDERS to gson.toJson(preference(FOLDERS, HashSet<Long>()).get().sorted()),
            TAB to gson.toJson(preference(TAB, ExpressionTrayTab.EMOJI).get().name),
            CONTACTS to gson.toJson(contactKeys.associateWith {
                if (it == CONTACT_UPSELL) clientPreferences.booleans[CONTACT_UPSELL_PREF] ?: false
                else contacts[it] ?: false
            }),
        )
        if (localAnimations) strings[STICKER_ANIMATION] = gson.toJson(preference(STICKER_ANIMATION, 0).get())
        return Payload(
            defaults.entries.associate { (key, default) ->
                PREFIX + key to preference(key, default).get()
            },
            strings.mapKeys { PREFIX + it.key },
        ).also {
            validate(Payload(clientPreferences.booleans + it.booleans, clientPreferences.strings + it.strings))
        }
    }

    /** Requires the full combined payload, including the source sync and durable-dismissal preferences. */
    fun validate(payload: Payload) {
        decode(payload)
    }

    /** No hardware, account API or hot-store calls. Caller must retain staging until this succeeds. */
    fun restore(payload: Payload) {
        val values = decode(payload)
        val notifications = preference(NOTIFICATIONS, NotificationSettingsV2())
        val destinationInApp = notifications.get().isEnabledInApp
        val written = ArrayList<Persister<*>>()
        fun <T : Any> write(key: String, default: T, value: T) {
            preference(key, default).also {
                written += it
                it.set(value, persist = true)
            }
        }
        values.booleans.forEach { (key, value) ->
            write(key, booleanDefaults[key] ?: animationBooleanDefaults.getValue(key), value)
        }
        notifications.set(values.notifications.copy(isEnabledInApp = destinationInApp), persist = true)
        written += notifications
        write<Map<String, Int>>(EXPERIMENTS, emptyMap(), values.experiments)
        write(CAMERA, "", values.camera)
        write(COUNTRY, PhoneCountryCode.MISSING_COUNTRY_CODE, values.country)
        write<Set<Long>>(HIDE_MUTED, emptySet(), values.hideMuted)
        write<Map<Long, Set<Long>>>(CATEGORIES, emptyMap(), values.categories)
        write<Set<Long>>(VOICE, emptySet(), values.voice)
        write(FOLDERS, HashSet<Long>(), HashSet(values.folders))
        write(TAB, ExpressionTrayTab.EMOJI, values.tab)
        write<Map<String, Boolean>>(CONTACTS, emptyMap(), values.contacts)
        values.stickerAnimation?.let { write(STICKER_ANIMATION, 0, it) }
        // set(persist=true) logs rather than throws on I/O failure. Retry/check while handles are retained.
        written.forEach { check(it.persistNow()) { "Unable to persist local settings" } }
    }

    private fun <T : Any> preference(key: String, default: T) =
        Persister(key, default, preload = false, deviceScoped = key in deviceKeys)

    private class Values(
        val booleans: Map<String, Boolean>,
        val notifications: NotificationSettingsV2,
        val experiments: Map<String, Int>,
        val camera: String,
        val country: PhoneCountryCode,
        val hideMuted: Set<Long>,
        val categories: Map<Long, Set<Long>>,
        val voice: Set<Long>,
        val folders: Set<Long>,
        val tab: ExpressionTrayTab,
        val contacts: Map<String, Boolean>,
        val stickerAnimation: Int?,
    )

    private fun decode(payload: Payload): Values = try {
        val booleans = payload.booleans.filterKeys { it.startsWith(PREFIX) }.mapKeys { it.key.removePrefix(PREFIX) }
        val strings = payload.strings.filterKeys { it.startsWith(PREFIX) }.mapKeys { it.key.removePrefix(PREFIX) }
        val localAnimations = payload.booleans[SYNC_TEXT_PREF] == false
        val expectedBooleans = if (localAnimations) booleanDefaults.keys + animationBooleanDefaults.keys else booleanDefaults.keys
        val expectedStrings = if (localAnimations) stringKeys + STICKER_ANIMATION else stringKeys
        require(booleans.keys == expectedBooleans && strings.keys == expectedStrings)
        require(strings.values.sumOf { it.length.toLong() } <= 1024 * 1024)

        fun <T> json(key: String, read: JsonReader.() -> T): T {
            val text = strings.getValue(key)
            require(text.length <= MAX_JSON_CHARS)
            return JsonReader(StringReader(text)).use { reader ->
                reader.strictness = Strictness.STRICT
                val value = reader.read()
                require(reader.peek() == JsonToken.END_DOCUMENT)
                value
            }
        }

        val notificationFields = json(NOTIFICATIONS) { objectValues(5) { boolean() } }
        require(notificationFields.keys == notificationKeys)
        val experiments = json(EXPERIMENTS) {
            objectValues(MAX_MAP_ENTRIES) { name ->
                require(name.matches(Regex("[A-Za-z0-9_.-]{1,256}")))
                number(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            }
        }
        val camera = json(CAMERA) { text(1024) }
        val country = json(COUNTRY) { objectValues(3) { text(128) } }
        require(country.keys == setOf("name", "alpha2", "phoneCountryCode"))
        val name = country.getValue("name")
        val alpha2 = country.getValue("alpha2")
        val dialing = country.getValue("phoneCountryCode")
        require((name.isEmpty() && alpha2.isEmpty() && dialing.isEmpty()) ||
            (name.isNotBlank() && alpha2.matches(Regex("[A-Z]{2}")) &&
                dialing.matches(Regex("\\+[0-9]{1,4}( [0-9]{1,4}){0,2}"))))
        val hideMuted = json(HIDE_MUTED) { ids(0L) }
        var categoryCount = 0
        val categories = json(CATEGORIES) {
            objectValues(MAX_MAP_ENTRIES) { guild ->
                integer(guild, 1L, Long.MAX_VALUE)
                ids(1L).also {
                    categoryCount += it.size
                    require(categoryCount <= MAX_ITEMS)
                }
            }.mapKeys { it.key.toLong() }
        }
        val voice = json(VOICE) { ids(1L) }
        // Folder creation uses Random.nextLong(), not a positive Discord snowflake.
        val folders = json(FOLDERS) { ids(Long.MIN_VALUE) }
        val tab = json(TAB) { ExpressionTrayTab.valueOf(text(16)) }
        val contacts = json(CONTACTS) { objectValues(2) { boolean() } }
        require(contacts.keys == contactKeys)
        require(contacts.getValue(CONTACT_UPSELL) == (payload.booleans[CONTACT_UPSELL_PREF] ?: false))
        val stickerAnimation = if (localAnimations) json(STICKER_ANIMATION) { number(0L, 2L).toInt() } else null

        Values(
            booleans,
            NotificationSettingsV2(
                isEnabled = notificationFields.getValue("isEnabled"),
                isWake = notificationFields.getValue("isWake"),
                isDisableBlink = notificationFields.getValue("isDisableBlink"),
                isDisableSound = notificationFields.getValue("isDisableSound"),
                isDisableVibrate = notificationFields.getValue("isDisableVibrate"),
            ),
            experiments, camera, PhoneCountryCode(name, alpha2, dialing), hideMuted, categories,
            voice, folders, tab, contacts, stickerAnimation,
        )
    } catch (_: Exception) {
        // Gson/enum diagnostics can embed remote values; never attach their messages or causes.
        throw IllegalArgumentException(INVALID)
    }

    private fun JsonReader.boolean(): Boolean {
        require(peek() == JsonToken.BOOLEAN)
        return nextBoolean()
    }

    private fun JsonReader.text(max: Int): String {
        require(peek() == JsonToken.STRING)
        return nextString().also {
            require(it.length <= max && it.none(Char::isISOControl) && Charsets.UTF_8.newEncoder().canEncode(it))
        }
    }

    private fun integer(text: String, min: Long, max: Long): Long {
        val value = text.toLongOrNull()
        require(value != null && value.toString() == text && value in min..max)
        return value
    }

    private fun JsonReader.number(min: Long, max: Long): Long {
        require(peek() == JsonToken.NUMBER)
        return integer(nextString(), min, max)
    }

    private fun JsonReader.ids(min: Long): Set<Long> {
        beginArray()
        val result = linkedSetOf<Long>()
        while (hasNext()) {
            require(result.size < MAX_ITEMS)
            require(result.add(number(min, Long.MAX_VALUE)))
        }
        endArray()
        return result
    }

    private fun <T> JsonReader.objectValues(max: Int, value: JsonReader.(String) -> T): Map<String, T> {
        beginObject()
        val result = linkedMapOf<String, T>()
        while (hasNext()) {
            require(result.size < max)
            val key = nextName()
            require(key.length <= 256 && key !in result)
            result[key] = value(key)
        }
        endObject()
        return result
    }
}
