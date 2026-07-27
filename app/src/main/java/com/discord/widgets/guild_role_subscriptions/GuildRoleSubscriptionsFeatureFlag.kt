package com.discord.widgets.guild_role_subscriptions

import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.models.guild.Guild
import com.discord.stores.StoreExperiments
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import rx.Observable

/**
 * Every gate that decides whether a guild may sell, or a user may see, role subscriptions.
 *
 * Two orthogonal things are checked throughout:
 *  - **experiments**, which say whether the guild/user is in the rollout at all, and
 *  - **guild features**, which say whether Discord has actually enabled monetisation for the guild
 *    (`CREATOR_MONETIZABLE` minus `CREATOR_MONETIZABLE_DISABLED`).
 *
 * The `observe*` methods are `ObservationDeck`-driven rather than store-observable-driven because
 * the predicates read several stores at once and only their *boolean* result matters — hence the
 * `distinctUntilChanged` on each.
 */
class GuildRoleSubscriptionsFeatureFlag(
    private val storeExperiments: StoreExperiments = StoreStream.getExperiments(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val storePermissions: StorePermissions = StoreStream.getPermissions(),
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    /**
     * Reference: `CreatorMonetizationCountryAllowlist.isUserInEligibleCountry()`, which lives in
     * `widgets/servers/creator_monetization_eligibility` and has not been reconstructed. Until it
     * exists a non-monetisable guild's owner cannot reach the settings screen, which is the safe
     * direction: the alternative would advertise a flow the backend would reject.
     */
    private val isUserInEligibleCountry: () -> Boolean = { false },
) {

    // region experiment gates

    private fun canUserCreateRoleSubscriptions(): Boolean =
        storeExperiments.getUserExperiment(EXPERIMENT_USERS)?.bucket == 1

    fun canGuildCreateRoleSubscriptions(guildId: Long): Boolean =
        storeExperiments.getGuildExperiment(EXPERIMENT_GUILDS, guildId)?.bucket == 1

    fun canGuildAccessMobileWebPurchases(guildId: Long): Boolean =
        storeExperiments.getGuildExperiment(EXPERIMENT_MOBILE_WEB_PURCHASE, guildId)?.bucket == 1

    /** The user may edit or create listings only if both halves of the rollout include them. */
    fun isGuildEligibleForRoleSubscriptions(guildId: Long): Boolean =
        canGuildCreateRoleSubscriptions(guildId) && canUserCreateRoleSubscriptions()

    fun isGuildEligibleForGuildRoleSubscriptionTrials(guildId: Long): Boolean =
        storeExperiments.getGuildExperiment(EXPERIMENT_TRIALS, guildId)?.bucket == 1 &&
            isGuildEligibleForRoleSubscriptions(guildId)

    fun canGuildSeePurchaseFeedbackLoopMessages(guildId: Long): Boolean =
        storeExperiments.getGuildExperiment(EXPERIMENT_PURCHASE_FEEDBACK_LOOP, guildId)?.bucket == 1 &&
            isGuildEligibleForRoleSubscriptions(guildId)

    fun canUserAccessRoleSubscriptionManagement(
        experiments: StoreExperiments = storeExperiments,
    ): Boolean = experiments.getUserExperiment(EXPERIMENT_NATIVE_MOBILE_MANAGEMENT)?.bucket == 1

    // endregion

    // region feature gates

    /** Whether the guild's storefront is live and purchasable by this user. */
    fun canGuildSeePremiumMemberships(guildId: Long): Boolean {
        val guild = storeGuilds.getGuild(guildId) ?: return false
        return guild.hasFeature(GuildFeature.ROLE_SUBSCRIPTIONS_AVAILABLE_FOR_PURCHASE) &&
            guild.isCreatorMonetizable
    }

    /**
     * Whether the settings entry point is shown.
     *
     * Once the guild is genuinely monetisable this is a plain `MANAGE_GUILD` check. Before that it
     * is an *owner-only preview* of the onboarding flow, restricted to countries where Discord can
     * pay out.
     */
    fun canGuildSeeGuildRoleSubscriptionSettings(guildId: Long): Boolean {
        if (!isGuildEligibleForRoleSubscriptions(guildId)) return false
        val guild = storeGuilds.getGuild(guildId) ?: return false
        if (guild.isCreatorMonetizable) {
            return PermissionUtils.can(Permission.MANAGE_GUILD, storePermissions.getGuildPermissions()[guildId])
        }
        return guild.isOwner(storeUser.getMe().id) && isUserInEligibleCountry()
    }

    // endregion

    // region observables

    fun observeCanGuildSeeGuildRoleSubscriptions(guildId: Long): Observable<Boolean> =
        observationDeck.connectRx(storeGuilds) { canGuildSeePremiumMemberships(guildId) }
            .distinctUntilChanged()

    fun observeCanGuildSeeGuildRoleSubscriptionsSettings(guildId: Long): Observable<Boolean> =
        observationDeck.connectRx(storeExperiments, storeGuilds, storeUser, storePermissions) {
            canGuildSeeGuildRoleSubscriptionSettings(guildId)
        }.distinctUntilChanged()

    fun observeCanGuildSeePurchaseFeedbackLoopSystemMessages(guildId: Long?): Observable<Boolean> {
        if (guildId == null) return Observable.just(false)
        return observationDeck.connectRx(storeExperiments, storeGuilds) {
            canGuildSeePurchaseFeedbackLoopMessages(guildId)
        }.distinctUntilChanged()
    }

    fun observeIsGuildEligibleForGuildRoleSubscriptionFreeTrials(guildId: Long): Observable<Boolean> =
        observationDeck.connectRx(storeExperiments) {
            isGuildEligibleForGuildRoleSubscriptionTrials(guildId)
        }.distinctUntilChanged()

    // endregion

    companion object {
        private const val EXPERIMENT_GUILDS = "2021-06_guild_role_subscriptions"
        private const val EXPERIMENT_USERS = "2021-08_guild_role_subscription_users"
        private const val EXPERIMENT_TRIALS = "2022-01_guild_role_subscription_trials"
        private const val EXPERIMENT_PURCHASE_FEEDBACK_LOOP =
            "2022-04_guild_role_subscription_purchase_feedback_loop"
        private const val EXPERIMENT_MOBILE_WEB_PURCHASE =
            "2022-05_mobile_web_role_subscription_purchase_page"
        private const val EXPERIMENT_NATIVE_MOBILE_MANAGEMENT =
            "2022-06_native_mobile_role_subscription_management"

        /**
         * Shared instance. Built lazily because its default stores come from [StoreStream], which
         * is not initialised when the class is first referenced.
         */
        @JvmStatic
        val INSTANCE: GuildRoleSubscriptionsFeatureFlag by lazy { GuildRoleSubscriptionsFeatureFlag() }

        /** Monetisation switched on for the guild and not subsequently revoked. */
        private val Guild.isCreatorMonetizable: Boolean
            get() = hasFeature(GuildFeature.CREATOR_MONETIZABLE) &&
                !hasFeature(GuildFeature.CREATOR_MONETIZABLE_DISABLED)
    }
}
