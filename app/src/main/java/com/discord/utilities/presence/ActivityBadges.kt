package com.discord.utilities.presence

import android.content.Context
import androidx.annotation.DrawableRes
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityParty
import com.discord.api.activity.ActivityType
import com.discord.i18n.FormatUtils

/** One chip under a rich-presence card: a glyph and a short label. */
data class ActivityBadge(@DrawableRes val icon: Int, val text: CharSequence, val style: Style) {
    enum class Style { MUTED, POSITIVE }
}

object ActivityBadges {

    /** Matches the `Season 1, Episode 2` shape Crunchyroll and friends put in `large_text`. */
    private val SEASON_EPISODE = Regex("""\w+ (\d+), \w+ (\d+)""")

    /**
     * The badges for [activity], in display order. Empty when the activity has nothing to add
     * beyond what the card already shows.
     */
    fun forActivity(context: Context, activity: Activity, elapsed: CharSequence?): List<ActivityBadge> =
        when (activity.type) {
            ActivityType.PLAYING -> listOfNotNull(
                partyBadge(context, activity),
                timestampBadge(activity, elapsed),
            )
            ActivityType.LISTENING -> listOfNotNull(timestampBadge(activity, elapsed))
            ActivityType.WATCHING -> listOfNotNull(
                timestampBadge(activity, elapsed),
                episodeBadge(context, activity),
            )
            else -> emptyList()
        }

    /** The seek bar already shows the elapsed time, so the badge stands down when it is present. */
    fun showsTimeBar(activity: Activity): Boolean =
        (activity.type == ActivityType.LISTENING || activity.type == ActivityType.WATCHING) &&
            (activity.timestamps?.startMs ?: 0L) > 0L &&
            (activity.timestamps?.endMs ?: 0L) > 0L

    private fun timestampBadge(activity: Activity, elapsed: CharSequence?): ActivityBadge? {
        if (elapsed.isNullOrEmpty() || showsTimeBar(activity)) return null
        val icon = when {
            activity.isEmbeddedActivity() -> R.drawable.ic_activity_embedded_24dp
            activity.type == ActivityType.WATCHING -> R.drawable.ic_activity_watching_24dp
            activity.type == ActivityType.LISTENING -> R.drawable.ic_activity_listening_24dp
            else -> R.drawable.ic_activity_playing_24dp
        }
        return ActivityBadge(icon, elapsed, ActivityBadge.Style.POSITIVE)
    }

    private fun partyBadge(context: Context, activity: Activity): ActivityBadge? {
        if (activity.isEmbeddedActivity()) return null
        val party = activity.party ?: return null
        val text = gameStateText(
            activity.state,
            party,
            context.getString(R.string.content_inventory_game_state_size),
            context.getString(R.string.content_inventory_game_state_size_no_max),
        ) ?: return null
        return ActivityBadge(R.drawable.ic_activity_party_24dp, text, ActivityBadge.Style.MUTED)
    }

    private fun episodeBadge(context: Context, activity: Activity): ActivityBadge? {
        val text = seasonEpisodeText(
            activity.assets?.largeText,
            context.getString(R.string.member_list_content_feed_watch_season_episode),
        ) ?: return null
        return ActivityBadge(R.drawable.ic_activity_episode_24dp, text, ActivityBadge.Style.MUTED)
    }

    /** `In Game (1 of 4)`, or whichever half of that the activity actually declared. */
    internal fun gameStateText(
        state: String?,
        party: ActivityParty?,
        sizeTemplate: String,
        noMaxTemplate: String,
    ): CharSequence? {
        val count = party?.size?.getOrNull(0) ?: 0L
        val max = party?.size?.getOrNull(1) ?: 0L
        val partySize = when {
            count > 0L && max > 0L -> FormatUtils.format(
                sizeTemplate,
                "count" to count.toString(),
                "max" to max.toString(),
            )
            count > 0L -> FormatUtils.format(noMaxTemplate, "count" to count.toString())
            else -> null
        }
        val label = state?.takeIf { it.isNotEmpty() }
        return when {
            label != null && partySize != null -> "$label ($partySize)"
            else -> label ?: partySize
        }
    }

    internal fun seasonEpisodeText(largeText: String?, template: String): CharSequence? {
        val match = SEASON_EPISODE.find(largeText.orEmpty()) ?: return null
        return FormatUtils.format(
            template,
            "seasonNum" to match.groupValues[1],
            "episodeNum" to match.groupValues[2],
        )
    }
}
