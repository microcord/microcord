package com.discord.widgets.user

import android.content.Context
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.user.UserProfile
import com.discord.i18n.i18nFormat
import com.discord.models.user.User
import com.discord.utilities.user.UserUtils
import com.discord.utilities.user.getBoostingSince
import com.discord.utilities.user.getPremiumSince
import com.discord.utilities.user.guildBoostMonthsSubscribed
import com.discord.utilities.user.isGuildBooster

/**
 * One of the small icons in the row under a profile's name.
 *
 * @param text the accessible label, and the tooltip's fallback.
 * @param tooltip the longer explanation shown on tap; badges with nothing to explain leave it null.
 * @param showPremiumUpSell the badge advertises something the *viewer* could buy, so tapping it
 *   opens the Nitro upsell rather than a tooltip.
 * @param objectType analytics identity of what the upsell is selling.
 */
data class Badge(
    @DrawableRes val icon: Int = 0,
    val text: CharSequence? = null,
    val tooltip: CharSequence? = null,
    val showPremiumUpSell: Boolean = false,
    val objectType: String? = null,
    val iconUrl: String? = null,
    val badgeId: String? = null,
    val link: String? = null,
) {

    companion object {
        private const val GUILD_BOOST_LEVEL_2_MONTHS = 2
        private const val GUILD_BOOST_LEVEL_3_MONTHS = 3
        private const val GUILD_BOOST_LEVEL_4_MONTHS = 6
        private const val GUILD_BOOST_LEVEL_5_MONTHS = 9
        private const val GUILD_BOOST_LEVEL_6_MONTHS = 12
        private const val GUILD_BOOST_LEVEL_7_MONTHS = 15
        private const val GUILD_BOOST_LEVEL_8_MONTHS = 18
        private const val GUILD_BOOST_LEVEL_9_MONTHS = 24

        private const val OBJECT_TYPE_PREMIUM_EARLY_SUPPORTER = "PREMIUM_EARLY_SUPPORTER"
        private const val OBJECT_TYPE_PREMIUM = "PREMIUM"
        private const val OBJECT_TYPE_PREMIUM_GUILD = "PREMIUM_GUILD"
        private const val PROFILE_BADGE_CDN = "https://cdn.discordapp.com/badge-icons"

        /**
         * Every badge [user] has earned, in the order Discord displays them: staff and programme
         * badges first, then the paid ones.
         *
         * [isMeUserPremium] and [isMeUserVerified] describe the *viewer*, not [user] — a verified
         * non-subscriber is who the Nitro badges try to sell to.
         */
        @JvmStatic
        fun getBadgesForUser(
            user: User,
            profile: UserProfile,
            isMeUserPremium: Boolean,
            isMeUserVerified: Boolean,
            context: Context,
        ): List<Badge> = getModernBadges(profile) ?: with(UserUtils) {
            val badges = ArrayList<Badge>(8)
            val canUpSell = !isMeUserPremium && isMeUserVerified

            if (user.isStaff) {
                badges += Badge(
                    R.drawable.ic_profile_badge_staff_32dp,
                    context.getString(R.string.staff_badge_tooltip),
                )
            }
            if (user.isPartner) {
                badges += Badge(
                    R.drawable.ic_profile_badge_partner_32dp,
                    context.getString(R.string.partner_badge_tooltip),
                )
            }
            if (user.isCertifiedModerator) {
                badges += Badge(
                    R.drawable.ic_profile_badge_certified_moderator_32dp,
                    context.getString(R.string.certified_moderator_badge_tooltip),
                )
            }
            if (user.isHypeSquad) {
                badges += Badge(
                    R.drawable.ic_profile_badge_hypesquad_32dp,
                    context.getString(R.string.hypesquad_badge_tooltip),
                )
            }
            // The house badges name their house both as the label and inside the tooltip.
            if (user.isHypesquadHouse1) {
                badges += hypesquadHouseBadge(
                    context,
                    R.drawable.ic_hypesquad_house1_32dp,
                    R.string.hypesquad_house_1,
                )
            }
            if (user.isHypesquadHouse2) {
                badges += hypesquadHouseBadge(
                    context,
                    R.drawable.ic_hypesquad_house2_32dp,
                    R.string.hypesquad_house_2,
                )
            }
            if (user.isHypesquadHouse3) {
                badges += hypesquadHouseBadge(
                    context,
                    R.drawable.ic_hypesquad_house3_32dp,
                    R.string.hypesquad_house_3,
                )
            }
            if (user.isBugHunterLevel1) {
                badges += Badge(
                    R.drawable.ic_profile_badge_bughunter_level_1_32dp,
                    context.getString(R.string.bug_hunter_badge_tooltip),
                )
            }
            if (user.isBugHunterLevel2) {
                badges += Badge(
                    R.drawable.ic_profile_badge_bughunter_level_2_32dp,
                    context.getString(R.string.bug_hunter_badge_tooltip),
                )
            }
            if (user.isVerifiedDeveloper) {
                badges += Badge(
                    R.drawable.ic_profile_badge_verified_developer_32dp,
                    context.getString(R.string.verified_developer_badge_tooltip),
                )
            }
            if (user.isPremiumEarlySupporter) {
                badges += Badge(
                    icon = R.drawable.ic_profile_badge_premium_early_supporter_32dp,
                    text = context.getString(R.string.early_supporter_tooltip),
                    showPremiumUpSell = canUpSell,
                    objectType = OBJECT_TYPE_PREMIUM_EARLY_SUPPORTER,
                )
            }
            if (user.isPremium) {
                badges += Badge(
                    icon = R.drawable.ic_profile_badge_nitro_32dp,
                    text = context.getString(R.string.premium_title),
                    tooltip = context.i18nFormat(
                        R.string.premium_badge_tooltip,
                        "date" to profile.getPremiumSince(context),
                    ),
                    showPremiumUpSell = canUpSell,
                    objectType = OBJECT_TYPE_PREMIUM,
                )
            }
            if (profile.isGuildBooster) {
                badges += Badge(
                    icon = guildBoostIcon(profile.guildBoostMonthsSubscribed ?: 0),
                    text = context.getString(R.string.premium_title),
                    tooltip = context.i18nFormat(
                        R.string.premium_guild_subscription_tooltip,
                        "date" to profile.getBoostingSince(context),
                    ),
                    showPremiumUpSell = canUpSell,
                    objectType = OBJECT_TYPE_PREMIUM_GUILD,
                )
            }
            badges
        }

        /** Puts guild badges first and preserves server order within both arrays. */
        internal fun getModernBadges(profile: UserProfile): List<Badge>? {
            if (profile.badges == null && profile.guildBadges == null) return null
            return (profile.guildBadges.orEmpty() + profile.badges.orEmpty()).mapNotNull { badge ->
                badge.icon.takeIf(String::isNotBlank)?.let { icon ->
                    Badge(
                        text = badge.description,
                        tooltip = badge.description,
                        iconUrl = "$PROFILE_BADGE_CDN/$icon.png",
                        badgeId = badge.id,
                        link = badge.link,
                    )
                }
            }
        }

        private fun hypesquadHouseBadge(
            context: Context,
            @DrawableRes icon: Int,
            houseNameRes: Int,
        ): Badge {
            val houseName = context.getString(houseNameRes)
            return Badge(
                icon = icon,
                text = houseName,
                tooltip = context.i18nFormat(
                    R.string.hypesquad_online_badge_tooltip,
                    "houseName" to houseName,
                ),
            )
        }

        /** The boost badge escalates through nine tiers as the boost ages. */
        @DrawableRes
        private fun guildBoostIcon(monthsSubscribed: Int): Int = when {
            monthsSubscribed >= GUILD_BOOST_LEVEL_9_MONTHS ->
                R.drawable.ic_profile_badge_premium_guild_subscription_lvl9_32dp
            monthsSubscribed >= GUILD_BOOST_LEVEL_8_MONTHS ->
                R.drawable.ic_profile_badge_premium_guild_subscription_lvl8_32dp
            monthsSubscribed >= GUILD_BOOST_LEVEL_7_MONTHS ->
                R.drawable.ic_profile_badge_premium_guild_subscription_lvl7_32dp
            monthsSubscribed >= GUILD_BOOST_LEVEL_6_MONTHS ->
                R.drawable.ic_profile_badge_premium_guild_subscription_lvl6_32dp
            monthsSubscribed >= GUILD_BOOST_LEVEL_5_MONTHS ->
                R.drawable.ic_profile_badge_premium_guild_subscription_lvl5_32dp
            monthsSubscribed >= GUILD_BOOST_LEVEL_4_MONTHS ->
                R.drawable.ic_profile_badge_premium_guild_subscription_lvl4_32dp
            monthsSubscribed >= GUILD_BOOST_LEVEL_3_MONTHS ->
                R.drawable.ic_profile_badge_premium_guild_subscription_lvl3_32dp
            monthsSubscribed >= GUILD_BOOST_LEVEL_2_MONTHS ->
                R.drawable.ic_profile_badge_premium_guild_subscription_lvl2_32dp
            else -> R.drawable.ic_profile_badge_premium_guild_subscription_lvl1_32dp
        }

        /**
         * Tap handler for a badge row.
         *
         * The reference routes an upsell badge into the premium purchase sheet
         * (`b.a.a.b.c` / `WidgetChoosePlan`), which is not reconstructed; until it is, an upsell
         * badge falls through to the same tooltip toast as every other badge rather than opening
         * nothing.
         */
        @JvmStatic
        fun onBadgeClick(fragmentManager: FragmentManager, context: Context): (Badge) -> Unit =
            { badge ->
                Toast.makeText(context, badge.tooltip ?: badge.text, Toast.LENGTH_SHORT).show()
            }
    }
}
