package com.discord.utilities.platform

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
        properName = "Twitter",
        colorResId = R.color.twitter,
        themedPlatformImage = R.attr.ic_account_twitter,
        whitePlatformImage = R.drawable.ic_account_twitter_white_24dp,
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

    /**
     * "no service". Declared last to match the reference's `$VALUES` order — Vineflower prints the
     * constants alphabetically, but `getProfileUrl`'s ordinal switch proves `NONE` is the final
     * constant.
     */
    NONE,
    ;

    /** Wire identifier for this service — the lowercase enum name. */
    val platformId: String = name.lowercase(Locale.ENGLISH)

    /**
     * Web profile for [connectedAccount] on this service, or `null` where the service has no
     * addressable public profile (or the account link is not visible off-platform).
     */
    fun getProfileUrl(connectedAccount: ConnectedAccount): String? = when (this) {
        GITHUB -> "https://github.com/${connectedAccount.name}"
        REDDIT -> "https://reddit.com/u/${connectedAccount.name}"
        SPOTIFY -> "https://open.spotify.com/user/${connectedAccount.id}"
        STEAM -> "https://steamcommunity.com/profiles/${connectedAccount.id}"
        TWITCH -> "https://twitch.tv/${connectedAccount.name}"
        TWITTER -> "https://www.twitter.com/${connectedAccount.name}"
        YOUTUBE -> "https://youtube.com/channel/${connectedAccount.id}"
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

        /**
         * Resolves a wire platform name. Unknown, blank and malformed names all yield [NONE] —
         * the server adds services faster than clients ship.
         */
        @JvmStatic
        fun from(platformName: String): Platform =
            runCatching { valueOf(platformName.trim().uppercase(Locale.ENGLISH)) }.getOrDefault(NONE)
    }
}
