package com.discord.widgets.chat.input.autocomplete.sources

import com.discord.api.channel.Channel
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.hasModernPermission
import com.discord.stores.StoreApplicationCommands
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreUser
import com.discord.utilities.rx.computationLatest
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandAutocompletable
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.AutocompletableComparator
import com.discord.widgets.chat.input.autocomplete.LeadingIdentifier
import java.util.TreeSet
import rx.Observable

/** Slash-command candidates available in one channel. */
class ApplicationCommandsAutocompletableSource(
    private val storeUsers: StoreUser,
    private val storeGuilds: StoreGuilds,
    private val storeApplicationCommands: StoreApplicationCommands,
    private val storePermissions: StorePermissions,
) {
    fun observeApplicationCommandAutocompletables(
        channel: Channel,
    ): Observable<Map<LeadingIdentifier, TreeSet<Autocompletable>>> =
        Observable.combineLatest(
            storeUsers.observeMeId().computationLatest(),
            storeGuilds.observeComputed(channel.guildId).computationLatest(),
            storeApplicationCommands.observeGuildApplications(channel.id).computationLatest(),
            storeApplicationCommands.observeQueryCommands(channel.id).computationLatest(),
            storePermissions.observePermissionsForChannel(channel.id).computationLatest(),
        ) { meId, members, applications, commands, memberPermissions ->
            createAutocompletables(
                meId = meId,
                roleIds = members[meId]?.getRoles().orEmpty(),
                applications = applications,
                commands = commands,
                memberPermissions = memberPermissions,
                isGuildOwner = storeGuilds.getGuild(channel.guildId)?.ownerId == meId,
                channelId = channel.id,
                guildId = channel.guildId,
            )
        }.computationLatest().distinctUntilChanged()

    companion object {
        internal fun createAutocompletables(
            meId: Long,
            roleIds: List<Long>,
            applications: List<Application>,
            commands: List<ApplicationCommand>,
            memberPermissions: Long? = null,
            isGuildOwner: Boolean = false,
            channelId: Long? = null,
            guildId: Long? = null,
        ): Map<LeadingIdentifier, TreeSet<Autocompletable>> {
            val applicationsById = applications.associateBy(Application::id)
            val result = TreeSet<Autocompletable>(AutocompletableComparator())
            commands.forEach { command ->
                result += ApplicationCommandAutocompletable(
                    application = applicationsById[command.applicationId],
                    command = command,
                    hasPermissionToUse = command.hasModernPermission(
                        meId,
                        roleIds,
                        memberPermissions,
                        isGuildOwner,
                        channelId,
                        guildId,
                    ),
                    showAvatar = true,
                )
            }
            return mapOf(LeadingIdentifier.APP_COMMAND to result)
        }
    }
}
