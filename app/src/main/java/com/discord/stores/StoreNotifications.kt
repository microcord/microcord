package com.discord.stores

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.discord.api.permission.Permission
import com.discord.models.message.Message
import com.discord.models.notification.NotificationSettingsV2
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.lifecycle.Backgrounded
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.time.Clock
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription

class StoreNotifications(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val observationDeck: ObservationDeck,
    private val clearChannelNotifications: (Long) -> Unit = {},
    private val onMessageCandidate: (Message, NotificationPolicy.Delivery) -> Unit = { _, _ -> },
    private val registerPushToken: (authToken: String, pushToken: String, persisted: (Boolean) -> Unit) -> Unit = { _, _, done -> done(true) },
    private val settingsCache: Persister<NotificationSettingsV2> = Persister(SETTINGS_CACHE_KEY, NotificationSettingsV2()),
    /**
     * The four inputs the reference folds into a completed `NotificationClient.SettingsV2`. They are
     * injected rather than reached for through `StoreStream.Companion` so the fold is testable
     * without a store graph; `StoreStream` supplies the real ones from [init].
     */
    private val observeAuthedToken: () -> Observable<String?> = { Observable.just(null) },
    private val observeLocale: () -> Observable<String> = { Observable.just("") },
    private val observeChannelPermissions: () -> Observable<Map<Long, Long>> = { Observable.just(emptyMap()) },
    private val observeBackgrounded: () -> Observable<Boolean> = Backgrounded::observe,
    private val setRegistrationIdReceived: ((String?) -> Unit) -> Unit = NotificationClient::setRegistrationIdReceived,
    private val updateNotificationClient: (NotificationClient.SettingsV2, Boolean) -> Unit = NotificationClient::updateSettings,
) : StoreV2() {
    private var settings = NotificationSettingsV2()
    private var settingsSnapshot = settings
    private var authToken: String? = null
    private var pushToken: String? = null
    private var pushTokenPersisted: String? = null

    /**
     * The visible activity, used as the context for in-app notification popups.
     *
     * Held only between an activity's create/resume and its destroy, so the store never keeps a
     * destroyed activity alive.
     */
    @Volatile
    private var context: Context? = null

    private var notificationClientSubscription: Subscription? = null

    fun getSettings() = settingsSnapshot
    fun getPushToken(): String? = synchronized(this) { pushToken }
    fun getContext(): Context? = context
    fun observeSettings(): Observable<NotificationSettingsV2> =
        observationDeck.connectRx(this, generator = ::getSettings).distinctUntilChanged()

    @StoreThread
    internal fun restore() {
        StoreThreadScheduler.assertCurrentThread()
        settings = settingsCache.get()
        markChanged()
    }

    /**
     * Brings the store to life: without this the notification client never learns the push
     * registration id, the locale, the auth state or the foreground/background state, and every
     * notification decision runs on defaults.
     */
    override fun init(context: Context) {
        super.init(context)
        (context.applicationContext as? Application)?.let { application ->
            configureContextSetter(application)
            NotificationClient.init(application)
        }
        configureNotificationClient()
    }

    /** Tracks the visible activity so an in-app popup has somewhere to show. */
    private fun configureContextSetter(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                context = activity
            }

            override fun onActivityResumed(activity: Activity) {
                context = activity
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (context === activity) context = null
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    /**
     * Folds everything the notification client needs into one snapshot and keeps pushing it.
     *
     * Four inputs make up the completed settings: the user's own toggles, the auth token (whose
     * mere presence is what makes the client "authed" and therefore willing to display anything),
     * the locale, and the set of channels the user cannot post in — those channels get no
     * direct-reply action. The result is paired with the foreground/background state, because the
     * client shows a system notification only while backgrounded and an in-app popup only while
     * foregrounded.
     *
     * Channel permissions are throttled on the leading edge: a single READY re-emits permissions
     * for every channel in every guild, and recomputing the blocked set per emission would burn the
     * store thread for no observable gain.
     */
    internal fun configureNotificationClient() {
        setRegistrationIdReceived(::handleRegistrationToken)
        notificationClientSubscription?.unsubscribe()
        Observable.combineLatest(observeCompletedSettings(), observeBackgrounded(), ::Pair)
            .computationBuffered()
            .distinctUntilChanged()
            .appSubscribe(
                errorTag = "nsClient",
                subscriptionHandler = { notificationClientSubscription = it },
            ) { (completed, isBackgrounded) -> updateNotificationClient(completed, isBackgrounded) }
    }

    /** The four-way fold described on [configureNotificationClient], without the delivery gate. */
    internal fun observeCompletedSettings(): Observable<NotificationClient.SettingsV2> =
        Observable.combineLatest(
            observeSettings(),
            observeAuthedToken(),
            observeLocale(),
            observeChannelPermissions()
                .leadingEdgeThrottle(1L, TimeUnit.SECONDS)
                .map(::sendBlockedChannels),
        ) { toggles, token, locale, blocked ->
            NotificationClient.SettingsV2(
                isEnabled = toggles.isEnabled,
                isEnabledInApp = toggles.isEnabledInApp,
                isWake = toggles.isWake,
                isDisableBlink = toggles.isDisableBlink,
                isDisableSound = toggles.isDisableSound,
                isDisableVibrate = toggles.isDisableVibrate,
                token = token,
                locale = locale,
                sendBlockedChannels = blocked,
            )
        }.distinctUntilChanged()

    private fun sendBlockedChannels(channelPermissions: Map<Long, Long>): Set<Long> =
        channelPermissions.filterValues { !PermissionUtils.can(Permission.SEND_MESSAGES, it) }.keys.toSet()

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

    /**
     * The notification client is not torn down here on purpose. Its snapshot is driven reactively
     * by [observeAuthedToken]; when that emits null the completed settings lose their token,
     * `isAuthed` goes false, and the client stops displaying anything — and it comes back by itself
     * on the next sign-in, which an unsubscribe would prevent.
     */
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
