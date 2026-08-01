package com.discord.views.calls

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.views.ThemedColor
import com.discord.widgets.guildscheduledevent.ScheduledEventUi
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventListBottomSheet

class CallEventsButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val content: ConstraintLayout
    private val icon: ImageView
    private val text: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_call_events_button, this)
        content = requireNotNull(findViewById<ImageView>(R.id.icon).parent as? ConstraintLayout)
        icon = requireNotNull(findViewById(R.id.icon))
        text = requireNotNull(findViewById(R.id.text))
    }

    fun configure(
        fragmentManager: FragmentManager,
        guildId: Long,
        channelId: Long,
        events: List<GuildScheduledEvent>,
    ) {
        content.setOnClickListener {
            WidgetGuildScheduledEventListBottomSheet.show(fragmentManager, guildId, channelId)
        }
        if (ScheduledEventUi.hasLiveEvent(events)) {
            text.setText(R.string.stage_channel_live_now)
            setContentColor(ContextCompat.getColor(context, R.color.status_green_500))
        } else {
            val count = resources.getQuantityString(R.plurals.guild_events_plural_number, events.size)
                .replace("{#}", events.size.toString())
            text.text = context.getString(R.string.guild_events_list_action_sheet_header).replace("{count}", count)
            setContentColor(ThemedColor.resolve(context, R.attr.colorInteractiveNormal))
        }
    }

    fun a(
        fragmentManager: FragmentManager,
        guildId: Long,
        channelId: Long,
        events: List<GuildScheduledEvent>,
    ) = configure(fragmentManager, guildId, channelId, events)

    private fun setContentColor(color: Int) {
        text.setTextColor(color)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
    }
}
