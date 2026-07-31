package com.discord.widgets.servers.guild_role_subscription

import android.content.Context
import android.os.Bundle
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter

class WidgetServerSettingsEnableMonetizationUnavailable :
    AppFragment(R.layout.view_server_settings_guild_role_subscription_unavailable_notice) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setActionBarDisplayHomeAsUpEnabled()
    }

    companion object {
        fun launch(context: Context) {
            AppScreenRouter.launch(context, WidgetServerSettingsEnableMonetizationUnavailable::class.java)
        }
    }
}
