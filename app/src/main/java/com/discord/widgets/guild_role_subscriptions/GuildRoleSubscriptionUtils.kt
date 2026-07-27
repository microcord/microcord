package com.discord.widgets.guild_role_subscriptions

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionGroupListing
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionTierListing
import com.discord.api.guildrolesubscription.SubscriptionTrialInterval
import com.discord.nullserializable.NullSerializable
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuildRoleSubscriptions
import com.discord.utilities.rx.restSubscribeOn
import com.discord.widgets.guild_role_subscriptions.tier.model.Benefit
import rx.Observable

/**
 * The write half of guild monetisation: creating and editing the storefront and its tiers.
 *
 * These are compound operations, not single calls. A tier is spread across three resources — the
 * listing, the guild *role* that carries its colour and badge, and its optional free trial — and
 * the editor treats them as one save. Each method therefore chains the calls and reports success
 * only once all of them land, and pushes the result into [StoreGuildRoleSubscriptions] itself so
 * callers never have to re-fetch.
 *
 * The role and trial legs are skipped when there is nothing to change, so editing only a tier's
 * description costs exactly one request.
 */
object GuildRoleSubscriptionUtils {

    /**
     * Creates the guild's storefront and its first tier in one flow.
     *
     * The group must exist before a tier can be attached to it, so the two calls are sequential
     * rather than zipped; the group is published to the store as soon as it is created so a failure
     * on the tier leg still leaves the storefront usable.
     */
    @Suppress("LongParameterList")
    fun createGuildRoleSubscriptionGroupListing(
        restApi: RestAPIInterface,
        storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions,
        guildId: Long,
        coverImage: String?,
        planDescription: String?,
        isFullServerGating: Boolean?,
        tierName: String,
        tierDescription: String?,
        priceTier: Int,
        tierImage: String? = null,
        memberColor: Int,
        memberBadge: String? = null,
        canAccessAllChannels: Boolean,
        channelBenefits: List<Benefit>,
        intangibleBenefits: List<Benefit>,
    ): Observable<GuildRoleSubscriptionTierListing> = restApi
        .createGuildRoleSubscriptionGroupListing(
            guildId,
            RestAPIParams.CreateGuildRoleSubscriptionGroupListing(
                image = coverImage,
                description = planDescription,
                fullServerGate = isFullServerGating,
            ),
        )
        .flatMap { groupListing ->
            storeGuildRoleSubscriptions.handleGuildRoleSubscriptionGroupUpdate(guildId, groupListing)
            createGuildRoleSubscriptionTierListing(
                restApi = restApi,
                storeGuildRoleSubscriptions = storeGuildRoleSubscriptions,
                guildId = guildId,
                groupListingId = groupListing.id,
                tierName = tierName,
                tierDescription = tierDescription,
                priceTier = priceTier,
                tierImage = tierImage,
                memberColor = memberColor,
                memberIcon = memberBadge,
                canAccessAllChannels = canAccessAllChannels,
                channelBenefits = channelBenefits,
                intangibleBenefits = intangibleBenefits,
            )
        }
        .restSubscribeOn()

    /**
     * Creates one tier, then applies its member design to the backing guild role.
     *
     * The tier is only announced to the store after the role update succeeds, so a half-created
     * tier (listing without its colour) never shows up in the tier list.
     */
    @Suppress("LongParameterList")
    fun createGuildRoleSubscriptionTierListing(
        restApi: RestAPIInterface,
        storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions,
        guildId: Long,
        groupListingId: Long,
        tierName: String,
        tierDescription: String?,
        priceTier: Int,
        tierImage: String? = null,
        memberColor: Int,
        memberIcon: String? = null,
        canAccessAllChannels: Boolean,
        channelBenefits: List<Benefit>,
        intangibleBenefits: List<Benefit>,
    ): Observable<GuildRoleSubscriptionTierListing> = restApi
        .createGuildRoleSubscriptionTier(
            guildId,
            groupListingId,
            RestAPIParams.CreateGuildRoleSubscriptionTierListing(
                name = tierName,
                image = tierImage,
                description = tierDescription,
                benefits = (channelBenefits + intangibleBenefits)
                    .map(Benefit::toGuildRoleSubscriptionBenefit),
                priceTier = priceTier,
                canAccessAllChannels = canAccessAllChannels,
            ),
        )
        .flatMap { tierListing ->
            updateGuildRoleSubscriptionDesign(restApi, guildId, memberColor, memberIcon, tierListing)
        }
        .restSubscribeOn()
        .doOnNext { tierListing ->
            storeGuildRoleSubscriptions.handleGuildRoleSubscriptionTierListingCreate(
                guildId,
                groupListingId,
                tierListing,
            )
        }

    fun deleteGuildRoleSubscriptionTierListing(
        restApi: RestAPIInterface,
        storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions,
        guildId: Long,
        groupListingId: Long,
        tierListingId: Long,
    ): Observable<Void> = restApi
        .deleteGuildRoleSubscriptionTierListing(guildId, groupListingId, tierListingId)
        .restSubscribeOn()
        .doOnNext {
            storeGuildRoleSubscriptions.handleGuildRoleSubscriptionTierListingDelete(
                guildId,
                groupListingId,
                tierListingId,
            )
        }

