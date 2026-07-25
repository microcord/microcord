package com.discord.widgets.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.models.notification.NotificationSettingsV2
import com.discord.views.CheckedSetting
import rx.Observable
import rx.Subscription
import rx.functions.Action1

class WidgetSettingsNotifications : Fragment(R.layout.widget_settings_notifications) {
    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Toolbar>(R.id.action_bar_toolbar)?.apply {
            setTitle(R.string.notifications)
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
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
        val root = view ?: return
        bind(root, R.id.settings_notifications_enabled, settings.isEnabled) { settings.copy(isEnabled = it) }
        bind(root, R.id.settings_inapp_notifs_switch, settings.isEnabledInApp) { settings.copy(isEnabledInApp = it) }
        bind(root, R.id.settings_notifications_blink, settings.isDisableBlink) { settings.copy(isDisableBlink = it) }
        bind(root, R.id.settings_notifications_vibrations, settings.isDisableVibrate) { settings.copy(isDisableVibrate = it) }
        bind(root, R.id.settings_notifications_mute_all, settings.isDisableSound) { settings.copy(isDisableSound = it) }
        bind(root, R.id.settings_notifications_wake, settings.isWake) { settings.copy(isWake = it) }
        root.findViewById<View>(R.id.settings_notifications_wrap).visibility = if (settings.isEnabled) View.VISIBLE else View.GONE
    }

    private fun bind(root: View, id: Int, checked: Boolean, update: (Boolean) -> NotificationSettingsV2) {
        root.findViewById<CheckedSetting>(id).apply {
            setOnCheckedListener(null)
            isChecked = checked
            setOnCheckedListener(Action1 { onSettingsChanged?.invoke(update(it)) })
        }
    }

    companion object {
        const val ROUTE = "settings/notifications"
        @JvmField var stateSource: (() -> Observable<NotificationSettingsV2>)? = null
        @JvmField var onSettingsChanged: ((NotificationSettingsV2) -> Unit)? = null
    }
}
