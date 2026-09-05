package com.discord.utilities.feature

import android.content.Context
import android.content.SharedPreferences
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.feature.ClientSettings.BackupPolicy
import com.discord.utilities.feature.ClientSettings.Scope
import com.discord.utilities.feature.ClientSettings.Type
import com.discord.utilities.feature.ClientSettings.backupType
import com.discord.utilities.feature.ManticordSettingsBackupCodec.Payload
import com.google.gson.JsonArray
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Local SharedPreferences only; initialize SharedPreferencesProvider before using this helper.
 * Restore ONLY during cold startup, before stores, plugins or customization caches are created.
 * Absence resets an allowed setting to its owner's default, not to the destination's old value.
 *
 * plugin-api.md's Storage contract forbids credentials in ordinary plugin settings. Secret-like
 * names are excluded too, but arbitrary string secrets under innocuous names cannot be inferred.
 * No plugin IDs are split on dots: the complete stored key is the identity.
 */
object ClientPreferenceSnapshot {
    private const val PREFIX = "prefs:"
    private const val INVALID = "Invalid client preference snapshot"
    private const val MAX_ENTRIES = 4096
    private const val MAX_KEY_CHARS = 1024
    private const val MAX_VALUE_CHARS = 256 * 1024
    private const val MAX_TOTAL_CHARS = 1024 * 1024
    private val textSync = ClientSettings.syncTextAndImages.key
    private val scopes = Scope.entries.mapTo(linkedSetOf()) { it.id }
    private val localMedia = ClientSettings.registered
        .filter { it.scope == Scope.DEFAULT && it.backupPolicy == BackupPolicy.WHEN_TEXT_SYNC_DISABLED }
        .mapTo(hashSetOf()) { it.key }
    private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    fun capture(context: Context): Payload = capture(preferences(context))

    fun validate(payload: Payload) {
        decode(payload)
    }

    fun restore(context: Context, payload: Payload) {
        val decoded = decode(payload)
        write(preferences(context), decoded)
    }

    private fun preferences(context: Context): Map<String, SharedPreferences> = linkedMapOf(
        Scope.DEFAULT.id to SharedPreferencesProvider.get(),
        Scope.DURABLE.id to SharedPreferencesProvider.getPrefsSessionDurable(),
        Scope.PLUGINS.id to context.getSharedPreferences("plugin_settings", Context.MODE_PRIVATE),
        Scope.CONTENT.id to context.getSharedPreferences("channel_content", Context.MODE_PRIVATE),
        Scope.ENGINE.id to context.getSharedPreferences("MediaEngine", Context.MODE_PRIVATE),
    )

    /** Same path as the Context API, without Android construction or process-global test state. */
    internal fun capture(preferences: Map<String, SharedPreferences>): Payload = try {
        require(preferences.keys == scopes)
        val stored = preferences.mapValues { it.value.all.toMap() }
        val booleans = linkedMapOf<String, Boolean>()
        val strings = linkedMapOf<String, String>()
        stored.forEach { (scope, values) ->
            values.forEach entry@{ (key, value) ->
                val type = backupType(scope, key) ?: return@entry
                if (scope == Scope.DEFAULT.id && key in localMedia && values[textSync] != false) return@entry
                val address = "$PREFIX$scope/$key"
                require(booleans.size + strings.size < MAX_ENTRIES)
                when (type) {
                    Type.BOOLEAN -> { require(value is Boolean); booleans[address] = value }
                    Type.STRING -> { require(value is String); strings[address] = value }
                    Type.INT -> { require(value is Int); strings[address] = value.toString() }
                    Type.FLOAT -> { require(value is Float); strings[address] = value.toString() }
                    Type.STRING_SET -> strings[address] = encodeSet(value)
                    Type.PLUGIN -> when (value) {
                        is Boolean -> booleans[address] = value
                        is String -> strings[address] = value
                        else -> throw IllegalArgumentException()
                    }
                }
            }
        }
        Payload(booleans, strings).also(::validate)
    } catch (_: Exception) {
        throw IllegalArgumentException(INVALID)
    }

    internal fun restore(preferences: Map<String, SharedPreferences>, payload: Payload) {
        write(preferences, decode(payload))
    }

