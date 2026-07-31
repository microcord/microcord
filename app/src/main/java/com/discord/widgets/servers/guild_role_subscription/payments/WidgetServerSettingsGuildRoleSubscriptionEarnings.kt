package com.discord.widgets.servers.guild_role_subscription.payments

import android.content.Context
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.widgets.settings.SettingsNavigation

class WidgetServerSettingsGuildRoleSubscriptionEarnings :
    AppFragment(R.layout.view_server_settings_guild_role_subscription_unavailable_notice) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        view.findViewById<TextView>(R.id.title)
            .setText(R.string.guild_role_subscription_payment_analytics_unavailable_mobile_title)
        view.findViewById<TextView>(R.id.description)
            .setText(R.string.guild_role_subscription_payment_analytics_unavailable_mobile_description)
    }

    companion object {
        fun launch(context: Context) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsGuildRoleSubscriptionEarnings::class.java,
            )
        }
    }
}
