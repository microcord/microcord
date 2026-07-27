package com.discord.widgets.guild_role_subscriptions

import android.content.Context
import android.net.Uri
import com.discord.R
import com.discord.app.AppToast
import com.discord.stores.StoreStream
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.guild_role_subscriptions.entrypoint.WidgetGuildRoleSubscriptionEntryPoint

/**
 * Routes `/channels/<guildId>/role-subscriptions` links.
 *
 * Purchases cannot happen inside the Android app, so an eligible guild hands the user off to the
 * mobile web purchase page. A guild that sells subscriptions but is not in the mobile-web rollout,
 * or does not sell them at all, gets a local explanation instead of a dead link:
 * [WidgetGuildRoleSubscriptionEntryPoint] for an unknown guild, a toast otherwise.
 */
object RoleSubscriptionsLinkingUtil {

    /**
     * The route segment the web client uses. Reference:
     * `utilities/intent/StaticChannelRoutes.ROLE_SUBSCRIPTIONS`, which has not been reconstructed —
     * when it lands, this constant and [tryPerformHandoff]'s parsing move there.
     */
    private const val ROLE_SUBSCRIPTIONS_ROUTE = "role-subscriptions"

    private val CHANNEL_ROUTE_REGEX = Regex("""^/channels/(\d+)/([\w\-]+)/?$""")

    private const val WEB_ORIGIN = "https://discord.com"

    /**
     * Opens the mobile web purchase page for [url] (a site-relative path).
     *
     * Reference: `utilities/handoff/MobileWebHandoff.tryLaunchRedirectTo`, which trades the session
     * for a one-time handoff token so the web page opens already signed in. Neither that helper nor
     * the `auth/handoff` client exists here yet, so the default opens the page unauthenticated —
     * the user may have to sign in on the web. Replace this hook when `MobileWebHandoff` lands.
     */
    var performHandoff: (context: Context, url: String) -> Boolean = { context, url ->
        UriHandler.handle(context, WEB_ORIGIN + url, forceExternal = true)
        true
    }

    fun handoffToGuildRoleSubscriptions(context: Context, guildId: Long): HandoffResult =
        when (getHandoffEligibility(guildId)) {
            HandoffEligibility.ELIGIBLE -> {
                val handled = performHandoff(context, "/channels/$guildId/$ROLE_SUBSCRIPTIONS_ROUTE")
                if (handled) HandoffResult.SUCCESS else HandoffResult.FAILURE
            }
            HandoffEligibility.INELIGIBLE_FOR_MOBILE_WEB_PURCHASE,
            HandoffEligibility.INELIGIBLE_FOR_ROLE_SUBSCRIPTIONS,
            -> {
                AppToast.show(context, R.string.inaccessible_channel_link_title)
                HandoffResult.INELIGIBLE
            }
            HandoffEligibility.UNKNOWN_GUILD -> {
                WidgetGuildRoleSubscriptionEntryPoint.launch(context, guildId, TRACKING_SOURCE_LOCATION)
                HandoffResult.INELIGIBLE
            }
        }

    /**
     * @return `true` when [uri] was a role-subscriptions link this object dealt with — including
     *   the ineligible cases, which are still "handled" because the user was told why.
     */
    fun tryPerformHandoff(context: Context, uri: Uri): Boolean {
        val (route, guildId) = extractStaticRoute(uri) ?: return false
        if (route != ROLE_SUBSCRIPTIONS_ROUTE) return false
        return handoffToGuildRoleSubscriptions(context, guildId) != HandoffResult.FAILURE
    }

    private fun extractStaticRoute(uri: Uri): Pair<String, Long>? {
        // A relative link (no host) is already ours; an absolute one must point at Discord.
        val host = uri.host
        if (host != null && !UriHandler.isImplicitlyTrustedDomain(host)) return null
        val match = CHANNEL_ROUTE_REGEX.matchEntire(uri.path.orEmpty()) ?: return null
        val guildId = match.groupValues[1].toLongOrNull() ?: 0L
        return match.groupValues[2] to guildId
    }

    private fun getHandoffEligibility(guildId: Long): HandoffEligibility {
        if (guildId == 0L || StoreStream.getGuilds().getGuild(guildId) == null) {
            return HandoffEligibility.UNKNOWN_GUILD
        }
        val featureFlag = GuildRoleSubscriptionsFeatureFlag.INSTANCE
        return when {
            !featureFlag.canGuildSeePremiumMemberships(guildId) ->
                HandoffEligibility.INELIGIBLE_FOR_ROLE_SUBSCRIPTIONS
            !featureFlag.canGuildAccessMobileWebPurchases(guildId) ->
                HandoffEligibility.INELIGIBLE_FOR_MOBILE_WEB_PURCHASE
            else -> HandoffEligibility.ELIGIBLE
        }
    }

    private const val TRACKING_SOURCE_LOCATION = "Role Subscriptions Tab"

    private enum class HandoffEligibility {
        ELIGIBLE,
        UNKNOWN_GUILD,
        INELIGIBLE_FOR_ROLE_SUBSCRIPTIONS,
        INELIGIBLE_FOR_MOBILE_WEB_PURCHASE,
    }

    enum class HandoffResult { SUCCESS, FAILURE, INELIGIBLE }
}
