package com.discord.widgets.settings

import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import com.discord.utilities.uri.UriHandler
import com.discord.views.CheckedSetting
import rx.functions.Action0

/**
 * "Behavior" user settings.
 *
 * Reference: `com.discord.widgets.settings.WidgetSettingsBehavior`.
 */
class WidgetSettingsBehavior : AppFragment(R.layout.widget_settings_behavior) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.form_label_mobile_notifications_behavior)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()

        val settings = StoreStream.getUserSettings()

        view.findViewById<CheckedSetting>(R.id.settings_behavior_shift_send_toggle)
            .bind(settings.getIsShiftEnterToSendEnabled(), settings::setIsShiftEnterToSendEnabled)

        view.findViewById<CheckedSetting>(R.id.settings_behavior_dev_mode_switch).apply {
            bind(settings.getIsDeveloperMode(), settings::setIsDeveloperMode)
            setSubtext(
                SettingsStrings.render(context, R.string.developer_mode_help_text, "apiDocsUrl" to API_LINK),
                true,
            )
            setSubtextOnClickListener(Action0 { UriHandler.handle(context, API_LINK) })
        }

        view.findViewById<CheckedSetting>(R.id.settings_behavior_browser_switch)
            .bind(settings.getIsChromeCustomTabsEnabled(), settings::setIsChromeCustomTabsEnabled)

        listOf(R.id.settings_behavior_keyboard_header, R.id.settings_behavior_chat_header)
            .forEach { id -> ViewCompat.setAccessibilityHeading(view.findViewById<TextView>(id), true) }
    }

    private companion object {
        const val API_LINK = "https://discord.com/developers/docs/intro"
    }
}
