package com.discord.widgets.guild_role_subscriptions

import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.models.guild.Guild
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.servers.creator_monetization_eligibility.CreatorMonetizationCountryAllowlist
import rx.Observable

/**
 * Every gate that decides whether a guild may sell, or a user may see, role subscriptions.
 *
 * The `observe*` methods are `ObservationDeck`-driven rather than store-observable-driven because
 * the predicates read several stores at once and only their *boolean* result matters — hence the
 * `distinctUntilChanged` on each.
 */
class GuildRoleSubscriptionsFeatureFlag(
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val storePermissions: StorePermissions = StoreStream.getPermissions(),
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val isUserInEligibleCountry: () -> Boolean =
        CreatorMonetizationCountryAllowlist.INSTANCE::isUserInEligibleCountry,
) {

    /** Whether the guild's storefront is live and purchasable by this user. */
    fun canGuildSeePremiumMemberships(guildId: Long): Boolean {
        val guild = storeGuilds.getGuild(guildId) ?: return false
        return guild.hasFeature(GuildFeature.ROLE_SUBSCRIPTIONS_AVAILABLE_FOR_PURCHASE) &&
            guild.isCreatorMonetizationEnabled
    }

    /** Before a guild is monetisable this is an owner-only preview of the onboarding flow. */
    fun canGuildSeeGuildRoleSubscriptionSettings(guildId: Long): Boolean {
        val guild = storeGuilds.getGuild(guildId) ?: return false
        if (guild.hasFeature(GuildFeature.CREATOR_MONETIZABLE_DISABLED)) return false
        if (guild.isCreatorMonetizationEnabled) {
            return PermissionUtils.can(Permission.MANAGE_GUILD, storePermissions.getGuildPermissions()[guildId])
        }
        return guild.hasFeature(GuildFeature.COMMUNITY) && guild.isOwner(storeUser.getMe().id) &&
            isUserInEligibleCountry()
    }

    fun isGuildEligibleForGuildRoleSubscriptionTrials(guildId: Long): Boolean =
        storeGuilds.getGuild(guildId)?.hasFeature(GuildFeature.ROLE_SUBSCRIPTIONS_ENABLED) == true

    fun observeCanGuildSeeGuildRoleSubscriptions(guildId: Long): Observable<Boolean> =
        observationDeck.connectRx(storeGuilds) { canGuildSeePremiumMemberships(guildId) }
            .distinctUntilChanged()

    fun observeCanGuildSeeGuildRoleSubscriptionsSettings(guildId: Long): Observable<Boolean> =
        observationDeck.connectRx(storeGuilds, storeUser, storePermissions) {
            canGuildSeeGuildRoleSubscriptionSettings(guildId)
        }.distinctUntilChanged()

    fun observeIsGuildEligibleForGuildRoleSubscriptionFreeTrials(guildId: Long): Observable<Boolean> =
        observationDeck.connectRx(storeGuilds) {
            isGuildEligibleForGuildRoleSubscriptionTrials(guildId)
        }.distinctUntilChanged()

    companion object {
        /**
         * Shared instance. Built lazily because its default stores come from [StoreStream], which
         * is not initialised when the class is first referenced.
         */
        @JvmStatic
        val INSTANCE: GuildRoleSubscriptionsFeatureFlag by lazy { GuildRoleSubscriptionsFeatureFlag() }

        private val Guild.isCreatorMonetizationEnabled: Boolean
            get() = !hasFeature(GuildFeature.CREATOR_MONETIZABLE_DISABLED) &&
                (hasFeature(GuildFeature.CREATOR_MONETIZABLE) ||
                    hasFeature(GuildFeature.CREATOR_MONETIZABLE_PROVISIONAL))
    }
}
