package com.discord.utilities.feature

import androidx.annotation.StringRes
import com.discord.R
import com.discord.utilities.cache.SharedPreferencesProvider

object HeaderCustomization {
    enum class Surface {
        DM,
        CHANNEL,
        ;

        val key: String get() = ClientSettings.headerLayout(this).key
    }

    enum class Action(@param:StringRes val label: Int) {
        VOICE(R.string.start_call),
        VIDEO(R.string.start_video_call),
        SEARCH(R.string.search),
        MEMBERS(R.string.members),
        PINS(R.string.pins),
    }

    enum class Identity {
        HEADER,
        AVATAR,
        TITLE,
        STATUS,
    }

    data class Entry(val action: Action, val visible: Boolean = true)

    private val dmActions = Action.entries
    private val channelActions = listOf(Action.SEARCH, Action.MEMBERS, Action.PINS)

    fun get(surface: Surface): List<Entry> = decode(
        surface,
        ClientSettings.headerLayout(surface).get(SharedPreferencesProvider.get()),
    )

    fun save(surface: Surface, entries: List<Entry>) {
        ClientSettings.headerLayout(surface).set(SharedPreferencesProvider.get(), encode(entries))
    }

    fun actions(surface: Surface) = if (surface == Surface.DM) dmActions else channelActions

    fun identityVisible(surface: Surface, identity: Identity) =
        ClientSettings.headerIdentity(surface, identity).get(SharedPreferencesProvider.get())

    fun setIdentityVisible(surface: Surface, identity: Identity, visible: Boolean) {
        ClientSettings.headerIdentity(surface, identity).set(SharedPreferencesProvider.get(), visible)
    }

    fun pluginVisible(surface: Surface, owner: String, contributionId: String): Boolean =
        ClientSettings.headerPlugin(surface, owner, contributionId).get(SharedPreferencesProvider.get())

    fun setPluginVisible(surface: Surface, owner: String, contributionId: String, visible: Boolean) {
        ClientSettings.headerPlugin(surface, owner, contributionId).set(SharedPreferencesProvider.get(), visible)
    }

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
