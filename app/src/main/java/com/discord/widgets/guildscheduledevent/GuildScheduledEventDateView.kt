package com.discord.widgets.guildscheduledevent

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.discord.R
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus

class GuildScheduledEventDateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val icon: ImageView
    private val text: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.guild_scheduled_event_date_view, this)
        icon = requireNotNull(findViewById(R.id.guild_scheduled_event_date_view_icon))
        text = requireNotNull(findViewById(R.id.guild_scheduled_event_date_view_text))
    }

    @JvmOverloads
    fun configure(event: GuildScheduledEvent, truncateDateTime: Boolean = true) = configure(
        event.scheduledStartTime.dateTimeMillis,
        event.scheduledEndTime?.dateTimeMillis,
        event.entityType,
        event.status,
        truncateDateTime,
    )

    @JvmOverloads
    fun configure(
        startTimeMillis: Long,
        endTimeMillis: Long?,
        entityType: GuildScheduledEventEntityType,
        status: GuildScheduledEventStatus,
        truncateDateTime: Boolean = true,
    ) {
        val done = status == GuildScheduledEventStatus.COMPLETED || status == GuildScheduledEventStatus.CANCELED
        val timing = ScheduledEventUi.timing(startTimeMillis, status)
        val live = timing == GuildScheduledEventTiming.LIVE
        icon.setImageResource(if (live) R.drawable.ic_event_20dp else R.drawable.ic_calendar_white_24dp)
        val color = when {
            live -> ContextCompat.getColor(context, R.color.status_green_500)
            done || timing == GuildScheduledEventTiming.SOON || timing == GuildScheduledEventTiming.NOW || timing == GuildScheduledEventTiming.EXPIRED -> themedColor(R.attr.colorInteractiveNormal)
            else -> themedColor(R.attr.colorHeaderSecondary)
        }
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))

        val startText = ScheduledEventUi.startingText(context, startTimeMillis, entityType, status)
        val endText = endTimeMillis?.let { ScheduledEventUi.endingText(context, startTimeMillis, it, status) }
        text.text = if (endText.isNullOrEmpty()) startText else "$startText - $endText"
        text.setTextColor(color)
        if (truncateDateTime) {
            text.ellipsize = TextUtils.TruncateAt.END
            text.maxLines = 1
        } else {
            text.ellipsize = null
            text.maxLines = Int.MAX_VALUE
        }
    }

    private fun themedColor(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }
}
