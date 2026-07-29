package com.discord.widgets.servers.community

import android.view.View
import com.discord.R
import com.discord.app.AppFragment

class WidgetServerSettingsEnableCommunity : AppFragment(R.layout.widget_server_settings_enable_community) {

    private val viewModel: WidgetServerSettingsEnableCommunityViewModel by enableCommunityViewModel()

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        requireView().findViewById<View>(R.id.community_setting_get_started).setOnClickListener {
            viewModel.updateCurrentPage(COMMUNITY_SETUP_FIRST_STEP)
        }
    }
}
