package com.discord.views.channelsidebar

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.discord.R
import com.google.android.material.button.MaterialButton

class GuildChannelSideBarActionsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val notifications: MaterialButton
    private val pins: MaterialButton
    private val searchOrThreads: MaterialButton
    private val settings: MaterialButton

    init {
        LayoutInflater.from(context).inflate(R.layout.guild_channel_side_bar_actions_view, this)
        notifications = requireNotNull(findViewById(R.id.channel_sidebar_actions_notifications))
        pins = requireNotNull(findViewById(R.id.channel_sidebar_actions_pins))
        searchOrThreads = requireNotNull(findViewById(R.id.channel_sidebar_actions_search_or_threads))
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
        hasUnseenPins: Boolean = false,
        channelIsMuted: Boolean = false,
        disablePins: Boolean = false,
        showSearch: Boolean = true,
    ) {
        setTopDrawable(pins, themedResource(if (hasUnseenPins) R.attr.ic_sidebar_pins_on_24dp else R.attr.ic_sidebar_pins_off_24dp))
        setTopDrawable(
            notifications,
            themedResource(
                if (channelIsMuted) R.attr.ic_sidebar_notifications_off_24dp
                else R.attr.ic_sidebar_notifications_on_24dp,
            ),
        )
        if (showSearch) {
            setTopDrawable(searchOrThreads, R.drawable.ic_sidebar_search_24dp)
            searchOrThreads.setText(R.string.search)
            searchOrThreads.setOnClickListener(onSearchClicked)
        } else {
            setTopDrawable(searchOrThreads, R.drawable.ic_thread)
            searchOrThreads.setText(R.string.threads)
            searchOrThreads.setOnClickListener(onThreadBrowserClicked)
        }
        pins.isEnabled = !disablePins
        pins.setOnClickListener(onPinsClicked)
        notifications.setOnClickListener(onNotificationsClicked)
        notifications.contentDescription = context.getText(
            if (channelIsMuted) R.string.notifications_muted else R.string.notifications,
        )
        settings.setOnClickListener(onSettingsClicked)
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
