package com.discord.views.channelsidebar

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.discord.R
import com.discord.utilities.feature.ChannelSidebarCustomization
import com.google.android.material.button.MaterialButton

class GuildChannelSideBarActionsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val search: MaterialButton
    private val threads: MaterialButton
    private val notifications: MaterialButton
    private val media: MaterialButton
    private val pins: MaterialButton
    private val settings: MaterialButton

    init {
        LayoutInflater.from(context).inflate(R.layout.guild_channel_side_bar_actions_view, this)
        search = requireNotNull(findViewById(R.id.channel_sidebar_actions_search))
        threads = requireNotNull(findViewById(R.id.channel_sidebar_actions_threads))
        notifications = requireNotNull(findViewById(R.id.channel_sidebar_actions_notifications))
        media = requireNotNull(findViewById(R.id.channel_sidebar_actions_media))
        pins = requireNotNull(findViewById(R.id.channel_sidebar_actions_pins))
        settings = requireNotNull(findViewById(R.id.channel_sidebar_actions_settings))
        configure()
    }

    @JvmOverloads
    fun configure(
        onSearchClicked: (View) -> Unit = {},
        onThreadBrowserClicked: (View) -> Unit = {},
        onNotificationsClicked: (View) -> Unit = {},
        onPinsClicked: (View) -> Unit = {},
        onSettingsClicked: (View) -> Unit = {},
        onMediaClicked: (View) -> Unit = {},
        hasUnseenPins: Boolean = false,
        channelIsMuted: Boolean = false,
        disablePins: Boolean = false,
        showThreads: Boolean = true,
    ) {
        setTopDrawable(pins, themedResource(if (hasUnseenPins) R.attr.ic_sidebar_pins_on_24dp else R.attr.ic_sidebar_pins_off_24dp))
        setTopDrawable(
            notifications,
            themedResource(
                if (channelIsMuted) R.attr.ic_sidebar_notifications_off_24dp
                else R.attr.ic_sidebar_notifications_on_24dp,
            ),
        )
        search.setOnClickListener(onSearchClicked)
        threads.setOnClickListener(onThreadBrowserClicked)
        pins.isEnabled = !disablePins
        pins.setOnClickListener(onPinsClicked)
        notifications.setOnClickListener(onNotificationsClicked)
        notifications.contentDescription = context.getText(
            if (channelIsMuted) R.string.notifications_muted else R.string.notifications,
        )
        settings.setOnClickListener(onSettingsClicked)
        media.setOnClickListener(onMediaClicked)
        search.visibility = visible(ChannelSidebarCustomization.Button.SEARCH)
        threads.visibility = if (showThreads) visible(ChannelSidebarCustomization.Button.THREADS) else View.GONE
        media.visibility = visible(ChannelSidebarCustomization.Button.MEDIA)
        pins.visibility = visible(ChannelSidebarCustomization.Button.PINS)
        notifications.visibility = visible(ChannelSidebarCustomization.Button.NOTIFICATIONS)
        settings.visibility = visible(ChannelSidebarCustomization.Button.SETTINGS)
    }

    private fun visible(button: ChannelSidebarCustomization.Button): Int =
        if (ChannelSidebarCustomization.visible(ChannelSidebarCustomization.Surface.CHANNEL, button)) {
            View.VISIBLE
        } else {
            View.GONE
        }

    private fun setTopDrawable(button: MaterialButton, resource: Int) {
        button.setCompoundDrawablesWithIntrinsicBounds(0, resource, 0, 0)
    }

    private fun themedResource(attribute: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return value.resourceId
    }
}
