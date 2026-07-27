package com.discord.widgets.guild_role_subscriptions.setup

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionGroupListing
import com.discord.app.AppViewModel

/**
 * The storefront-level half of the setup wizard: its cover image, its blurb, and whether buying any
 * tier unlocks the entire server.
 *
 * Activity-scoped, so [WidgetGuildRoleSubscriptionPlanFormat] (which picks the gating mode) and
 * [WidgetGuildRoleSubscriptionPlanDetails] (which fills in the artwork) edit one object, and
 * [GuildRoleSubscriptionPlanSetupViewModel] observes the result.
 */
class GuildRoleSubscriptionPlanDetailsViewModel : AppViewModel<GuildRoleSubscriptionPlanDetailsViewModel.ViewState>(
    ViewState(),
) {

    /**
     * [coverImage] is a freshly-picked `data:` URL; [coverImageAssetId] identifies one already
     * uploaded. Editing an existing storefront starts with only the latter, and the former replaces
     * it the moment the creator picks a new image — see `WidgetGuildRoleSubscriptionPlanDetails`.
     */
    data class ViewState(
        val coverImage: String? = null,
        val applicationId: Long? = null,
        val coverImageAssetId: Long? = null,
        val description: String? = null,
        val isFullServerGate: Boolean = false,
    )

    /**
     * Seeds the editor from a storefront that already exists.
     *
     * [fullServerGatingOverwrite] wins over the listing's own flag: the roles screen can have taken
     * `VIEW_CHANNEL` away from `@everyone` since the listing was saved, and what the guild actually
     * looks like now is what the creator should see.
     */
    fun setGroupPlanDetails(
        subscriptionGroupListing: GuildRoleSubscriptionGroupListing,
        fullServerGatingOverwrite: Boolean?,
    ) {
        updateViewState(
            ViewState(
                coverImage = null,
                applicationId = subscriptionGroupListing.applicationId,
                coverImageAssetId = subscriptionGroupListing.imageAsset?.id,
                description = subscriptionGroupListing.description,
                isFullServerGate = fullServerGatingOverwrite ?: subscriptionGroupListing.fullServerGate,
            ),
        )
    }

    fun updateCoverImage(coverImage: String?) {
        updateViewState(requireViewState().copy(coverImage = coverImage))
    }

    fun updateDescription(description: String?) {
        updateViewState(requireViewState().copy(description = description))
    }

    fun updateFullServerGate(isFullServerGate: Boolean) {
        updateViewState(requireViewState().copy(isFullServerGate = isFullServerGate))
    }
}
