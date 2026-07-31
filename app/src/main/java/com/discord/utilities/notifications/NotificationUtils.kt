package com.discord.utilities.notifications

import androidx.fragment.app.Fragment
import com.discord.utilities.fcm.NotificationClient
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.WidgetSettingsNotifications
import com.discord.widgets.settings.WidgetSettingsNotificationsOs

object NotificationUtils {
    fun showNotificationPage(fragment: Fragment) {
        if (NotificationClient.isOsLevelNotificationEnabled()) {
            WidgetSettingsNotificationsOs.show(fragment.parentFragmentManager)
        } else {
            SettingsNavigation.launch(fragment.requireContext(), WidgetSettingsNotifications::class.java)
        }
    }
}
