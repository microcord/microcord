package com.discord.utilities.platform

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.discord.R
import com.discord.api.activity.ActivityPlatform
import com.discord.api.connectedaccounts.ConnectedAccount
import java.util.Locale

/**
 * The external services Discord can link an account to, or observe an activity from.
 *
 * A single enum backs three separate surfaces: connected-account rows in a profile, the
 * "Connect <service>" button on a platform rich-presence card, and the coloured brand chrome each
 * of those uses. [NONE] is the miss value — every lookup here resolves rather than throws, because
 * the server is free to send a service this build has never heard of.
 *
 * [platformId] is the wire name (lowercase enum name) and is what the REST/OAuth endpoints expect.
 */
enum class Platform(
    /** Human-facing service name; empty for [NONE]. */
    val properName: String = "",
    /** Brand colour, where the service has one. */
    @ColorRes val colorResId: Int? = null,
    /** Theme attribute pointing at the light/dark variant of the service glyph. */
    @AttrRes val themedPlatformImage: Int? = null,
    /** Flat white glyph, used where the badge sits on the service's own brand colour. */
    @DrawableRes val whitePlatformImage: Int? = null,
    /** The service can seed friend suggestions. */
    val canSyncFriends: Boolean = false,
    /** The service can drive a rich-presence card. */
    val canShowActivity: Boolean = false,
    /** The account link is offered in this build. */
    val enabled: Boolean = false,
    private val wireName: String? = null,
) {
    BATTLENET(
        properName = "Battle.net",
        colorResId = R.color.battlenet,
        themedPlatformImage = R.attr.ic_account_battlenet,
        whitePlatformImage = R.drawable.ic_account_battlenet_white_24dp,
    ),
    FACEBOOK(
        properName = "Facebook",
        colorResId = R.color.facebook,
        themedPlatformImage = R.attr.ic_account_facebook,
        whitePlatformImage = R.drawable.ic_account_facebook_white_24dp,
        enabled = true,
    ),
    GITHUB(
        properName = "GitHub",
        colorResId = R.color.github,
        themedPlatformImage = R.attr.ic_account_github,
        whitePlatformImage = R.drawable.ic_account_github_white_24dp,
        enabled = true,
    ),
    LEAGUEOFLEGENDS(
        properName = "League of Legends",
        colorResId = R.color.league_of_legends,
        themedPlatformImage = R.attr.ic_account_league_of_legends,
        whitePlatformImage = R.drawable.ic_account_league_of_legends_white_24dp,
    ),
    PLAYSTATION(
        properName = "PlayStation Network",
        colorResId = R.color.playstation,
        themedPlatformImage = R.attr.ic_account_playstation,
        whitePlatformImage = R.drawable.ic_account_playstation_white_24dp,
        canShowActivity = true,
        enabled = true,
    ),
    REDDIT(
        properName = "Reddit",
        colorResId = R.color.reddit,
        themedPlatformImage = R.attr.ic_account_reddit,
        whitePlatformImage = R.drawable.ic_account_reddit_white_24dp,
        enabled = true,
    ),
    SAMSUNG(
        properName = "Samsung Galaxy",
        themedPlatformImage = R.attr.ic_account_samsung,
        whitePlatformImage = R.drawable.ic_account_samsung_white_24dp,
        canShowActivity = true,
    ),
    SKYPE(
        properName = "Skype",
        colorResId = R.color.skype,
        themedPlatformImage = R.attr.ic_account_skype,
        whitePlatformImage = R.drawable.ic_account_skype_white_24dp,
    ),
    SPOTIFY(
        properName = "Spotify",
        colorResId = R.color.spotify,
        themedPlatformImage = R.attr.ic_account_spotify,
        whitePlatformImage = R.drawable.ic_account_spotify_white_24dp,
        canShowActivity = true,
        enabled = true,
    ),
    STEAM(
        properName = "Steam",
        colorResId = R.color.steam,
        themedPlatformImage = R.attr.ic_account_steam,
        whitePlatformImage = R.drawable.ic_account_steam_white_24dp,
        enabled = true,
    ),
    TWITCH(
        properName = "Twitch",
        colorResId = R.color.twitch,
        themedPlatformImage = R.attr.ic_account_twitch,
        whitePlatformImage = R.drawable.ic_account_twitch_white_24dp,
        enabled = true,
    ),
    TWITTER(
        properName = "X",
        colorResId = R.color.twitter,
        themedPlatformImage = R.attr.ic_account_x,
        whitePlatformImage = R.drawable.ic_account_x_white_24dp,
        enabled = true,
    ),
    XBOX(
        properName = "Xbox",
        colorResId = R.color.xbox,
        themedPlatformImage = R.attr.ic_account_xbox,
        whitePlatformImage = R.drawable.ic_account_xbox_white_24dp,
        canShowActivity = true,
        enabled = true,
    ),
    YOUTUBE(
        properName = "YouTube",
        colorResId = R.color.youtube,
        themedPlatformImage = R.attr.ic_account_youtube,
        whitePlatformImage = R.drawable.ic_account_youtube_white_24dp,
        enabled = true,
    ),

    AMAZON_MUSIC(
        properName = "Amazon Music",
        themedPlatformImage = R.attr.ic_account_amazon_music,
        enabled = true,
        wireName = "amazon-music",
    ),
    BLUESKY(
        properName = "Bluesky",
        themedPlatformImage = R.attr.ic_account_bluesky,
        whitePlatformImage = R.drawable.ic_account_bluesky_white_24dp,
        enabled = true,
    ),
    BUNGIE(
        properName = "Bungie.net",
        themedPlatformImage = R.attr.ic_account_bungie,
        whitePlatformImage = R.drawable.ic_account_bungie_white_24dp,
        enabled = true,
    ),
    CRUNCHYROLL(
        properName = "Crunchyroll",
        themedPlatformImage = R.attr.ic_account_crunchyroll,
        whitePlatformImage = R.drawable.ic_account_crunchyroll_white_24dp,
        enabled = true,
    ),
    DOMAIN(
        properName = "Domain",
        themedPlatformImage = R.attr.ic_account_domain,
        whitePlatformImage = R.drawable.ic_account_domain_white_24dp,
        enabled = true,
    ),
    EBAY(
        properName = "eBay",
        themedPlatformImage = R.attr.ic_account_ebay,
        whitePlatformImage = R.drawable.ic_account_ebay_white_24dp,
        enabled = true,
    ),
    EPIC_GAMES(
        properName = "Epic Games",
        themedPlatformImage = R.attr.ic_account_epic_games,
        whitePlatformImage = R.drawable.ic_account_epic_games_white_24dp,
        enabled = true,
        wireName = "epicgames",
    ),
    INSTAGRAM(
        properName = "Instagram",
        themedPlatformImage = R.attr.ic_account_instagram,
        whitePlatformImage = R.drawable.ic_account_instagram_white_24dp,
    ),
    MASTODON(
        properName = "Mastodon",
        themedPlatformImage = R.attr.ic_account_mastodon,
        whitePlatformImage = R.drawable.ic_account_mastodon_white_24dp,
    ),
    META_QUEST_OR_HORIZON(
        properName = "Meta Quest",
        themedPlatformImage = R.attr.ic_account_meta_quest,
        whitePlatformImage = R.drawable.ic_account_meta_quest_white_24dp,
        wireName = "meta_quest_or_horizon",
    ),
    PAYPAL(
        properName = "PayPal",
        themedPlatformImage = R.attr.ic_account_paypal,
        whitePlatformImage = R.drawable.ic_account_paypal_white_24dp,
        enabled = true,
    ),
    PLAYSTATION_STAGING(
        properName = "PlayStation Network (Staging)",
        colorResId = R.color.playstation,
        themedPlatformImage = R.attr.ic_account_playstation,
        whitePlatformImage = R.drawable.ic_account_playstation_white_24dp,
        wireName = "playstation-stg",
    ),
    RIOT_GAMES(
        properName = "Riot Games",
        themedPlatformImage = R.attr.ic_account_riot_games,
        whitePlatformImage = R.drawable.ic_account_riot_games_white_24dp,
        enabled = true,
        wireName = "riotgames",
    ),
    ROBLOX(
        properName = "Roblox",
        themedPlatformImage = R.attr.ic_account_roblox,
        whitePlatformImage = R.drawable.ic_account_roblox_white_24dp,
        enabled = true,
    ),
    TIKTOK(
        properName = "TikTok",
        themedPlatformImage = R.attr.ic_account_tiktok,
        whitePlatformImage = R.drawable.ic_account_tiktok_white_24dp,
    ),

    /**
     * "no service". Declared last to match the reference's `$VALUES` order — Vineflower prints the
     * constants alphabetically, but `getProfileUrl`'s ordinal switch proves `NONE` is the final
     * constant.
     */
    NONE,
    ;

    /** Wire identifier for this service, as the REST/OAuth endpoints expect it. */
    val platformId: String = wireName ?: name.lowercase(Locale.ENGLISH)

    /**
     * Web profile for [connectedAccount] on this service, or `null` where the service has no
     * addressable public profile (or the account link is not visible off-platform).
     */
    fun getProfileUrl(connectedAccount: ConnectedAccount): String? = when (this) {
        BLUESKY -> "https://bsky.app/profile/${connectedAccount.id}"
        DOMAIN -> "https://${connectedAccount.id}/"
        EBAY -> "https://www.ebay.com/usr/${connectedAccount.name}"
        GITHUB -> "https://github.com/${connectedAccount.name}"
        INSTAGRAM -> "https://www.instagram.com/${connectedAccount.name}"
        MASTODON -> connectedAccount.id
        REDDIT -> "https://www.reddit.com/u/${connectedAccount.name}"
        ROBLOX -> "https://roblox.com/users/${connectedAccount.id}/profile"
        SPOTIFY -> "https://open.spotify.com/user/${connectedAccount.id}"
        STEAM -> "https://steamcommunity.com/profiles/${connectedAccount.id}"
        TIKTOK -> "https://www.tiktok.com/@${connectedAccount.name}"
        TWITCH -> "https://www.twitch.tv/${connectedAccount.name}"
        TWITTER -> "https://x.com/${connectedAccount.name}"
        YOUTUBE -> "https://www.youtube.com/channel/${connectedAccount.id}"
        else -> null
    }

    companion object {

        /**
         * The service behind a presence's platform tag. Only the platforms that can produce a rich
         * presence map; everything else — including the desktop/mobile clients — is [NONE].
         */
        @JvmStatic
        fun from(activityPlatform: ActivityPlatform?): Platform = when (activityPlatform) {
            ActivityPlatform.XBOX -> XBOX
            ActivityPlatform.SAMSUNG -> SAMSUNG
            ActivityPlatform.SPOTIFY -> SPOTIFY
            ActivityPlatform.PS4, ActivityPlatform.PS5 -> PLAYSTATION
            else -> NONE
        }

        @JvmStatic
        fun from(connectedAccount: ConnectedAccount): Platform = from(connectedAccount.type)

        private val byPlatformId = entries.associateBy(Platform::platformId)

        /** Unknown, blank and malformed names all yield [NONE]. */
        @JvmStatic
        fun from(platformName: String): Platform =
            byPlatformId[platformName.trim().lowercase(Locale.ENGLISH)] ?: NONE
    }
}

/** The service glyph resolved against [context]'s theme, or a neutral one where there is none. */
@DrawableRes
fun Platform.resolvePlatformImage(context: Context): Int {
    val attribute = themedPlatformImage ?: return R.drawable.ic_account_circle_grey_24dp
    val value = TypedValue()
    return if (context.theme.resolveAttribute(attribute, value, true)) {
        value.resourceId
    } else {
        R.drawable.ic_account_circle_grey_24dp
    }
}
