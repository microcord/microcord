package com.discord.utilities.feature

import androidx.annotation.StringRes
import com.discord.R
import com.discord.utilities.cache.SharedPreferencesProvider
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object PluginSurfaceCustomization {
    enum class Surface {
        BOTTOM_TABS,
        PROFILE,
        PROFILE_TABS,
        COMPOSER,
        MESSAGE_HEADER,
        MESSAGE_CONTENT,
        MESSAGE_STANDALONE,
        ;

        val key: String get() = ClientSettings.pluginLayout(this).key
    }

    data class Entry(val key: String, val visible: Boolean = true)

    enum class ProfileElement(@param:StringRes val label: Int, val priority: Int) {
        ABOUT_ME(R.string.user_profile_bio, 800),
        MEMBER_SINCE(R.string.user_profile_member_since, 700),
        FRIENDS_SINCE(R.string.user_profile_friends_since, 600),
        VOICE_SETTINGS(R.string.voice_settings, 500),
        RICH_PRESENCE(R.string.activity, 400),
        GUILD(R.string.roles, 300),
        CONNECTIONS(R.string.connections, 200),
        NOTE(R.string.note, 100),
    }

    private data class Cached(val knownKeys: List<String>, val entries: List<Entry>)
    private val cache = ConcurrentHashMap<Surface, Cached>()

    fun get(surface: Surface, knownKeys: List<String>): List<Entry> {
        cache[surface]?.takeIf { it.knownKeys == knownKeys }?.let { return it.entries }
        return decode(ClientSettings.pluginLayout(surface).get(SharedPreferencesProvider.get()), knownKeys).also {
            cache[surface] = Cached(knownKeys.toList(), it)
        }
    }

    internal fun decode(value: String?, knownKeys: List<String>): List<Entry> {
        val known = knownKeys.toSet()
        val parsed = value.orEmpty().split(',').mapNotNull { encoded ->
                val parts = encoded.split(':', limit = 2)
                val key = parts.getOrNull(0)?.let(::decode)?.takeIf(known::contains)
                    ?: return@mapNotNull null
                val visible = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: return@mapNotNull null
                Entry(key, visible)
            }.distinctBy(Entry::key)
        return parsed + knownKeys.filter { key -> parsed.none { it.key == key } }.map(::Entry)
    }

    fun save(surface: Surface, entries: List<Entry>) {
        val value = entries.joinToString(",") { "${encode(it.key)}:${it.visible}" }
        ClientSettings.pluginLayout(surface).set(SharedPreferencesProvider.get(), value)
        cache.remove(surface)
    }

    fun pluginKey(owner: String, contributionId: String) = "plugin:$owner:$contributionId"
    fun builtinTabKey(tab: String) = "builtin:$tab"
    fun builtinProfileKey(element: ProfileElement) = "builtin:${element.name}"
    fun builtinProfileTabKey(tab: String) = "builtin:$tab"
    fun isRequiredBottomTab(key: String) = key == builtinTabKey("HOME") || key == builtinTabKey("SETTINGS")
    fun isRequiredProfileTab(key: String) = key == builtinProfileTabKey("MAIN")
    fun builtinTabPriority(tab: String) = when (tab) {
        "HOME" -> 500
        "FRIENDS" -> 400
        "SEARCH" -> 300
        "MENTIONS" -> 200
        "SETTINGS" -> 100
        else -> 0
    }

    fun defaultOrder(entries: List<Pair<String, Int>>): List<String> = entries
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        .map(Pair<String, Int>::first)

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
