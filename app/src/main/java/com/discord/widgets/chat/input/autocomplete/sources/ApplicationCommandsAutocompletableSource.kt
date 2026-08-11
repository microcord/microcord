package com.discord.widgets.chat.input.autocomplete.sources

import com.discord.api.channel.Channel
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.CommandPermissionContext
import com.discord.models.commands.hasAccess
import com.discord.stores.StoreApplicationCommands
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreUser
import com.discord.utilities.applicationcommands.CommandPermissionContexts
import com.discord.utilities.rx.computationLatest
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandAutocompletable
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.AutocompletableComparator
import com.discord.widgets.chat.input.autocomplete.LeadingIdentifier
import java.util.TreeSet
import rx.Observable

/** Slash-command candidates available in one channel. */
class ApplicationCommandsAutocompletableSource(
    storeUsers: StoreUser,
    storeGuilds: StoreGuilds,
    private val storeApplicationCommands: StoreApplicationCommands,
    storePermissions: StorePermissions,
    storeChannels: StoreChannels,
) {
    private val permissionContexts =
        CommandPermissionContexts(storeUsers, storeGuilds, storeChannels, storePermissions)

    fun observeApplicationCommandAutocompletables(
        channel: Channel,
    ): Observable<Map<LeadingIdentifier, TreeSet<Autocompletable>>> =
        Observable.combineLatest(
            permissionContexts.observe(channel),
            storeApplicationCommands.observeGuildApplications(channel.id).computationLatest(),
            storeApplicationCommands.observeQueryCommands(channel.id).computationLatest(),
        ) { permissionContext, applications, commands ->
            createAutocompletables(permissionContext, applications, commands)
        }.computationLatest().distinctUntilChanged()

    companion object {
        internal fun createAutocompletables(
            permissionContext: CommandPermissionContext,
            applications: List<Application>,
            commands: List<ApplicationCommand>,
        ): Map<LeadingIdentifier, TreeSet<Autocompletable>> {
            val applicationsById = applications.associateBy(Application::id)
            val result = TreeSet<Autocompletable>(AutocompletableComparator())
            commands.forEach { command ->
                val application = applicationsById[command.applicationId]
                result += ApplicationCommandAutocompletable(
                    application = application,
                    command = command,
                    hasPermissionToUse = command.hasAccess(permissionContext, application),
                    showAvatar = true,
                )
            }
            return mapOf(LeadingIdentifier.APP_COMMAND to result)
        }
    }
}
