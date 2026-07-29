package com.discord.stores

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.discord.api.permission.Permission
import com.discord.models.message.Message
import com.discord.models.notification.NotificationSettingsV2
import com.discord.restapi.RestAPIParams
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.lifecycle.ApplicationProvider
import com.discord.utilities.lifecycle.Backgrounded
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import java.util.Locale
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription
import rx.subjects.BehaviorSubject

class StoreNotifications(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val observationDeck: ObservationDeck,
    private val clearChannelNotifications: (Long) -> Unit = {},
    private val onMessageCandidate: (Message, NotificationPolicy.Delivery) -> Unit = { _, _ -> },
    private val registerPushToken: (authToken: String, pushToken: String?, persisted: (Boolean) -> Unit) -> Unit =
        { _, pushToken, persisted -> registerDevice(pushToken, persisted) },
    private val settingsCache: Persister<NotificationSettingsV2> = Persister(SETTINGS_CACHE_KEY, NotificationSettingsV2()),
    /**
     * The four inputs the reference folds into a completed `NotificationClient.SettingsV2`. They are
     * injectable so the fold is testable without a store graph. The production defaults use this
     * store's auth stream and the existing system-settings and permissions stores.
     */
    private val observeAuthedToken: (() -> Observable<String?>)? = null,
    private val observeLocale: () -> Observable<String> = {
        runCatching { StoreStream.getUserSettingsSystem().observeSettings(false).map { it.locale } }
            .getOrElse { Observable.just(Locale.getDefault().toLanguageTag()) }
    },
    private val observeChannelPermissions: () -> Observable<Map<Long, Long>> = {
        runCatching { StoreStream.getPermissions().observePermissionsForAllChannels() }
            .getOrElse { Observable.just(emptyMap()) }
    },
    private val observeBackgrounded: () -> Observable<Boolean> = Backgrounded::observe,
    private val setRegistrationIdReceived: ((String?) -> Unit) -> Unit = NotificationClient::setRegistrationIdReceived,
    private val updateNotificationClient: (NotificationClient.SettingsV2, Boolean) -> Unit = NotificationClient::updateSettings,
) : StoreV2() {
    private var settings = NotificationSettingsV2()
    private var settingsSnapshot = settings
    private var authToken: String? = null
    private val authTokenSubject = BehaviorSubject.create(null as String?)
    private var pushToken: String? = null
    private var pushTokenPersisted: String? = null
    private var registrationTokenReceived = false

    /**
     * The visible activity, used as the context for in-app notification popups.
     *
     * Held only between an activity's create/resume and its destroy, so the store never keeps a
     * destroyed activity alive.
     */
    @Volatile
    private var context: Context? = null

    private var notificationClientSubscription: Subscription? = null
    private var notificationClientConfigured = false
    private var initializedApplication: Application? = null

    fun getSettings() = settingsSnapshot
    fun getPushToken(): String? = synchronized(this) { pushToken }
    fun getContext(): Context? = context
    fun observeSettings(): Observable<NotificationSettingsV2> =
        observationDeck.connectRx(this, generator = ::getSettings).distinctUntilChanged()

    @StoreThread
    internal fun restore() {
        StoreThreadScheduler.assertCurrentThread()
        settings = settingsCache.get()
        val application = runCatching(ApplicationProvider::get).getOrNull()
        if (application != null) init(application) else ensureNotificationClientConfigured()
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
            if (initializedApplication !== application) {
                initializedApplication = application
                configureContextSetter(application)
                NotificationClient.init(application)
            }
        }
        ensureNotificationClientConfigured()
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
        notificationClientConfigured = true
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

    private fun ensureNotificationClientConfigured() {
        if (!notificationClientConfigured) configureNotificationClient()
    }

    /** The four-way fold described on [configureNotificationClient], without the delivery gate. */
    internal fun observeCompletedSettings(): Observable<NotificationClient.SettingsV2> =
        Observable.combineLatest(
            observeSettings(),
            observeAuthedToken?.invoke() ?: authTokenSubject,
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
    @Suppress("UNUSED_PARAMETER")
    fun setEnabledInApp(value: Boolean, logToggle: Boolean = true) =
        updateSettings { copy(isEnabledInApp = value) }
    fun setWake(value: Boolean) = updateSettings { copy(isWake = value) }
    fun setNotificationLightDisabled(value: Boolean) = updateSettings { copy(isDisableBlink = value) }
    fun setNotificationSoundDisabled(value: Boolean) = updateSettings { copy(isDisableSound = value) }
    fun setNotificationsVibrateDisabled(value: Boolean) = updateSettings { copy(isDisableVibrate = value) }

    fun handleAuthToken(token: String?) = synchronized(this) {
        authToken = token
        authTokenSubject.onNext(token)
        if (token == null) pushTokenPersisted = null
        tryTokenPersist()
    }

    fun handleRegistrationToken(token: String?) = synchronized(this) {
        registrationTokenReceived = true
        pushToken = token
        tryTokenPersist()
    }

    @StoreThread
    fun handleChannelSelected(channelId: Long) = clearChannelNotifications(channelId)

    @StoreThread
    fun handleMessageCreate(input: NotificationPolicy.Input, isForeground: Boolean) {
        if (!isForeground || !isFresh(input.message)) return
        if (NotificationPolicy.Delivery.FOREGROUND in NotificationPolicy.evaluate(input.copy(appSettings = settings))) {
            onMessageCandidate(input.message, NotificationPolicy.Delivery.FOREGROUND)
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
        if (!registrationTokenReceived) return
        val push = pushToken
        if (push == pushTokenPersisted) return
        registerPushToken(auth, push) { success ->
            if (success) synchronized(this) {
                if (authToken == auth && pushToken == push) pushTokenPersisted = push
            }
        }
    }

    private fun isFresh(message: Message): Boolean {
        val sentAt = message.timestamp?.dateTimeMillis
            ?: if (message.id > 4_194_304L) SnowflakeUtils.toTimestamp(message.id) else 0L
        return sentAt > 0L && sentAt + MESSAGE_WINDOW_MS > clock.currentTimeMillis()
    }

    companion object {
        const val SETTINGS_CACHE_KEY = "STORE_NOTIFICATIONS_SETTINGS_V2"
        const val MESSAGE_WINDOW_MS = 10_000L

        private fun registerDevice(pushToken: String?, persisted: (Boolean) -> Unit) {
            val api = RestAPI.apiOrNull ?: return persisted(false)
            api.userCreateDevice(RestAPIParams.UserDevices(pushToken))
                .restSubscribeOn(retry = false)
                .appSubscribe(
                    errorTag = "StoreNotifications",
                    errorHandler = { persisted(false) },
                    onCompleted = { persisted(true) },
                    onNext = {},
                )
        }
    }
}
