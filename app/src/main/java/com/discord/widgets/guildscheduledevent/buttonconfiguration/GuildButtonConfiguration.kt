package com.discord.widgets.guildscheduledevent.buttonconfiguration

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.views.ThemedColor
import com.discord.widgets.guildscheduledevent.GuildScheduledEventTiming
import com.discord.widgets.guildscheduledevent.ScheduledEventUi

data class GuildButtonConfiguration(
    val guildScheduledEvent: GuildScheduledEvent,
    val canRsvp: Boolean,
    val canStartEvent: Boolean,
    val isConnected: Boolean,
    val canConnect: Boolean,
    val isInGuild: Boolean,
    val isRsvped: Boolean,
    val isDetailView: Boolean = false,
    val onRsvpButtonClicked: View.OnClickListener? = null,
    val onJoinButtonClicked: View.OnClickListener? = null,
    val onEndEventButtonClicked: View.OnClickListener? = null,
    val onStartEventButtonClicked: View.OnClickListener? = null,
    val onShareButtonClicked: View.OnClickListener? = null,
    val onExtraButtonClicked: View.OnClickListener? = null,
) : ButtonConfiguration {
    private val timing = ScheduledEventUi.timing(guildScheduledEvent)
    private val isComplete = guildScheduledEvent.status == GuildScheduledEventStatus.COMPLETED
    private val isActive = timing == GuildScheduledEventTiming.LIVE
    private val showEnd = isDetailView && isActive && isConnected && canStartEvent

    override val primaryButtonVisible = canStartEvent && !isComplete && timing.isStartable()
    override val primaryButtonOnClickListener = onStartEventButtonClicked
    override val secondaryButtonVisible = canRsvp
    override val secondaryButtonOnClickListener = when {
        showEnd -> onEndEventButtonClicked
        isActive -> onJoinButtonClicked
        else -> onRsvpButtonClicked
    }
    override val secondaryButtonEnabled = !isComplete &&
        !(isActive && guildScheduledEvent.entityType != GuildScheduledEventEntityType.EXTERNAL && !canConnect)
    override val secondaryButtonTextDrawableRes = when {
        isActive || !secondaryButtonEnabled -> 0
        isRsvped -> R.drawable.ic_bell_16dp
        else -> R.drawable.ic_bell_16dp
    }
    override val secondaryButtonBackground = when {
        !secondaryButtonEnabled -> R.drawable.bg_guild_scheduled_event_list_item_interested_button_disabled
        isActive -> R.drawable.bg_guild_scheduled_event_list_item_interested_button_active
        isRsvped -> R.drawable.bg_guild_scheduled_event_list_item_interested_button_interested
        else -> R.drawable.bg_guild_scheduled_event_list_item_interested_button_default
    }
    override val isShareVisible = !isComplete
    override val shareButtonOnClickListener = onShareButtonClicked
    override val isExtrasVisible = true
    override val extrasButtonOnClickListener = onExtraButtonClicked
    override val isAnyButtonVisible = primaryButtonVisible || secondaryButtonVisible || showEnd || isShareVisible

    override fun primaryButtonText(context: Context) = context.getText(R.string.start_event)

    override fun secondaryButtonText(context: Context): CharSequence = when {
        isActive && guildScheduledEvent.entityType == GuildScheduledEventEntityType.EXTERNAL -> context.getText(R.string.guild_scheduled_event_join_live)
        isActive && !canConnect -> context.getText(R.string.guild_scheduled_event_live)
        showEnd -> context.getText(R.string.end_event)
        isActive && isConnected -> context.getText(R.string.guild_scheduled_event_live)
        isActive -> context.getText(R.string.join)
        else -> context.getText(R.string.indicate_rsvp)
    }

    override fun secondaryButtonTextColor(context: Context): Int {
        if (!secondaryButtonEnabled) return ContextCompat.getColor(context, R.color.white_alpha_40)
        if (isRsvped && !isActive && !isComplete) return ThemedColor.resolve(context, R.attr.color_brand)
        return ContextCompat.getColor(context, R.color.white)
    }

}
