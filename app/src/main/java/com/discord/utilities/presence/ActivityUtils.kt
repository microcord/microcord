@file:JvmName("ActivityUtils")

package com.discord.utilities.presence

import androidx.annotation.DrawableRes
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityEmoji
import com.discord.api.activity.ActivityFlags
import com.discord.api.activity.ActivityPlatform
import com.discord.api.activity.ActivityType
import com.discord.api.activity.StatusDisplayType

/** The application id Discord uses for the synthetic "listening in a stage" presence. */
internal const val STAGE_PRESENCE_APPLICATION_ID = 834488117758001152L

private const val XBOX_APPLICATION_ID = 438122941302046720L

private const val CRUNCHYROLL_APPLICATION_ID = 981509069309354054L

private const val SPOTIFY_PARTY_PREFIX = "spotify:"

/**
 * The `name` Discord reports for a Spotify listening activity.
 *
 * The reference compares against `Platform.SPOTIFY.properName`; `utilities/platform` has
 * not been reconstructed yet, so the literal lives here until it has.
 */
private const val SPOTIFY_ACTIVITY_NAME = "Spotify"

fun Activity.hasFlag(flag: Int): Boolean = ((flags ?: 0) and flag) == flag

fun Activity.isCustomStatus(): Boolean = type == ActivityType.CUSTOM_STATUS

fun Activity.isGameActivity(): Boolean = type == ActivityType.PLAYING

fun Activity.isStreaming(): Boolean = type == ActivityType.STREAMING

/** Whether the activity was reported by this device's platform. */
fun Activity.isCurrentPlatform(): Boolean =
    platform == ActivityPlatform.ANDROID ||
        supportedPlatforms?.contains(ActivityPlatform.ANDROID) == true

/**
 * Whether the activity carries enough detail to render a rich-presence card rather than
 * a single line of text.
 */
fun Activity.isRichPresence(): Boolean = !isCustomStatus() &&
    (assets != null || party != null || !state.isNullOrEmpty() || !details.isNullOrEmpty())

/**
 * A genuine Spotify listening session, which is what unlocks the album art, track link and artist
 * links. A bare activity merely named "Spotify" renders as an ordinary card.
 */
fun Activity.isSpotifyActivity(): Boolean = type == ActivityType.LISTENING &&
    name == SPOTIFY_ACTIVITY_NAME &&
    party?.id?.startsWith(SPOTIFY_PARTY_PREFIX) == true

fun Activity.isEmbeddedActivity(): Boolean = hasFlag(ActivityFlags.EMBEDDED)

fun Activity.isCrunchyrollActivity(): Boolean = applicationId == CRUNCHYROLL_APPLICATION_ID

fun Activity.isHangStatus(): Boolean = type == ActivityType.HANG

/** Spotify packs the artist list into `state`, semicolon separated. */
fun Activity.spotifyArtists(): String? =
    state?.trim()?.takeIf { it.isNotEmpty() }?.split("; ")?.joinToString(", ")

/**
 * The text the member list shows for this activity, with no "Playing …" wrapper.
 *
 * @param embeddedFallback shown for an embedded activity that did not name itself.
 */
fun Activity.statusText(embeddedFallback: String): String? = when {
    // A custom status carries its text in `state`; its `name` is the literal "Custom Status".
    type == ActivityType.CUSTOM_STATUS -> state?.takeIf { it.isNotEmpty() }
    isEmbeddedActivity() -> name.takeIf { it.isNotEmpty() } ?: embeddedFallback
    isSpotifyActivity() -> spotifyArtists()
    isStageChannelActivity() -> name.takeIf { it.isNotEmpty() }
    isCrunchyrollActivity() -> details?.takeIf { it.isNotEmpty() }
    else -> statusDisplayText()
}

/** The phrasing to wrap [statusText] in; a stage or Crunchyroll session reads as "Watching". */
fun Activity.statusPhrasing(): ActivityType = when {
    isStageChannelActivity() || isCrunchyrollActivity() -> ActivityType.WATCHING
    else -> type
}

/** The glyph shown beside the status text. */
@DrawableRes
fun Activity.statusIcon(): Int? = when {
    isEmbeddedActivity() -> R.drawable.ic_activity_embedded_24dp
    type == ActivityType.PLAYING || type == ActivityType.COMPETING ->
        R.drawable.ic_activity_playing_24dp
    type == ActivityType.LISTENING -> R.drawable.ic_activity_listening_24dp
    type == ActivityType.WATCHING || type == ActivityType.STREAMING ->
        R.drawable.ic_activity_watching_24dp
    else -> null
}

/**
 * The bare text the status line shows for this activity, before it is wrapped in "Playing …" and
 * friends. Honours [Activity.statusDisplayType]; only a streaming activity prefers `details`.
 */
fun Activity.statusDisplayText(): String? {
    val name = name.takeIf { it.isNotEmpty() }
    val details = details?.takeIf { it.isNotEmpty() }
    val state = state?.takeIf { it.isNotEmpty() }
    return when {
        statusDisplayType == StatusDisplayType.NAME && name != null -> name
        statusDisplayType == StatusDisplayType.STATE && state != null -> state
        statusDisplayType == StatusDisplayType.DETAILS && details != null -> details
        type == ActivityType.STREAMING -> details ?: name
        else -> name
    }
}

fun Activity.isStageChannelActivity(): Boolean =
    type == ActivityType.LISTENING && applicationId == STAGE_PRESENCE_APPLICATION_ID

fun Activity.isXboxActivity(): Boolean =
    platform == ActivityPlatform.XBOX || applicationId == XBOX_APPLICATION_ID

/** Console platforms and Samsung report games through a dedicated platform value. */
fun Activity.isGamePlatform(): Boolean = isXboxActivity() ||
    platform == ActivityPlatform.SAMSUNG ||
    platform == ActivityPlatform.PS4 ||
    platform == ActivityPlatform.PS5

/** The platform a playing activity is running on, or `null` when it is not a game. */
fun Activity.getGamePlatform(): ActivityPlatform? {
    if (!isGameActivity()) return null
    if (isXboxActivity()) return ActivityPlatform.XBOX
    return platform ?: ActivityPlatform.DESKTOP
}

/** Builds the local activity that publishes this user's custom status. */
fun createCustomStatusActivity(text: String?, emoji: ActivityEmoji?, createdAt: Long): Activity =
    Activity(
        name = "Custom Status",
        type = ActivityType.CUSTOM_STATUS,
        url = null,
        createdAt = createdAt,
        timestamps = null,
        applicationId = null,
        details = null,
        state = text,
        emoji = emoji,
        party = null,
        assets = null,
        flags = null,
        syncId = null,
        sessionId = null,
        metadata = null,
        platform = null,
        supportedPlatforms = null,
        buttons = null,
    )

/** Builds the local activity that publishes a game this device is playing. */
fun createPlayingActivity(name: String?, createdAt: Long): Activity? {
    if (name == null) return null
    return Activity(
        name = name,
        type = ActivityType.PLAYING,
        url = null,
        createdAt = createdAt,
        timestamps = null,
        applicationId = null,
        details = null,
        state = null,
        emoji = null,
        party = null,
        assets = null,
        flags = null,
        syncId = null,
        sessionId = null,
        metadata = null,
        platform = ActivityPlatform.ANDROID,
        supportedPlatforms = null,
        buttons = null,
    )
}
