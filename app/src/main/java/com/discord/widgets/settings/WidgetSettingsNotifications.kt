package com.discord.widgets.settings

import android.view.View
import android.widget.LinearLayout
import com.discord.R
import com.discord.api.notification.AccountNotificationFlags
import com.discord.api.notification.AccountNotificationSettings
import com.discord.app.AppFragment
import com.discord.models.notification.NotificationSettingsV2
import com.discord.stores.StoreNotifications
import com.discord.stores.StoreStream
import com.discord.stores.UserSettings
import com.discord.views.RadioManager
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings.ReactionNotificationType
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
        bindAccountFlags(view)

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
            .setOnCheckedListener(Action1 { UserSettings.showInAppNotifications.set(it) })
        bindAccountToggles(view)
        view.findViewById<CheckedSetting>(R.id.settings_notifications_blink)
            .setOnCheckedListener(Action1 { store.setNotificationLightDisabled(it) })
        view.findViewById<CheckedSetting>(R.id.settings_notifications_vibrations)
            .setOnCheckedListener(Action1 { store.setNotificationsVibrateDisabled(it) })
        view.findViewById<CheckedSetting>(R.id.settings_notifications_mute_all)
            .setOnCheckedListener(Action1 { store.setNotificationSoundDisabled(it) })
        // `settings_notifications_wake` exists in the recovered layout but the 126.21 reference
        // never binds it, so it stays inert here too rather than gaining invented behaviour.
    }

    /**
     * The two account-level notification flags, which live on the server rather than in
     * [StoreNotifications] and so stay usable when system notifications are off.
     */
    private fun bindAccountFlags(view: View) {
        val settings = StoreStream.getUserGuildSettings()
        view.findViewById<CheckedSetting>(R.id.settings_notifications_experimental_unreads).apply {
            setSubtext(getString(R.string.experimental_unreads_description))
            setLabelTagText(R.string.beta)
            setLabelTagVisibility(true)
            setOnCheckedListener(
                Action1 {
                    settings.setAccountNotificationFlag(
                        requireContext(),
                        AccountNotificationFlags.USE_NEW_NOTIFICATIONS,
                        it,
                    )
                },
            )
        }
        view.findViewById<CheckedSetting>(R.id.settings_notifications_mention_on_all_messages).apply {
            setSubtext(SettingsStrings.render(context, R.string.user_settings_mention_on_all_messages_body))
            setLabelTagText(R.string.beta)
            setLabelTagVisibility(true)
            setOnCheckedListener(
                Action1 {
                    settings.setAccountNotificationFlag(
                        requireContext(),
                        AccountNotificationFlags.MENTION_ON_ALL_MESSAGES,
                        it,
                    )
                },
            )
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val settings = notificationStore?.observeSettings() ?: Observable.just(NotificationSettingsV2())
        settings.ui(this).subscribe(::configureUI)
        StoreStream.getUserGuildSettings()
            .observeAccountNotificationSettings()
            .ui(this)
            .subscribe(::configureAccountFlags)
    }

    private fun configureAccountFlags(settings: AccountNotificationSettings) {
        val root = view ?: return
        root.findViewById<CheckedSetting>(R.id.settings_notifications_experimental_unreads)
            .setChecked(settings.useNewNotifications, false)
        root.findViewById<CheckedSetting>(R.id.settings_notifications_mention_on_all_messages)
            .setChecked(settings.mentionOnAllMessages, false)
    }

    private fun bindAccountToggles(view: View) {
        listOf(
            R.id.settings_notifications_stream to UserSettings.streamNotifications,
            R.id.settings_notifications_friend_anniversary to UserSettings.friendAnniversaryNotifications,
            R.id.settings_notifications_voice_activity to UserSettings.voiceActivityNotifications,
            R.id.settings_notifications_friends_online to UserSettings.friendOnlineNotifications,
            R.id.settings_notifications_status to UserSettings.statusNotifications,
            R.id.settings_notifications_friend_gaming to UserSettings.friendGamingActivityNotifications,
            R.id.settings_notifications_profile_updates to UserSettings.profileUpdateNotifications,
            R.id.settings_notifications_upcoming_events to UserSettings.upcomingEventNotifications,
            R.id.settings_notifications_share_online to UserSettings.notifyFriendsOnComeOnline,
            R.id.settings_notifications_share_profile to UserSettings.notifyFriendsOnProfileUpdate,
        ).forEach { (id, setting) ->
            // Lint mistakes setting.get() for MatchGroupCollection.get(String) (API 26); no regex here.
            @Suppress("NewApi")
            view.findViewById<CheckedSetting>(id).bind(setting.get(), setting::set)
        }

        val reactionRows = listOf(
            ReactionNotificationType.REACTION_NOTIFICATION_TYPE_NOTIFICATIONS_ENABLED to
                view.findViewById<CheckedSetting>(R.id.settings_notifications_reactions_all),
            ReactionNotificationType.REACTION_NOTIFICATION_TYPE_ONLY_DMS to
                view.findViewById<CheckedSetting>(R.id.settings_notifications_reactions_dms),
            ReactionNotificationType.REACTION_NOTIFICATION_TYPE_NOTIFICATIONS_DISABLED to
                view.findViewById<CheckedSetting>(R.id.settings_notifications_reactions_never),
        )
        val radios = RadioManager(reactionRows.map { it.second })
        val current = UserSettings.reactionNotifications.get()
        reactionRows.forEach { (type, radio) ->
            if (type == current) radios.setChecked(radio)
            radio.setContainerClickListener { UserSettings.reactionNotifications.set(type) }
        }
    }

    private fun configureUI(settings: NotificationSettingsV2) {
        val root = view ?: return
        root.findViewById<LinearLayout>(R.id.settings_notifications_wrap).visibility =
            if (settings.isEnabled) View.VISIBLE else View.GONE
        root.findViewById<CheckedSetting>(R.id.settings_notifications_enabled)
            .setChecked(settings.isEnabled, false)
        root.findViewById<CheckedSetting>(R.id.settings_inapp_notifs_switch)
            .setChecked(UserSettings.showInAppNotifications.get(), false)
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
