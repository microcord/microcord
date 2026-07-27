package com.discord.widgets.stage.start

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.models.guild.UserGuildMember
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

/**
 * A row on the "what would you like to start?" screen a moderator sees on entering an idle stage.
 *
 * [ListItem] rows all render the same way — icon on a coloured disc, title, subtitle — so they share
 * their icon fields; what differs is only where the title and subtitle come from. A [ListItem.Event]
 * takes them from a real scheduled event, a [ListItem.StaticOption] from fixed string resources.
 */
sealed class ModeratorStartStageItem : MGRecyclerDataPayload {

    data class Header(
        override val type: Int = TYPE_HEADER,
        override val key: String = KEY_HEADER,
    ) : ModeratorStartStageItem()

    data class Continue(
        override val type: Int = TYPE_CONTINUE,
        override val key: String = KEY_CONTINUE,
    ) : ModeratorStartStageItem()

    sealed class ListItem(
        override val key: String,
        @DrawableRes val iconRes: Int,
        @ColorRes val iconBgColorRes: Int,
    ) : ModeratorStartStageItem() {

        override val type: Int = TYPE_LIST_ITEM

        /** An already-scheduled event that is close enough to its start time to be startable. */
        data class Event(val event: GuildScheduledEvent) : ListItem(
            key = event.id.toString(),
            iconRes = R.drawable.ic_event_20dp,
            iconBgColorRes = R.color.status_green_600,
        )

        /** A fixed choice whose copy comes from string resources rather than from an event. */
        sealed class StaticOption(
            key: String,
            @DrawableRes iconRes: Int,
            @ColorRes iconBgColorRes: Int,
            @StringRes val titleRes: Int,
            @StringRes val subtitleRes: Int,
        ) : ListItem(key = key, iconRes = iconRes, iconBgColorRes = iconBgColorRes) {

            /** Open the stage right now. */
            object StartStage : StaticOption(
                key = KEY_START_STAGE,
                iconRes = R.drawable.ic_channel_stage_24dp,
                iconBgColorRes = R.color.status_green_600,
                titleRes = R.string.stage_channel_start_title,
                subtitleRes = R.string.stage_channel_start_subtitle,
            )

            /** Create an event for later instead of going live now. */
            object ScheduleEvent : StaticOption(
                key = KEY_SCHEDULE_EVENT,
                iconRes = R.drawable.ic_event_20dp,
                iconBgColorRes = R.color.brand_new_500,
                titleRes = R.string.schedule_event,
                subtitleRes = R.string.schedule_event_description,
            )
        }
    }

    /** "Alice, Bob, and 2 others are currently waiting." Capped at [MAX_DISPLAY_WAITING_USERS]. */
    data class Waiting(
        val users: List<UserGuildMember>,
        override val type: Int = TYPE_WAITING,
        override val key: String = KEY_WAITING,
    ) : ModeratorStartStageItem()

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_LIST_ITEM = 1
        const val TYPE_CONTINUE = 2
        const val TYPE_WAITING = 3

        const val KEY_START_STAGE = "KEY_START_STAGE"
        const val KEY_SCHEDULE_EVENT = "KEY_SCHEDULE_EVENT"
        private const val KEY_HEADER = "KEY_CREATE_STAGE_EVENT_HEADER"
        private const val KEY_CONTINUE = "KEY_CREATE_STAGE_EVENT_CONTINUE"
        private const val KEY_WAITING = "KEY_CREATE_STAGE_EVENT_WAITING"

        /** How many waiting participants are named before the rest collapse into "+N others". */
        const val MAX_DISPLAY_WAITING_USERS = 3
    }
}