    // Any is confined to SharedPreferences' heterogeneous boundary; the registry checks every value.
    private fun decode(payload: Payload): Map<String, Map<String, Any>> = try {
        val result = scopes.associateWith { linkedMapOf<String, Any>() }
        var count = 0
        var characters = 0L
        for (map in listOf(payload.booleans as Map<*, *>, payload.strings as Map<*, *>)) {
            for ((address, value) in map) {
                require(address is String)
                if (!address.startsWith(PREFIX)) continue // local: belongs to LocalSettingsSnapshot.
                require(++count <= MAX_ENTRIES)
                text(address, MAX_KEY_CHARS)
                require(address.none(Char::isISOControl))
                val slash = address.indexOf('/', PREFIX.length)
                require(slash > PREFIX.length && slash < address.lastIndex)
                val scope = address.substring(PREFIX.length, slash)
                val key = address.substring(slash + 1)
                val destination = requireNotNull(result[scope])
                require(key !in destination)
                val type = requireNotNull(backupType(scope, key))
                characters += address.length
                if (map === payload.booleans) {
                    require(value is Boolean && (type == Type.BOOLEAN || type == Type.PLUGIN))
                    destination[key] = value
                } else {
                    require(value is String && type != Type.BOOLEAN)
                    text(value, MAX_VALUE_CHARS)
                    characters += value.length
                    destination[key] = when (type) {
                        Type.INT -> integer(key, value)
                        Type.FLOAT -> floating(key, value)
                        Type.STRING_SET -> decodeSet(key, value)
                        Type.STRING -> value.also { validateString(key, it) }
                        Type.PLUGIN -> value
                        Type.BOOLEAN -> throw IllegalArgumentException()
                    }
                }
                require(characters <= MAX_TOTAL_CHARS)
            }
        }
        val defaults = result.getValue(Scope.DEFAULT.id)
        require(defaults.keys.none(localMedia::contains) || defaults[textSync] == false)
        result
    } catch (_: Exception) {
        // Parser, enum and numeric diagnostics may contain values. Never propagate their causes.
        throw IllegalArgumentException(INVALID)
    }

