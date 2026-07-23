package com.discord.widgets.guildscheduledevent.buttonconfiguration

import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.widgets.guildscheduledevent.GuildScheduledEventTiming
import com.discord.widgets.guildscheduledevent.ScheduledEventUi

data class DirectoryButtonConfiguration(
    val guildScheduledEvent: GuildScheduledEvent,
    val isInGuild: Boolean,
    val isRsvped: Boolean,
    val isDetailView: Boolean,
    override val primaryButtonOnClickListener: View.OnClickListener?,
    override val secondaryButtonOnClickListener: View.OnClickListener?,
    override val shareButtonOnClickListener: View.OnClickListener?,
    override val extrasButtonOnClickListener: View.OnClickListener?,
) : ButtonConfiguration {
    private val complete = guildScheduledEvent.status == GuildScheduledEventStatus.COMPLETED
    private val active = ScheduledEventUi.timing(guildScheduledEvent) == GuildScheduledEventTiming.LIVE

    override val primaryButtonVisible = !isDetailView || active || !isInGuild
    override val secondaryButtonBackground = if (isRsvped) {
        R.drawable.bg_guild_scheduled_event_list_item_interested_button_interested
    } else {
        R.drawable.bg_guild_scheduled_event_list_item_interested_button_default
    }
    override val secondaryButtonEnabled = true
    override val secondaryButtonTextDrawableRes = R.drawable.ic_bell_16dp
    override val secondaryButtonVisible = isInGuild && !active && !complete
    override val isShareVisible = isInGuild
    override val isExtrasVisible = true
    override val isAnyButtonVisible = true

    override fun primaryButtonText(context: Context): CharSequence =
        if (isInGuild) context.getText(R.string.guild_scheduled_event_join_live)
        else guildScheduledEvent.guild?.name ?: context.getText(R.string.join)

    override fun secondaryButtonText(context: Context) = context.getText(R.string.indicate_rsvp)

    override fun secondaryButtonTextColor(context: Context): Int {
        if (isRsvped && !active && !complete) return themedColor(context, R.attr.color_brand)
        return ContextCompat.getColor(context, R.color.white)
    }

    private fun themedColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }
}
