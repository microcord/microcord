package com.discord.widgets.chat.list.sheet

import com.discord.api.channel.Channel
import com.discord.api.commands.ApplicationCommandData
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.ApplicationCommandValue
import com.discord.api.role.GuildRole
import com.discord.app.AppViewModel
import com.discord.models.commands.Application
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreApplicationInteractions
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.rx.ui
import rx.Observable

class WidgetApplicationCommandBottomSheetViewModel(
    val interactionId: Long,
    val messageId: Long,
    val channelId: Long,
    val guildId: Long?,
    val interactionUserId: Long,
    val applicationId: Long,
    val messageNonce: String?,
    storeStateObservable: Observable<StoreState> = observeStores(
        interactionId,
        channelId,
        guildId,
        interactionUserId,
        applicationId,
    ),
) : AppViewModel<WidgetApplicationCommandBottomSheetViewModel.ViewState>(ViewState.Loading) {

    init {
        StoreStream.getInteractions().fetchInteractionDataIfNonExisting(
            interactionId,
            channelId,
            messageId,
            messageNonce,
        )
        storeStateObservable.ui(this).subscribe(::handleStoreState)
    }

    private fun handleStoreState(storeState: StoreState) {
        val interaction = storeState.interactionState
        when (interaction) {
            StoreApplicationInteractions.State.Failure -> updateViewState(ViewState.Failed)
            StoreApplicationInteractions.State.Fetching, null -> updateViewState(ViewState.Loading)
            is StoreApplicationInteractions.State.Loaded -> {
                val mentionedUserIds = interaction.commandOptions.options
                    .orEmpty()
                    .flattenOptions()
                    .filter { it.type == ApplicationCommandType.USER.type }
                    .mapNotNull { it.value.toString().toLongOrNull() }
                    .toSet() + interactionUserId

                if (!storeState.users.keys.containsAll(mentionedUserIds)) {
                    StoreStream.getUsers().getUsers(mentionedUserIds, fetchMissing = true)
                    guildId?.let {
                        StoreStream.getGatewayConnection()
                            .requestGuildMembers(it, userIds = mentionedUserIds.toList())
                    }
                    updateViewState(ViewState.Loading)
                    return
                }

                val usernamesOrNicks = mentionedUserIds.associateWith { userId ->
                    storeState.guildMembers?.get(userId)?.nick
                        ?: storeState.users[userId]?.username.orEmpty()
                }
                updateViewState(
                    ViewState.Loaded(
                        id = interactionId,
                        user = storeState.users[interactionUserId],
                        interactionUser = storeState.guildMembers?.get(interactionUserId),
                        application = storeState.application,
                        applicationCommandData = interaction.commandOptions,
                        guildId = guildId,
                        channelId = channelId,
                        guildMembers = storeState.guildMembers,
                        guildRoles = storeState.guildRoles,
                        channels = storeState.channels,
                        usernamesOrNicks = usernamesOrNicks,
                        commandValues = commandValues(
                            interaction.commandOptions.options.orEmpty(),
                            storeState.users,
                            storeState.guildMembers.orEmpty(),
                            storeState.guildRoles.orEmpty(),
                            storeState.channels,
                        ),
                    ),
                )
            }
        }
    }

    data class SlashCommandParam(
        val name: String,
        val value: String,
        val valueColor: Int?,
        val copyText: String,
    )

    data class StoreState(
        val interactionState: StoreApplicationInteractions.State?,
        val application: Application?,
        val users: Map<Long, User>,
        val guildMembers: Map<Long, GuildMember>?,
        val guildRoles: Map<Long, GuildRole>?,
        val channels: Map<Long, Channel>,
    )

    sealed class ViewState {
        object Loading : ViewState()
        object Failed : ViewState()

        data class Loaded(
            val id: Long,
            val user: User?,
            val interactionUser: GuildMember?,
            val application: Application?,
            val applicationCommandData: ApplicationCommandData,
            val guildId: Long?,
            val channelId: Long?,
            val guildMembers: Map<Long, GuildMember>?,
            val guildRoles: Map<Long, GuildRole>?,
            val channels: Map<Long, Channel>?,
            val usernamesOrNicks: Map<Long, String>,
            val commandValues: Map<String, SlashCommandParam>,
        ) : ViewState()
    }

    companion object {
        private fun observeStores(
            interactionId: Long,
            channelId: Long,
            guildId: Long?,
            interactionUserId: Long,
            applicationId: Long,
        ): Observable<StoreState> {
            val interactions = StoreStream.getInteractions().observeInteractionData(interactionId)
            val applications = StoreStream.getApplicationCommands()
                .observeGuildApplications(channelId)
                .map { StoreStream.getApplicationCommands().getApplicationMap()[applicationId] }
            val users = StoreStream.getUsers().observeAllUsers()
            val members: Observable<Map<Long, GuildMember>> = guildId?.let {
                StoreStream.getGuilds().observeComputed(it)
            }
                ?: Observable.just(emptyMap())
            val roles: Observable<Map<Long, GuildRole>> = guildId?.let {
                StoreStream.getGuilds().observeRoles(it)
            }
                ?: Observable.just(emptyMap())
            val channels = StoreStream.getChannels().observeAllChannels()

            val identity = Observable.combineLatest(interactions, applications, users) { state, app, allUsers ->
                Triple(state, app, allUsers)
            }
            val guild = Observable.combineLatest(members, roles, ::Pair)
            return Observable.combineLatest(identity, guild, channels) { first, guildData, allChannels ->
                StoreState(
                    interactionState = first.first,
                    application = first.second,
                    users = first.third,
                    guildMembers = guildData.first,
                    guildRoles = guildData.second,
                    channels = allChannels,
                )
            }.distinctUntilChanged()
        }

        internal fun commandValues(
            options: List<ApplicationCommandValue>,
            users: Map<Long, User>,
            members: Map<Long, GuildMember>,
            roles: Map<Long, GuildRole>,
            channels: Map<Long, Channel>,
        ): Map<String, SlashCommandParam> = options.flattenOptions().associate { option ->
            val id = option.value.toString().toLongOrNull()
            val param = when (ApplicationCommandType.from(option.type)) {
                ApplicationCommandType.USER -> {
                    val user = id?.let(users::get)
                    SlashCommandParam(
                        option.name,
                        user?.username.orEmpty(),
                        id?.let(members::get)?.color,
                        user?.let { "@${it.username}#${it.discriminator}" }.orEmpty(),
                    )
                }
                ApplicationCommandType.ROLE -> {
                    val role = id?.let(roles::get)
                    SlashCommandParam(
                        option.name,
                        role?.name.orEmpty(),
                        role?.let { RoleUtils.getOpaqueColor(it) },
                        role?.let { "@${it.name}" }.orEmpty(),
                    )
                }
                ApplicationCommandType.MENTIONABLE -> {
                    val role = id?.let(roles::get)
                    val user = id?.let(users::get)
                    if (role != null) {
                        SlashCommandParam(option.name, role.name, RoleUtils.getOpaqueColor(role), "@${role.name}")
                    } else {
                        SlashCommandParam(
                            option.name,
                            user?.username.orEmpty(),
                            id?.let(members::get)?.color,
                            user?.let { "@${it.username}#${it.discriminator}" }.orEmpty(),
                        )
                    }
                }
                ApplicationCommandType.CHANNEL -> {
                    val channel = id?.let(channels::get)
                    SlashCommandParam(
                        option.name,
                        channel?.name.orEmpty(),
                        null,
                        channel?.let { "#${it.name}" }.orEmpty(),
                    )
                }
                else -> option.value.toStringTruncateZeroDecimal().let {
                    SlashCommandParam(option.name, it, null, it)
                }
            }
            option.name to param
        }
    }
}
