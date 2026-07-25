package com.discord.stores

import com.discord.models.message.Message
import com.discord.models.notification.NotificationSettingsV2
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import rx.Observable

class StoreNotifications(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val observationDeck: ObservationDeck,
    private val clearChannelNotifications: (Long) -> Unit = {},
    private val onMessageCandidate: (Message, NotificationPolicy.Delivery) -> Unit = { _, _ -> },
    private val registerPushToken: (authToken: String, pushToken: String, persisted: (Boolean) -> Unit) -> Unit = { _, _, done -> done(true) },
    private val settingsCache: Persister<NotificationSettingsV2> = Persister(SETTINGS_CACHE_KEY, NotificationSettingsV2()),
) : StoreV2() {
    private var settings = NotificationSettingsV2()
    private var settingsSnapshot = settings
    private var authToken: String? = null
    private var pushToken: String? = null
    private var pushTokenPersisted: String? = null

    fun getSettings() = settingsSnapshot
    fun getPushToken(): String? = synchronized(this) { pushToken }
    fun observeSettings(): Observable<NotificationSettingsV2> =
        observationDeck.connectRx(this, generator = ::getSettings).distinctUntilChanged()

    @StoreThread
    internal fun restore() {
        StoreThreadScheduler.assertCurrentThread()
        settings = settingsCache.get()
        markChanged()
    }

    fun setEnabled(value: Boolean) = updateSettings { copy(isEnabled = value) }
    fun setEnabledInApp(value: Boolean) = updateSettings { copy(isEnabledInApp = value) }
    fun setWake(value: Boolean) = updateSettings { copy(isWake = value) }
    fun setNotificationLightDisabled(value: Boolean) = updateSettings { copy(isDisableBlink = value) }
    fun setNotificationSoundDisabled(value: Boolean) = updateSettings { copy(isDisableSound = value) }
    fun setNotificationsVibrateDisabled(value: Boolean) = updateSettings { copy(isDisableVibrate = value) }

    fun handleAuthToken(token: String?) = synchronized(this) {
        authToken = token
        if (token == null) pushTokenPersisted = null
        tryTokenPersist()
    }

    fun handleRegistrationToken(token: String?) = synchronized(this) {
        pushToken = token
        tryTokenPersist()
    }

    @StoreThread
    fun handleChannelSelected(channelId: Long) = clearChannelNotifications(channelId)

    @StoreThread
    fun handleMessageCreate(input: NotificationPolicy.Input, isForeground: Boolean) {
        if (!isFresh(input.message)) return
        val delivery = if (isForeground) NotificationPolicy.Delivery.FOREGROUND else NotificationPolicy.Delivery.SYSTEM
        if (delivery in NotificationPolicy.evaluate(input.copy(appSettings = settings))) {
            onMessageCandidate(input.message, delivery)
        }
    }

    @StoreThread
    fun handlePreLogout() {
        StoreThreadScheduler.assertCurrentThread()
        synchronized(this) {
            authToken = null
            pushTokenPersisted = null
        }
        settings = NotificationSettingsV2()
        settingsCache.set(settings, persist = true)
        markChanged()
    }

    override fun snapshotData() {
        settingsSnapshot = settings
    }

    private fun updateSettings(update: NotificationSettingsV2.() -> NotificationSettingsV2) = dispatcher.schedule {
        val next = settings.update()
        if (next != settings) {
            settings = next
            settingsCache.set(next, persist = true)
            markChanged()
        }
    }

    @Synchronized
    private fun tryTokenPersist() {
        val auth = authToken ?: return
        val push = pushToken ?: return
        if (push == pushTokenPersisted) return
        registerPushToken(auth, push) { success ->
            if (success) synchronized(this) {
                if (authToken == auth && pushToken == push) pushTokenPersisted = push
            }
        }
    }

    private fun isFresh(message: Message): Boolean {
        val sentAt = message.timestamp?.dateTimeMillis
            ?: if (message.id > 4_194_304L) (message.id ushr 22) + DISCORD_EPOCH else 0L
        return sentAt > 0L && sentAt + MESSAGE_WINDOW_MS > clock.currentTimeMillis()
    }

    companion object {
        const val SETTINGS_CACHE_KEY = "STORE_NOTIFICATIONS_SETTINGS_V2"
        const val MESSAGE_WINDOW_MS = 10_000L
        private const val DISCORD_EPOCH = 1_420_070_400_000L
    }
}
