package com.discord.utilities.fcm

import android.app.Application
import android.content.Context
import android.os.Build
import com.discord.app.AppLog
import com.discord.utilities.notifications.NotificationData
import com.discord.utilities.notifications.NotificationRenderer
import com.discord.utilities.persister.Persister

/**
 * Process-wide owner of the push registration id and of the settings snapshot that notification
 * rendering reads.
 *
 * The reference splits notification work the same way: [com.discord.stores.StoreNotifications] owns
 * the *observable* state (user toggles, auth token, locale, channels the user cannot post in) and
 * pushes a completed [SettingsV2] here through [updateSettings]; the push entry point then reads
 * that snapshot synchronously, because it runs in a process that may have no store graph yet.
 *
 * Everything here is deliberately provider-agnostic. The reference's `FCMMessagingService` is a
 * `FirebaseMessagingService` subclass, and the Firebase SDK is not a dependency of this project —
 * registering project-owned Firebase configuration for `dev.mantikafasi.manticord` is Phase 13
 * work, and the reference APK's Firebase values must never be reused. A future messaging service
 * only has to call [onNewToken] and [handlePushNotification]; no other class needs to change.
 */
object NotificationClient {
    /** Push payload type the reference deliberately drops instead of displaying. */
    const val IGNORED_NOTIFICATION_TYPE = "top_messages_push"

    const val NOTIF_CHANNEL_CALLS = "Calls"
    const val NOTIF_CHANNEL_FORUM_POST_CREATE = "Forum Post Create"
    const val NOTIF_CHANNEL_GAME_DETECTION = "Game Detection"
    const val NOTIF_CHANNEL_MEDIA_CONNECTIONS = "Media Connections"
    const val NOTIF_CHANNEL_MESSAGES = "Messages"
    const val NOTIF_CHANNEL_MESSAGES_DIRECT = "DirectMessages"
    const val NOTIF_CHANNEL_SOCIAL = "Social"
    const val NOTIF_CHANNEL_STAGE_START = "Stage Live"
    const val NOTIF_GENERAL = "General"
    const val NOTIF_GENERAL_HIGH_PRIO = "GeneralHigh"
    const val NOTIF_GUILD_SCHEDULED_EVENT_START = "Guild Event Live"

    const val SETTINGS_CACHE_KEY = "NOTIFICATION_CLIENT_SETTINGS_V3"

    /**
     * The complete notification configuration, as opposed to the user-facing toggles alone.
     *
     * [token] doubles as the authentication marker — the reference derives `isAuthed` from it — so
     * this type is never logged in full and [toString] redacts it.
     */
    data class SettingsV2(
        val isEnabled: Boolean = true,
        val isEnabledInApp: Boolean = true,
        val isWake: Boolean = false,
        val isDisableBlink: Boolean = false,
        val isDisableSound: Boolean = false,
        val isDisableVibrate: Boolean = false,
        val token: String? = null,
        val locale: String = "",
        /** Channels the user cannot SEND_MESSAGES in: their notifications get no direct-reply action. */
        val sendBlockedChannels: Set<Long> = emptySet(),
    ) {
        val isAuthed: Boolean get() = token != null

        fun canReplyTo(channelId: Long): Boolean = channelId !in sendBlockedChannels

        override fun toString(): String = "SettingsV2(isEnabled=$isEnabled, isEnabledInApp=$isEnabledInApp, " +
            "isWake=$isWake, isDisableBlink=$isDisableBlink, isDisableSound=$isDisableSound, " +
            "isDisableVibrate=$isDisableVibrate, token=<redacted>, locale=$locale, " +
            "sendBlockedChannels=${sendBlockedChannels.size})"
    }

    private val renderer = NotificationRenderer()

    /**
     * Application context only. The reference keeps the *activity* here and leaks it behind a
     * `@SuppressLint("StaticFieldLeak")`; a process-lifetime singleton has no business holding an
     * activity, and nothing here needs one.
     */
    @Volatile
    private var applicationContext: Context? = null

    /** Starts backgrounded: nothing may claim the app is visible until a resumed activity says so. */
    @Volatile
    private var isBackgrounded: Boolean = true

    private val settings: Persister<SettingsV2> by lazy { Persister(SETTINGS_CACHE_KEY, SettingsV2()) }

