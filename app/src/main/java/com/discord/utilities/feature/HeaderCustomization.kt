package com.discord.utilities.feature

import androidx.annotation.StringRes
import com.discord.R
import com.discord.utilities.cache.SharedPreferencesProvider

object HeaderCustomization {
    enum class Surface(val key: String) {
        DM("header_customization_dm_layout"),
        CHANNEL("header_customization_channel_layout"),
    }

    enum class Action(@param:StringRes val label: Int) {
        VOICE(R.string.start_call),
        VIDEO(R.string.start_video_call),
        SEARCH(R.string.search),
        MEMBERS(R.string.members),
        PINS(R.string.pins),
    }

    enum class Identity {
        AVATAR,
        TITLE,
        STATUS,
    }

    data class Entry(val action: Action, val visible: Boolean = true)

    private val dmActions = Action.entries
    private val channelActions = listOf(Action.SEARCH, Action.MEMBERS, Action.PINS)

    fun get(surface: Surface): List<Entry> = decode(
        surface,
        SharedPreferencesProvider.get().getString(surface.key, null),
    )

    fun save(surface: Surface, entries: List<Entry>) {
        SharedPreferencesProvider.get().edit().putString(surface.key, encode(entries)).apply()
    }

    fun actions(surface: Surface) = if (surface == Surface.DM) dmActions else channelActions

    fun identityVisible(surface: Surface, identity: Identity) =
        SharedPreferencesProvider.get().getBoolean(identityKey(surface, identity), true)

    fun setIdentityVisible(surface: Surface, identity: Identity, visible: Boolean) {
        SharedPreferencesProvider.get().edit().putBoolean(identityKey(surface, identity), visible).apply()
    }

    private fun identityKey(surface: Surface, identity: Identity) =
        "header_customization_${surface.name.lowercase()}_${identity.name.lowercase()}"

    internal fun encode(entries: List<Entry>) = entries.joinToString(",") {
        "${it.action.name}:${it.visible}"
    }

    internal fun decode(surface: Surface, value: String?): List<Entry> {
        val actions = actions(surface)
        val parsed = value.orEmpty().split(',').mapNotNull { item ->
            val parts = item.split(':', limit = 2)
            val action = parts.getOrNull(0)?.let { runCatching { Action.valueOf(it) }.getOrNull() }
                ?: return@mapNotNull null
            val visible = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: return@mapNotNull null
            Entry(action, visible)
        }.filter { it.action in actions }.distinctBy(Entry::action)
        return parsed + actions.filter { action -> parsed.none { it.action == action } }.map(::Entry)
    }
}
