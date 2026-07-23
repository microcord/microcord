package com.discord.views.channelsidebar

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.discord.R
import com.google.android.material.button.MaterialButton

class PrivateChannelSideBarActionsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val call: MaterialButton
    private val notifications: MaterialButton
    private val search: MaterialButton
    private val video: MaterialButton

    init {
        LayoutInflater.from(context).inflate(R.layout.private_channel_side_bar_actions_view, this)
        call = requireNotNull(findViewById(R.id.private_channel_sidebar_actions_call))
        notifications = requireNotNull(findViewById(R.id.private_channel_sidebar_actions_notifications))
        search = requireNotNull(findViewById(R.id.private_channel_sidebar_actions_search))
        video = requireNotNull(findViewById(R.id.private_channel_sidebar_actions_video))
        configure()
    }

    @JvmOverloads
    fun configure(
        onCallClicked: View.OnClickListener = View.OnClickListener {},
        onVideoClicked: View.OnClickListener = View.OnClickListener {},
        onNotificationsClicked: View.OnClickListener = View.OnClickListener {},
        onSearchClicked: View.OnClickListener = View.OnClickListener {},
        channelIsMuted: Boolean = false,
    ) {
        call.setOnClickListener(onCallClicked)
        video.setOnClickListener(onVideoClicked)
        notifications.setOnClickListener(onNotificationsClicked)
        search.setOnClickListener(onSearchClicked)
        val value = TypedValue()
        context.theme.resolveAttribute(
            if (channelIsMuted) R.attr.ic_sidebar_notifications_off_24dp
            else R.attr.ic_sidebar_notifications_on_24dp,
            value,
            true,
        )
        notifications.setCompoundDrawablesWithIntrinsicBounds(0, value.resourceId, 0, 0)
    }
}
