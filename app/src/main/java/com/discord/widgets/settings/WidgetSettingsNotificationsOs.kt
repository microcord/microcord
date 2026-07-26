package com.discord.widgets.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.app.AppBottomSheet
import com.discord.R
import com.discord.models.notification.NotificationSettingsV2
import com.discord.views.CheckedSetting
import rx.Observable
import rx.Subscription
import rx.functions.Action1

class WidgetSettingsNotificationsOs : AppBottomSheet() {
    private var subscription: Subscription? = null
    private var latest = NotificationSettingsV2()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onNotificationPermissionResult?.invoke(granted)
        if (granted) onSettingsChanged?.invoke(latest.copy(isEnabled = true)) else render(latest)
    }

    override fun getContentViewResId() = R.layout.widget_settings_notification_os

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.settings_notification_os).setOnClickListener {
            onOpenSystemSettings?.invoke() ?: startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName),
            )
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: Observable.just(NotificationSettingsV2()))
            .distinctUntilChanged().subscribe(::render)
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun render(settings: NotificationSettingsV2) {
        latest = settings
        val root = view ?: return
        root.findViewById<CheckedSetting>(R.id.settings_notification_switch).apply {
            setOnCheckedListener(null)
            isChecked = settings.isEnabled
            setOnCheckedListener(Action1 { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else onSettingsChanged?.invoke(settings.copy(isEnabled = enabled))
            })
        }
        root.findViewById<CheckedSetting>(R.id.settings_inapp_notifs_switch).apply {
            setOnCheckedListener(null)
            isChecked = settings.isEnabledInApp
            setOnCheckedListener(Action1 { onSettingsChanged?.invoke(settings.copy(isEnabledInApp = it)) })
        }
    }

    companion object {
        const val ROUTE = "settings/notifications/os"
        @JvmField var stateSource: (() -> Observable<NotificationSettingsV2>)? = null
        @JvmField var onSettingsChanged: ((NotificationSettingsV2) -> Unit)? = null
        @JvmField var onOpenSystemSettings: (() -> Unit)? = null
        @JvmField var onNotificationPermissionResult: ((Boolean) -> Unit)? = null
        fun show(manager: androidx.fragment.app.FragmentManager) =
            WidgetSettingsNotificationsOs().show(manager, WidgetSettingsNotificationsOs::class.java.name)
    }
}