    private var token: String? = null

    private var tokenCallback: (String?) -> Unit = {
        AppLog.w(javaClass.simpleName, "Push token was not sent to server")
    }

    fun init(application: Application) {
        applicationContext = application.applicationContext
        if (isOsLevelNotificationEnabled()) renderer.initNotificationChannels(application)
    }

    /** Notification channels — and therefore per-channel user control — exist from API 26. */
    fun isOsLevelNotificationEnabled(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    @Synchronized
    fun getSettings(): SettingsV2 = settings.get()

    @Synchronized
    fun updateSettings(settings: SettingsV2, isBackgrounded: Boolean) {
        this.settings.set(settings, persist = isBackgrounded)
        this.isBackgrounded = isBackgrounded
    }

    @Synchronized
    fun getRegistrationId(): String? = token

    /**
     * Registers the sink for the push registration id and immediately replays the current one, so a
     * store that starts after the token arrived still sees it. Mirrors the reference contract.
     */
    @Synchronized
    fun setRegistrationIdReceived(onDeviceRegistrationIdReceived: (String?) -> Unit) {
        tokenCallback = onDeviceRegistrationIdReceived
        onDeviceRegistrationIdReceived(token)
    }

    /**
     * Entry point for the push provider. Only the token's hash is logged — a registration id is a
     * device credential and never reaches the log.
     */
    @Synchronized
    fun onNewToken(token: String?) {
        AppLog.d("Push token received. hash=${token?.hashCode()}")
        this.token = token
        tokenCallback(token)
    }

    /**
     * Displays a notification that arrived out of band (push), applying the same gate the reference
     * applies: system notification only while backgrounded and enabled, in-app popup only while
     * foregrounded and enabled in-app, and nothing at all while unauthenticated.
     *
     * Returns the delivery that was performed, or `null` when the notification was dropped.
     */
    fun handlePushNotification(data: NotificationData, context: Context? = null): Delivery? {
        val target = context ?: applicationContext
        if (target == null) {
            AppLog.e("Not showing notification because context was null.")
            return null
        }
        val current = getSettings()
        if (!current.isAuthed) return null
        if (data.type == IGNORED_NOTIFICATION_TYPE) return null
        return display(target, data, current)
    }

    /**
     * In-app popup sink, installed by the UI layer.
     *
     * The reference routes a foreground notification to `NoticePopupChannel` rather than to the
     * system tray. That surface does not exist in this project yet, so the seam is left explicit:
     * while it is `null` a foreground notification is dropped, exactly as the reference drops it
     * from the tray, instead of being silently promoted to a system notification.
     */
    @Volatile
    var inAppNotificationHandler: ((Context, NotificationData) -> Boolean)? = null

    /** Applies [settings] to [data] and renders it, or returns `null` when the gate rejects it. */
    fun display(context: Context, data: NotificationData, settings: SettingsV2 = getSettings()): Delivery? = when {
        isBackgrounded && settings.isEnabled ->
            Delivery.SYSTEM.takeIf { renderer.render(context, data, settings) }
        !isBackgrounded && settings.isEnabledInApp ->
            Delivery.IN_APP.takeIf { inAppNotificationHandler?.invoke(context, data) == true }
        else -> null
    }

    /** Renders [data] straight to the system tray, bypassing the foreground/background gate. */
    fun displaySystem(context: Context, data: NotificationData, settings: SettingsV2 = getSettings()): Boolean =
        settings.isEnabled && renderer.render(context, data, settings)

    /**
     * Removes every notification shown for [channelId].
     *
     * [isAckRequest] marks a clear caused by our own read-ack round trip. A channel that was just
     * direct-replied to is exempt from exactly one such clear, so the reply's own ack does not wipe
     * a notification that arrived in the meantime.
     */
    @JvmOverloads
    fun clear(channelId: Long, context: Context? = null, isAckRequest: Boolean = false) {
        val target = context ?: applicationContext ?: return
        renderer.clearChannel(target, channelId, isAckRequest)
    }

    /** Marks [channelId] exempt from the next ack-driven clear. */
    fun setIgnoreNextClearForAck(channelId: Long, shouldIgnore: Boolean) =
        renderer.setIgnoreNextClearForAck(channelId, shouldIgnore)

    enum class Delivery { SYSTEM, IN_APP }
}
