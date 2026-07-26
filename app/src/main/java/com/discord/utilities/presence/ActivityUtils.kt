@file:JvmName("ActivityUtils")

package com.discord.utilities.presence

import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityEmoji
import com.discord.api.activity.ActivityPlatform
import com.discord.api.activity.ActivityType

/** The application id Discord uses for the synthetic "listening in a stage" presence. */
internal const val STAGE_PRESENCE_APPLICATION_ID = 834488117758001152L

private const val XBOX_APPLICATION_ID = 438122941302046720L

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

fun Activity.isSpotifyActivity(): Boolean = name.equals(SPOTIFY_ACTIVITY_NAME, ignoreCase = true)

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
