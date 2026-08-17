package com.discord.widgets.settings.connections

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.connectedaccounts.ConnectedAccountIntegration
import com.discord.utilities.icon.IconUtils
import com.discord.views.VitoImageView
import com.google.android.material.button.MaterialButton

class ConnectionsGuildIntegrationView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private val handle: TextView
    private val icon: VitoImageView
    private val name: TextView
    private val error: TextView
    private val join: MaterialButton

    init {
        LayoutInflater.from(context).inflate(R.layout.view_connection_guild_integration, this, true)
        handle = requireNotNull(findViewById(R.id.widget_connection_integration_guild_handle))
        icon = requireNotNull(findViewById(R.id.widget_connection_integration_guild_icon))
        name = requireNotNull(findViewById(R.id.widget_connection_integration_guild_name))
        error = requireNotNull(findViewById(R.id.widget_connection_integration_guild_join_error))
        join = requireNotNull(findViewById(R.id.widget_connection_integration_join_guild))
    }

    fun setIntegrationData(
        integration: ConnectedAccountIntegration,
        joinStatus: WidgetSettingsUserConnectionsViewModel.JoinStatus?,
    ) {
        handle.text = when (integration.type) {
            "twitch" -> "twitch.tv/${integration.account?.name}"
            "youtube" -> "youtube.com/channel/${integration.account?.name}"
            else -> integration.guild?.name
        }
        integration.guild?.let { guild ->
            name.text = guild.name
            IconUtils.setIcon(icon, IconUtils.getForGuild(guild.id, guild.icon, size = 32), 32, 32)
        }
        join.isEnabled = joinStatus !is WidgetSettingsUserConnectionsViewModel.JoinStatus.Joining
        join.setText(
            if (joinStatus is WidgetSettingsUserConnectionsViewModel.JoinStatus.Joining) {
                R.string.joining_guild
            } else {
                R.string.join
            },
        )
        error.visibility = if (joinStatus is WidgetSettingsUserConnectionsViewModel.JoinStatus.JoinFailed) View.VISIBLE else View.GONE
        join.visibility = if (joinStatus is WidgetSettingsUserConnectionsViewModel.JoinStatus.Joined) View.INVISIBLE else View.VISIBLE
    }

    fun setJoinClickListener(listener: OnClickListener?) {
        join.setOnClickListener(listener)
    }
}
