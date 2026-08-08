package com.discord.widgets.servers

import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildNsfwLevel
import com.discord.api.permission.Permission
import com.discord.api.user.NsfwAllowance
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

/** State for [WidgetServerSettingsModeration], matching its reference `Model`. */
class ServerSettingsModerationViewModel(
    guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<ServerSettingsModerationViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guild: Guild?,
        val canManageServer: Boolean,
        val isNsfwAllowed: Boolean = true,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()
        object Unauthorized : ViewState()

        data class Loaded(val guild: Guild, val isNsfwAllowed: Boolean = true) : ViewState() {
            val isCommunity: Boolean get() = guild.hasFeature(GuildFeature.COMMUNITY)
            val isAgeRestricted: Boolean
                get() = guild.ownerConfiguredContentLevel == GuildNsfwLevel.AGE_RESTRICTED

            /** Discord's own rating overrides the owner's, and cannot be turned off from here. */
            val isAgeRestrictedByDiscord: Boolean
                get() = guild.nsfwLevel == GuildNsfwLevel.AGE_RESTRICTED && !isAgeRestricted
        }
    }

    init {
        storeStateObservable.computationLatest().distinctUntilChanged().ui(this).subscribe {
            updateViewState(toViewState(it))
        }
    }

    companion object {
        internal fun toViewState(storeState: StoreState): ViewState {
            val guild = storeState.guild
            return if (guild == null || !storeState.canManageServer) {
                ViewState.Unauthorized
            } else {
                ViewState.Loaded(guild, storeState.isNsfwAllowed)
            }
        }

        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
        ) { guild, meUser, guildPermissions ->
            StoreState(
                isNsfwAllowed = meUser.nsfwAllowance != NsfwAllowance.DISALLOWED,
                guild = guild,
                canManageServer = guild != null && (
                    guild.isOwner(meUser.id) ||
                        PermissionUtils.canAndIsElevated(
                            Permission.MANAGE_GUILD,
                            guildPermissions,
                            meUser.mfaEnabled,
                            guild.mfaLevel,
                        )
                    ),
            )
        }
    }
}
