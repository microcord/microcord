package com.discord.models.commands

import com.discord.api.user.User
import com.discord.api.commands.ApplicationCommandIndexPermissions
import com.discord.models.user.CoreUser
import com.discord.nullserializable.NullSerializable
import com.discord.stores.StoreStream

/**
 * An application as the slash-command picker shows it.
 *
 * Built-in commands (`/shrug`, `/tableflip`, …) are modelled as an application too; they carry
 * [builtIn] and an [iconRes] instead of a CDN [icon].
 */
data class Application(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val iconRes: Int? = null,
    val commandCount: Int,
    val bot: User? = null,
    val builtIn: Boolean = false,
    val permissions: ApplicationCommandIndexPermissions? = null,
    val installationTypes: Set<Int> = emptySet(),
    val botId: Long? = bot?.id,
) {
    companion object {
        fun fromApiApplication(apiApplication: com.discord.api.application.Application): Application =
            Application(
                id = apiApplication.id,
                name = apiApplication.name,
                icon = apiApplication.icon,
                commandCount = 0,
            )
    }
}

/**
 * The application's bot user: the one the command index sent, else the cached user for [botId]
 * (the store fetches unknown ones when the index arrives).
 */
fun Application.botUser(): com.discord.models.user.User? =
    bot?.let(::CoreUser) ?: botId?.let { StoreStream.getUsers().getUsers()[it] }

/** What the command pickers call the app: its bot's username, as the official client does, else its name. */
fun Application.displayName(): String = botUser()?.username ?: name

/** True when the application's bot user has an explicitly set avatar (not the default one). */
fun Application.hasBotAvatar(): Boolean = bot?.avatar is NullSerializable.Value<*>

/** Sorts real applications ahead of the built-in commands, then alphabetically. */
object ModelApplicationComparator : Comparator<Application> {
    override fun compare(a: Application, b: Application): Int {
        val builtIn = (if (a.builtIn) 1 else 0) - (if (b.builtIn) 1 else 0)
        return if (builtIn != 0) builtIn else a.name.compareTo(b.name)
    }
}