    private fun write(preferences: Map<String, SharedPreferences>, decoded: Map<String, Map<String, Any>>) {
        require(preferences.keys == scopes) { INVALID }
        val localMediaEnabled = decoded.getValue(Scope.DEFAULT.id)[textSync] == false
        // Read all removal plans before opening an editor. Never clear a whole preference file.
        val removals = preferences.mapValues { (scope, prefs) ->
            prefs.all.keys.filter { key ->
                backupType(scope, key) != null && (scope != Scope.DEFAULT.id || key !in localMedia || localMediaEnabled)
            }
        }
        scopes.forEach { scope ->
            val values = decoded.getValue(scope)
            val editor = preferences.getValue(scope).edit()
            removals.getValue(scope).forEach(editor::remove)
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST") // decodeSet only returns checked strings.
                        editor.putStringSet(key, value as Set<String>)
                    }
                    else -> error(INVALID)
                }
            }
            // Commit even an empty scope: a failed commit may have updated memory but not disk.
            check(editor.commit()) { "Unable to commit client preference snapshot" }
        }
    }

    private fun text(value: String, maximum: Int) {
        require(value.length <= maximum && Charsets.UTF_8.newEncoder().canEncode(value))
    }

    private fun integer(key: String, text: String): Int {
        val value = requireNotNull(text.toIntOrNull())
        require(value.toString() == text)
        require(when {
            key == "CACHE_KEY_FONT_SCALE" -> value == -1 || value in 80..150
            key == "developer_premium_tier_override" -> value in -1..4
            key == "CACHE_KEY_VOICE_SETTINGS_INPUT_MODE" -> value in 1..2
            key == "CACHE_KEY_VOICE_SETTINGS_NOISE_PROCESSING" -> value in 0..3
            key.startsWith("forum_layout_") -> value == -1 || value in 1..2
            else -> value in -1..1 // Forum sort order and tag matching; -1 is NO_OVERRIDE.
        })
        return value
    }

    private fun floating(key: String, text: String): Float {
        require(decimal.matches(text))
        val value = requireNotNull(text.toFloatOrNull())
        require(value.isFinite())
        require(value != 0f || text.toBigDecimal().signum() == 0) // Reject underflow, not silently zero it.
        require(when (key) {
            "CACHE_KEY_VOICE_SETTINGS_SENSITIVITY" -> value in -100f..0f
            "developer_floating_shortcut_x", "developer_floating_shortcut_y" -> value in 0f..1f
            else -> value >= 0f // Output/user amplitude setters have no maximum; do not clamp to a UI slider.
        })
        return value
    }

    private fun validateString(key: String, value: String) {
        when (key) {
            "CACHE_KEY_LOCALE" -> {
                require(value.isNotEmpty() && value.length <= 255)
                require(Locale.Builder().setLanguageTag(value).build().language.isNotEmpty())
            }
            "CACHE_KEY_THEME" -> require(value in setOf("light", "dark", "darker", "midnight", "pureEvil", "materialYou"))
            "feature_gif_provider" -> require(value in setOf("KLIPY", "TENOR", "GIPHY"))
            else -> {
                if (value.isEmpty()) return // Every customization decoder accepts the default/empty layout.
                val seen = hashSetOf<String>()
                value.split(',').forEach { item ->
                    val parts = item.split(':')
                    require(parts.size == 2 && seen.size < MAX_ENTRIES)
                    val name = parts[0]
                    val identity = if (key.startsWith("plugin_surface_")) {
                        URLDecoder.decode(name, "UTF-8").also {
                            text(it, MAX_KEY_CHARS)
                            require(URLEncoder.encode(it, "UTF-8") == name)
                        }
                    } else name
                    require(identity.isNotEmpty() && seen.add(identity))
                    if (key == "feature_chatbox_customizations_layout") {
                        require(name in setOf("PROFILE", "ATTACHMENT", "APPS", "EXPAND", "EXPRESSION", "SEND", "KEYBOARD"))
                        require(parts[1] in setOf("LEFT", "INSIDE", "INSIDE_END", "RIGHT", "HIDDEN"))
                    } else {
                        require(parts[1].toBooleanStrictOrNull() != null)
                        when (key) {
                            "header_customization_dm_layout" -> require(name in setOf("VOICE", "VIDEO", "SEARCH", "MEMBERS", "PINS"))
                            "header_customization_channel_layout" -> require(name in setOf("SEARCH", "MEMBERS", "PINS"))
                            "message_customization_layout" -> require(name in setOf("CORE", "MEDIA", "EMBEDS", "STICKERS", "COMPONENTS", "CARDS", "REACTIONS", "THREAD"))
                        }
                    }
                }
            }
        }
    }

    private fun encodeSet(value: Any?): String {
        require(value is Set<*> && value.size <= MAX_ENTRIES && value.all { it is String })
        val strings = value.filterIsInstance<String>().sorted()
        require(strings.sumOf { it.length.toLong() } <= MAX_VALUE_CHARS)
        return JsonArray().apply { strings.forEach(::add) }.toString()
    }

    private fun decodeSet(key: String, value: String): Set<String> = JsonReader(StringReader(value)).use { reader ->
        reader.strictness = Strictness.STRICT
        val values = linkedSetOf<String>()
        val users = hashSetOf<Long>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(values.size < MAX_ENTRIES && reader.peek() == JsonToken.STRING)
            val entry = reader.nextString()
            text(entry, MAX_KEY_CHARS)
            require(values.add(entry))
            when (key) {
                "MUTED_USERS_V2", "USER_OUTPUT_VOLUMES_V2" -> {
                    val parts = entry.split(',')
                    require(parts.size == 2 && users.add(id(parts[0])))
                    if (key == "MUTED_USERS_V2") require(parts[1].toBooleanStrictOrNull() != null)
                    else floating(key, parts[1])
                }
                "USER_TRUSTED_DOMAINS_CACHE_KEY" -> {
                    val uri = URI("https://$entry")
                    require(entry.isNotBlank() && uri.rawAuthority == entry && uri.rawUserInfo == null &&
                        uri.port == -1 && uri.rawPath.isEmpty() && uri.rawQuery == null && uri.rawFragment == null)
                }
                else -> id(entry) // ForumUtils' selected tag IDs.
            }
        }
        reader.endArray()
        require(reader.peek() == JsonToken.END_DOCUMENT)
        values
    }

    private fun id(value: String): Long {
        val id = requireNotNull(value.toLongOrNull())
        require(id > 0 && id.toString() == value)
        return id
    }
}
