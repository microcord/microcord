package com.discord.widgets.guildscheduledevent

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.discord.R
import com.discord.api.guildscheduledevent.GuildScheduledEvent

class GuildEventPromptListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    data class ScheduledEventData(
        val guildScheduledEvent: GuildScheduledEvent,
        val onScheduledEventClick: () -> Unit,
    )

    private val startEvent: GuildEventPromptView
    private val createEvent: GuildEventPromptView
    private val startStage: GuildEventPromptView

    init {
        LayoutInflater.from(context).inflate(R.layout.guild_event_prompt_list_view, this)
        startEvent = requireNotNull(findViewById(R.id.start_event_button))
        createEvent = requireNotNull(findViewById(R.id.create_event_button))
        startStage = requireNotNull(findViewById(R.id.start_stage_button))
    }

    @JvmOverloads
    fun configure(
        scheduledEventData: ScheduledEventData? = null,
        onCreateEventClick: (() -> Unit)? = null,
        onStartStageClick: (() -> Unit)? = null,
    ) {
        startStage.bind(
            onStartStageClick,
            R.drawable.ic_stage_20dp,
            R.color.status_green_500,
            context.getString(R.string.stage_channel_start_title),
            context.getString(R.string.stage_channel_start_subtitle),
        )
        createEvent.bind(
            onCreateEventClick,
            R.drawable.ic_calendar_white_24dp,
            R.color.brand_500,
            context.getString(R.string.schedule_event),
            context.getString(R.string.schedule_event_description),
        )
        startEvent.bind(
            scheduledEventData?.onScheduledEventClick,
            R.drawable.ic_calendar_white_24dp,
            R.color.status_green_500,
            scheduledEventData?.guildScheduledEvent?.name.orEmpty(),
            scheduledEventData?.guildScheduledEvent?.let { ScheduledEventUi.startingText(context, it) } ?: "",
        )
    }

    private fun GuildEventPromptView.bind(
        listener: (() -> Unit)?,
        icon: Int,
        backgroundColor: Int,
        title: CharSequence,
        subtitle: CharSequence,
    ) {
        visibility = if (listener == null) View.GONE else View.VISIBLE
        configure(icon, backgroundColor, title, subtitle)
        setOnClickListener(if (listener == null) null else View.OnClickListener { listener() })
    }
}
