package com.discord.widgets.servers

import com.discord.api.channel.Channel
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

/** State for [WidgetServerSettingsOverview], matching its reference `Model`. */
class ServerSettingsOverviewViewModel(
    guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<ServerSettingsOverviewViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guild: Guild?,
        val canManage: Boolean,
        val guildChannels: List<Channel>,
        val approximateMemberCount: Int?,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()
        object Unauthorized : ViewState()

        data class Loaded(
            val guild: Guild,
            val afkChannel: Channel?,
            val systemChannel: Channel?,
            val isAboveNotifyAllSize: Boolean,
            val isCreatorMonetizable: Boolean,
        ) : ViewState()
    }

    init {
        storeStateObservable.computationLatest().distinctUntilChanged().ui(this).subscribe {
            updateViewState(toViewState(it))
        }
    }

    companion object {
        internal const val NOTIFY_ALL_MEMBER_LIMIT = 2500

        internal fun toViewState(storeState: StoreState): ViewState {
            val guild = storeState.guild
            if (guild == null || !storeState.canManage) return ViewState.Unauthorized
            val byId = storeState.guildChannels.associateBy { it.id }
            return ViewState.Loaded(
                guild = guild,
                afkChannel = guild.afkChannelId?.let(byId::get),
                systemChannel = guild.systemChannelId?.let(byId::get),
                isAboveNotifyAllSize =
                    (storeState.approximateMemberCount ?: 0) > NOTIFY_ALL_MEMBER_LIMIT,
                isCreatorMonetizable = !guild.hasFeature(GuildFeature.CREATOR_MONETIZABLE_DISABLED) &&
                    (guild.hasFeature(GuildFeature.CREATOR_MONETIZABLE) ||
                        guild.hasFeature(GuildFeature.CREATOR_MONETIZABLE_PROVISIONAL)),
            )
        }

        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
            StoreStream.getChannels().observeChannelsForGuild(guildId),
            StoreStream.getGuildProfiles().observeGuildProfile(guildId),
        ) { guild, meUser, guildPermissions, channels, guildProfile ->
            StoreState(
                guild = guild,
                canManage = guild != null && (
                    guild.isOwner(meUser.id) ||
                        PermissionUtils.canAndIsElevated(
                            Permission.MANAGE_GUILD,
                            guildPermissions,
                            meUser.mfaEnabled,
                            guild.mfaLevel,
                        )
                    ),
                guildChannels = channels.values.toList(),
                approximateMemberCount = guildProfile?.data?.approximateMemberCount,
            )
        }
    }
}
