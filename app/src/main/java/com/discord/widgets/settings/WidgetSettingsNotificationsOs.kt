package com.discord.widgets.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.models.notification.NotificationSettingsV2
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import rx.Observable
import rx.functions.Action1
import rx.subscriptions.CompositeSubscription

/**
 * The compact notification sheet reachable from a notification the user tapped.
 *
 * Reference: `com.discord.widgets.settings.WidgetSettingsNotificationsOs`, `@RequiresApi(26)` —
 * it exists because notification channels arrived in Android O, and it links out to the system
 * notification screen through `SystemSettingNavigators.openSystemNotification`.
 *
 * The store is resolved through [NotificationStore]; see that file. The previous implementation of
 * this screen also requested `POST_NOTIFICATIONS` on API 33+, which the 126.21 reference does not do
 * (it targets API 30). That divergence is dropped here: the sheet links to the system screen, which
 * is where the permission is granted on modern Android too.
 */
@RequiresApi(26)
class WidgetSettingsNotificationsOs : AppBottomSheet() {

    override fun getContentViewResId() = R.layout.widget_settings_notification_os

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val store = NotificationStore.get()
        if (store != null) {
            view.findViewById<CheckedSetting>(R.id.settings_notification_switch)
                .setOnCheckedListener(Action1 { store.setEnabled(it) })
            view.findViewById<CheckedSetting>(R.id.settings_inapp_notifs_switch)
                .setOnCheckedListener(Action1 { store.setEnabledInApp(it) })
        } else {
            view.findViewById<CheckedSetting>(R.id.settings_notification_switch).isEnabled = false
            view.findViewById<CheckedSetting>(R.id.settings_inapp_notifs_switch).isEnabled = false
        }
        view.findViewById<View>(R.id.settings_notification_os).setOnClickListener {
            openSystemNotificationSettings(it.context)
            dismiss()
        }
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        val settings = NotificationStore.get()?.observeSettings() ?: Observable.just(NotificationSettingsV2())
        compositeSubscription.add(settings.ui(this).subscribe(::configureUI))
    }

    private fun configureUI(settings: NotificationSettingsV2) {
        val root = view ?: return
        root.findViewById<CheckedSetting>(R.id.settings_notification_switch)
            .setChecked(settings.isEnabled, false)
        root.findViewById<CheckedSetting>(R.id.settings_inapp_notifs_switch)
            .setChecked(settings.isEnabledInApp, false)
    }

    /** Reference: `SystemSettingNavigators.openSystemNotification`, in a package this slice does not own. */
    private fun openSystemNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent) }
    }

    companion object {
        fun show(fragmentManager: FragmentManager) =
            WidgetSettingsNotificationsOs().show(fragmentManager, WidgetSettingsNotificationsOs::class.java.name)
    }
}
