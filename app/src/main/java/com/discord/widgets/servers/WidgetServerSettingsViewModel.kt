package com.discord.widgets.servers

import com.discord.api.guild.GuildFeature
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.ManageGuildContext
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionsFeatureFlag
import rx.Observable

/**
 * State for the server-settings landing screen.
 *
 * Reference: `WidgetServerSettings.Model`. Every row on that screen is permission-gated, and the
 * gate is the whole point of the class — [ManageGuildContext] is derived once from the acting
 * user's guild permissions, their per-category channel permissions, the guild's MFA level and
 * whether they own it, and each section reads a single boolean off it.
 *
 * Role-subscription visibility comes from the recovered experiment/permission gate; enrollment is
 * the guild's `ROLE_SUBSCRIPTIONS_ENABLED` feature, matching 126.21.
 */
class WidgetServerSettingsViewModel(
    val guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<WidgetServerSettingsViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guild: Guild?,
        val manageGuildContext: ManageGuildContext,
        val canManageGuildRoleSubscriptions: Boolean,
        val hasGuildRoleSubscriptions: Boolean,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        /** The user cannot manage this guild, or it is gone. The screen closes itself. */
        object Unauthorized : ViewState()

        data class Loaded(
            val guild: Guild,
            val manageGuildContext: ManageGuildContext,
            val canManageGuildRoleSubscriptions: Boolean,
            val hasGuildRoleSubscriptions: Boolean,
        ) : ViewState() {
            val isCommunity: Boolean get() = guild.hasFeature(GuildFeature.COMMUNITY)
            val hasVanityUrl: Boolean get() = guild.hasFeature(GuildFeature.VANITY_URL)
            val isCreatorMonetizable: Boolean get() = guild.hasFeature(GuildFeature.CREATOR_MONETIZABLE)

            /** The delete entry only exists for an owner who satisfies the guild's MFA requirement. */
            val canDeleteGuild: Boolean get() = manageGuildContext.isOwnerWithRequiredMFALevel
        }
    }

    init {
        storeStateObservable.computationLatest().ui(this).subscribe(::handleStoreState)
    }

    private fun handleStoreState(storeState: StoreState) {
        updateViewState(toViewState(storeState))
    }

    companion object {
        internal fun toViewState(storeState: StoreState): ViewState {
            val guild = storeState.guild
            if (guild == null || !storeState.manageGuildContext.canManage()) {
                return ViewState.Unauthorized
            }
            return ViewState.Loaded(
                guild = guild,
                manageGuildContext = storeState.manageGuildContext,
                canManageGuildRoleSubscriptions = storeState.canManageGuildRoleSubscriptions,
                hasGuildRoleSubscriptions = storeState.hasGuildRoleSubscriptions,
            )
        }

        fun observeStoreState(
            guildId: Long,
            canManageGuildRoleSubscriptionsSource: Observable<Boolean> =
                GuildRoleSubscriptionsFeatureFlag()
                    .observeCanGuildSeeGuildRoleSubscriptionsSettings(guildId),
        ): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getUsers().observeMe(),
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
            StoreStream.getChannels().observeChannelCategories(guildId),
            StoreStream.getPermissions().observeChannelPermissionsForGuild(guildId),
            canManageGuildRoleSubscriptionsSource,
        ) { meUser, guild, guildPermissions, categories, channelPermissions, canManageSubs ->
            StoreState(
                guild = guild,
                manageGuildContext = ManageGuildContext.from(
                    categories = categories,
                    isOwner = guild?.isOwner(meUser.id) == true,
                    guildPermissions = guildPermissions,
                    channelPermissions = channelPermissions,
                    guildMfaLevel = guild?.mfaLevel ?: 0,
                    isMeMfaEnabled = meUser.mfaEnabled,
                ),
                canManageGuildRoleSubscriptions = canManageSubs,
                hasGuildRoleSubscriptions =
                    guild?.hasFeature(GuildFeature.ROLE_SUBSCRIPTIONS_ENABLED) == true,
            )
        }.distinctUntilChanged()
    }
}
