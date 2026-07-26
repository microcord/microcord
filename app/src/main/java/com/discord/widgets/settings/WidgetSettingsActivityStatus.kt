package com.discord.widgets.settings

import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting

/**
 * "Activity Status" user settings.
 *
 * Reference: `com.discord.widgets.settings.WidgetSettingsActivityStatus`.
 */
class WidgetSettingsActivityStatus : AppFragment(R.layout.widget_settings_activity_status) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.activity_status)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getUserSettings().observeIsShowCurrentGameEnabled()
            .ui(this)
            .subscribe(::configureUI)
    }

    private fun configureUI(showCurrentGame: Boolean) {
        val settings = StoreStream.getUserSettings()
        view?.findViewById<CheckedSetting>(R.id.show_current_activity)
            ?.bind(showCurrentGame, settings::setIsShowCurrentGameEnabled)
    }
}