    fun updateGuildRoleSubscriptionGroupListing(
        restApi: RestAPIInterface,
        storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions,
        guildId: Long,
        groupListingId: Long,
        coverImage: String?,
        planDescription: String?,
        isFullServerGating: Boolean?,
    ): Observable<GuildRoleSubscriptionGroupListing> = restApi
        .updateGuildRoleSubscriptionGroupListing(
            guildId,
            groupListingId,
            RestAPIParams.UpdateGuildRoleSubscriptionGroupListing(
                image = coverImage,
                description = planDescription,
                fullServerGate = isFullServerGating,
            ),
        )
        .restSubscribeOn()
        .doOnNext { groupListing ->
            storeGuildRoleSubscriptions.handleGuildRoleSubscriptionGroupUpdate(guildId, groupListing)
        }

    /**
     * Edits an existing tier: the listing, then its role design, then its free trial.
     *
     * Every argument is optional and `null` means "leave alone" — except the two trial arguments,
     * which are [NullSerializable] because "clear the trial" and "don't touch the trial" are
     * different requests.
     *
     * Benefit lists are merged before being sent: the API replaces the whole array, so passing only
     * the channel half would delete every intangible benefit.
     */
    @Suppress("LongParameterList")
    fun updateGuildRoleSubscriptionTierListing(
        restApi: RestAPIInterface,
        storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions,
        guildId: Long,
        groupListingId: Long,
        tierListingId: Long,
        tierName: String? = null,
        tierDescription: String? = null,
        tierImage: String? = null,
        priceTier: Int? = null,
        memberColor: Int? = null,
        memberIcon: String? = null,
        canAccessAllChannels: Boolean? = null,
        channelBenefits: List<Benefit>? = null,
        intangibleBenefits: List<Benefit>? = null,
        published: Boolean? = null,
        trialInterval: NullSerializable<SubscriptionTrialInterval>? = null,
        activeTrialUserLimit: NullSerializable<Int>? = null,
    ): Observable<GuildRoleSubscriptionTierListing> {
        val benefits = when {
            channelBenefits != null && intangibleBenefits != null -> channelBenefits + intangibleBenefits
            else -> channelBenefits ?: intangibleBenefits
        }?.map(Benefit::toGuildRoleSubscriptionBenefit)

        return restApi
            .updateGuildRoleSubscriptionTierListing(
                guildId,
                groupListingId,
                tierListingId,
                RestAPIParams.UpdateGuildRoleSubscriptionTierListing(
                    name = tierName,
                    image = tierImage,
                    description = tierDescription,
                    priceTier = priceTier,
                    benefits = benefits,
                    published = published,
                    canAccessAllChannels = canAccessAllChannels,
                ),
            )
            .flatMap { tierListing ->
                updateGuildRoleSubscriptionDesign(restApi, guildId, memberColor, memberIcon, tierListing)
            }
            .flatMap { tierListing ->
                updateGuildRoleSubscriptionTrial(
                    restApi,
                    storeGuildRoleSubscriptions,
                    guildId,
                    tierListing,
                    trialInterval,
                    activeTrialUserLimit,
                )
            }
            .restSubscribeOn()
            .doOnNext { tierListing ->
                storeGuildRoleSubscriptions.handleGuildRoleSubscriptionTierListingUpdate(guildId, tierListing)
            }
    }

    /**
     * Pushes the tier's colour and badge onto the guild role that subscribers receive.
     *
     * The role endpoint answers with no body, so the tier listing is passed straight through to
     * keep the chain's element type.
     */
    private fun updateGuildRoleSubscriptionDesign(
        restApi: RestAPIInterface,
        guildId: Long,
        memberColor: Int? = null,
        memberIcon: String? = null,
        guildRoleSubscriptionTierListing: GuildRoleSubscriptionTierListing,
    ): Observable<GuildRoleSubscriptionTierListing> = restApi
        .updateRole(
            guildId,
            guildRoleSubscriptionTierListing.roleId,
            RestAPIParams.Role(
                color = memberColor,
                id = guildRoleSubscriptionTierListing.roleId,
                icon = memberIcon,
            ),
        )
        .map { guildRoleSubscriptionTierListing }

    /** No-ops when neither trial field was supplied, so an ordinary edit costs no extra request. */
    private fun updateGuildRoleSubscriptionTrial(
        restApi: RestAPIInterface,
        storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions,
        guildId: Long,
        guildRoleSubscriptionTierListing: GuildRoleSubscriptionTierListing,
        trialInterval: NullSerializable<SubscriptionTrialInterval>? = null,
        activeTrialUserLimit: NullSerializable<Int>? = null,
    ): Observable<GuildRoleSubscriptionTierListing> {
        if (trialInterval == null && activeTrialUserLimit == null) {
            return Observable.just(guildRoleSubscriptionTierListing)
        }
        return restApi
            .updateGuildRoleSubscriptionTierFreeTrial(
                guildId,
                guildRoleSubscriptionTierListing.id,
                RestAPIParams.UpdateGuildRoleSubscriptionTierFreeTrial(
                    trial = trialInterval,
                    maxNumActiveTrialUsers = activeTrialUserLimit,
                ),
            )
            .doOnNext { freeTrial ->
                storeGuildRoleSubscriptions.handleGuildRoleSubscriptionTierTrialUpdate(
                    guildId,
                    guildRoleSubscriptionTierListing.id,
                    freeTrial,
                )
            }
            .map { guildRoleSubscriptionTierListing }
    }
}
