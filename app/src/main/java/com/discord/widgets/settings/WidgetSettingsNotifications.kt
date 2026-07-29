package com.discord.widgets.settings

import android.view.View
import android.widget.LinearLayout
import com.discord.R
import com.discord.app.AppFragment
import com.discord.models.notification.NotificationSettingsV2
import com.discord.stores.StoreNotifications
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import rx.Observable
import rx.functions.Action1

/**
 * "Notifications" user settings.
 *
 * Reference: `com.discord.widgets.settings.WidgetSettingsNotifications`, which reads
 * `StoreStream.getNotifications().getSettings()` (an `Observable`) and writes each toggle straight
 * back into `StoreNotifications`.
 *
 * Two API mismatches against the reference, both in `stores/` and therefore reported rather than
 * changed here:
 * - reference `StoreNotifications.getSettings()` returns the observable; this project splits it into
 *   `getSettings()` (snapshot) + `observeSettings()`.
 * - reference `setEnabledInApp(value, trackAnalytics = false)` takes a second argument.
 *
 * The store instance itself is resolved through [NotificationStore]; see that file for why.
 */
class WidgetSettingsNotifications : AppFragment(R.layout.widget_settings_notifications) {

    private val notificationStore: StoreNotifications? get() = NotificationStore.get()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.notifications)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)

        val store = notificationStore
        if (store == null) {
            // No store to write to: leave every row visibly inert instead of accepting taps that
            // would be dropped. Removed together with `NotificationStore`.
            toggles(view).forEach { it.isEnabled = false }
            return
        }
        view.findViewById<CheckedSetting>(R.id.settings_notifications_enabled)
            .setOnCheckedListener(Action1 { enabled ->
                if (!enabled) {
                    store.setEnabled(false)
                } else {
                    requestNotifications(
                        onFailure = { store.setEnabled(false) },
                        onSuccess = { store.setEnabled(true) },
                    )
                }
            })
        view.findViewById<CheckedSetting>(R.id.settings_inapp_notifs_switch)
            .setOnCheckedListener(Action1 { store.setEnabledInApp(it) })
        view.findViewById<CheckedSetting>(R.id.settings_notifications_blink)
            .setOnCheckedListener(Action1 { store.setNotificationLightDisabled(it) })
        view.findViewById<CheckedSetting>(R.id.settings_notifications_vibrations)
            .setOnCheckedListener(Action1 { store.setNotificationsVibrateDisabled(it) })
        view.findViewById<CheckedSetting>(R.id.settings_notifications_mute_all)
            .setOnCheckedListener(Action1 { store.setNotificationSoundDisabled(it) })
        // `settings_notifications_wake` exists in the recovered layout but the 126.21 reference
        // never binds it, so it stays inert here too rather than gaining invented behaviour.
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val settings = notificationStore?.observeSettings() ?: Observable.just(NotificationSettingsV2())
        settings.ui(this).subscribe(::configureUI)
    }

    private fun configureUI(settings: NotificationSettingsV2) {
        val root = view ?: return
        root.findViewById<LinearLayout>(R.id.settings_notifications_wrap).visibility =
            if (settings.isEnabled) View.VISIBLE else View.GONE
        root.findViewById<CheckedSetting>(R.id.settings_notifications_enabled)
            .setChecked(settings.isEnabled, false)
        root.findViewById<CheckedSetting>(R.id.settings_inapp_notifs_switch)
            .setChecked(settings.isEnabledInApp, false)
        root.findViewById<CheckedSetting>(R.id.settings_notifications_blink)
            .setChecked(settings.isDisableBlink, false)
        root.findViewById<CheckedSetting>(R.id.settings_notifications_vibrations)
            .setChecked(settings.isDisableVibrate, false)
        root.findViewById<CheckedSetting>(R.id.settings_notifications_mute_all)
            .setChecked(settings.isDisableSound, false)
    }

    private fun toggles(root: View): List<CheckedSetting> = listOf(
        root.findViewById(R.id.settings_notifications_enabled),
        root.findViewById(R.id.settings_inapp_notifs_switch),
        root.findViewById(R.id.settings_notifications_blink),
        root.findViewById(R.id.settings_notifications_vibrations),
        root.findViewById(R.id.settings_notifications_mute_all),
        root.findViewById(R.id.settings_notifications_wake),
    )
}
