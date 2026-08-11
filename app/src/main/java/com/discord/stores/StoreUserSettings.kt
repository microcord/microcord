package com.discord.stores

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.core.content.edit
import com.discord.R
import com.discord.app.AppActivity
import com.discord.models.domain.Consents
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelGuildFolder
import com.discord.models.domain.ModelUserSettings
import com.discord.restapi.RestAPIInterface
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.persister.Persister
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscriber
import rx.Subscription
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

/**
 * The account's user settings, both the device-local ones and the ones the server owns.
 *
 * Server-owned settings live in [UserSettings]; this store keeps the local mirror the app reads
 * from, so a value survives a cold start before the settings proto has been decoded. Writes update
 * both, and [handleUserSettingsProto] brings inbound changes back the other way.
 *
 * The media/animation group is gated on [getIsSyncTextAndImagesEnabled] in both directions — that
 * is the "sync across devices" switch, and turning it off must leave those preferences
 * device-local, holding whatever value they last synced at.
 *
 * NOTE: the reference puts `activity` first; here it is a trailing defaulted parameter so the
 * existing call sites (and `::setter` method references in the settings screens) keep working. A
 * write no longer needs it — it is only where a confirmation toast is shown.
 */
class StoreUserSettings(
    private val dispatcher: Dispatcher,
    private val accessibilityStore: StoreAccessibility,
    private val preferences: SharedPreferences = SharedPreferencesProvider.get(),
    private val api: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    private val clock: Clock = ClockFactory.get(),
) : Store() {
    sealed class ConsentState {
        data class Loading(val current: Consents?) : ConsentState()
        data class Loaded(val consents: Consents) : ConsentState()
        data class Failed(val current: Consents?, val error: Throwable) : ConsentState()
    }

    private val shouldRenderEmbedsSubject = subject<Boolean>()
    private val mobileOverlaySubject = subject<Boolean>()
    private val explicitContentFilterSubject = subject(ModelUserSettings.EXPLICIT_CONTENT_FILTER_DISABLED)
    private val defaultGuildsRestrictedSubject = subject(false)
    private val friendSourceFlagsSubject = subject(ModelUserSettings.FriendSourceFlags())
    private val friendDiscoveryFlagsSubject = subject(0)
    private val customStatusSubject = subject<ModelCustomStatusSetting>()
    private val consentStateSubject = subject<ConsentState>(ConsentState.Loading(null))
    private val restrictedGuildIdsPublisher = Persister("RESTRICTED_GUILD_IDS", arrayListOf<Long>())
    private val guildFoldersPublisher = Persister("STORE_SETTINGS_FOLDERS_V1", arrayListOf<ModelGuildFolder>())
    private val allowAnimatedEmojisPublisher = Persister("STORE_SETTINGS_ALLOW_ANIMATED_EMOJIS", true)
    private val stickerAnimationSettingsPublisher = Persister("CACHE_KEY_STICKER_ANIMATION_SETTINGS_V1", 0)
    private val showCurrentGame = Persister("STORE_SETTINGS_ALLOW_GAME_STATUS", true)
    private val stickerSuggestionsPublisher = Persister("CACHE_KEY_STICKER_SUGGESTIONS", true)
    private val allowAccessibilityDetectionPublisher = Persister("STORE_SETTINGS_ALLOW_ACCESSIBILITY_DETECTION", false)
    private val autoPlayGifsPublisher = Persister("STORE_SETTINGS_AUTO_PLAY_GIFS", true)
    private var contactSyncUpsellShown: Boolean? = null
    private var customStatus = ModelCustomStatusSetting.CLEAR
    private var customStatusExpiration: Subscription? = null
    @Volatile private var consentsFetch: Subscription? = null
    @Volatile private var currentConsents: Consents? = null
    @Volatile private var consentApi: RestAPIInterface? = null
    @Volatile private var consentRequested = false

    override fun init(context: Context) {
        initForTesting()
    }

    internal fun initForTesting() {
        shouldRenderEmbedsSubject.onNext(getIsRenderEmbedsEnabled())
        mobileOverlaySubject.onNext(getIsMobileOverlayEnabled())
        updateAllowAccessibilityDetectionInternal(allowAccessibilityDetectionPublisher.get())
    }

    @Synchronized
    fun bindApi(api: RestAPIInterface) {
        if (consentApi !== api) {
            consentsFetch?.unsubscribe()
            consentsFetch = null
        }
        consentApi = api
        if (consentRequested) fetchConsents()
    }

    @Synchronized
    internal fun restoreApiForTesting(api: RestAPIInterface?) {
        consentsFetch?.unsubscribe()
        consentsFetch = null
        consentApi = api
    }

    // region reads

    fun getContactSyncUpsellShown() = contactSyncUpsellShown
    fun getCustomStatus() = customStatus
    fun getIsAttachmentMediaInline() = preferences.getBoolean(KEY_INLINE_ATTACHMENT_MEDIA, true)
    fun getIsAnimatedEmojisEnabled() = allowAnimatedEmojisPublisher.get()
    fun getIsAutoImageCompressionEnabled() = preferences.getBoolean(KEY_IMAGE_COMPRESSION, false)
    fun getIsAutoPlayGifsEnabled() = autoPlayGifsPublisher.get()
    fun getIsChromeCustomTabsEnabled() = preferences.getBoolean(KEY_CHROME_CUSTOM_TABS, true)
    fun getIsDeveloperMode() = preferences.getBoolean(KEY_DEVELOPER_MODE, false)
    fun getIsEmbedMediaInlined() = preferences.getBoolean(KEY_INLINE_EMBED_MEDIA, true)
    fun getIsMobileOverlayEnabled() = preferences.getBoolean(KEY_MOBILE_OVERLAY, false)
    fun getIsRenderEmbedsEnabled() = preferences.getBoolean(KEY_RENDER_EMBEDS, true)
    fun getIsShiftEnterToSendEnabled() = preferences.getBoolean(KEY_SHIFT_ENTER_TO_SEND, false)
    fun getIsStickerSuggestionsEnabled() = stickerSuggestionsPublisher.get()
    fun getIsSyncTextAndImagesEnabled() = preferences.getBoolean(KEY_SYNC_TEXT_AND_IMAGES, true)
    fun getStickerAnimationSettings() = stickerAnimationSettingsPublisher.get()
    internal fun getGuildFolders(): List<ModelGuildFolder> = guildFoldersPublisher.get().toList()

    // endregion

    // region observables
    //
    // `computationLatest` is not decoration: these subjects are fed from the store thread, and
    // without it every downstream operator — including UI binding — would run there.

    fun observeCustomStatus(): Observable<ModelCustomStatusSetting> =
        customStatusSubject.computationLatest().distinctUntilChanged()

    fun observeExplicitContentFilter(): Observable<Int> =
        explicitContentFilterSubject.computationLatest().distinctUntilChanged()

    fun observeFriendDiscoveryFlags(): Observable<Int> =
        friendDiscoveryFlagsSubject.computationLatest().distinctUntilChanged()

    fun observeFriendSourceFlags(): Observable<ModelUserSettings.FriendSourceFlags> =
        friendSourceFlagsSubject.computationLatest().distinctUntilChanged()

    fun observeGuildFolders(): Observable<List<ModelGuildFolder>> =
        guildFoldersPublisher.getObservable().computationLatest().map { it.toList() }.distinctUntilChanged()

    fun observeIsAccessibilityDetectionAllowed(): Observable<Boolean> =
        allowAccessibilityDetectionPublisher.getObservable().distinctUntilChanged()

    @JvmOverloads
    fun observeIsAnimatedEmojisEnabled(useReducedMotion: Boolean = true): Observable<Boolean> =
        reducedMotionAware(allowAnimatedEmojisPublisher.getObservable(), useReducedMotion) { enabled, reduced -> enabled && !reduced }

    @JvmOverloads
    fun observeIsAutoPlayGifsEnabled(useReducedMotion: Boolean = true): Observable<Boolean> =
        reducedMotionAware(autoPlayGifsPublisher.getObservable(), useReducedMotion) { enabled, reduced -> enabled && !reduced }

    fun observeIsDefaultGuildsRestricted(): Observable<Boolean> =
        defaultGuildsRestrictedSubject.computationLatest().distinctUntilChanged()

    fun observeIsRenderEmbedsEnabled(): Observable<Boolean> =
        shouldRenderEmbedsSubject.computationLatest().distinctUntilChanged()

    fun observeIsMobileOverlayEnabled(): Observable<Boolean> =
        mobileOverlaySubject.computationLatest().distinctUntilChanged()

    fun observeIsShowCurrentGameEnabled(): Observable<Boolean> = showCurrentGame.getObservable()
    fun observeIsStickerSuggestionsEnabled(): Observable<Boolean> = stickerSuggestionsPublisher.getObservable()

    fun observeRestrictedGuildIds(): Observable<List<Long>> =
        restrictedGuildIdsPublisher.getObservable().computationLatest().map { it.toList() }.distinctUntilChanged()

    /**
     * Reduced motion only downgrades "always animate" to "animate on interaction"; it never forces
     * "never animate". A user who explicitly chose to never animate keeps that, and a user who
     * chose on-interaction is already respecting the accessibility preference.
     */
    @JvmOverloads
    fun observeStickerAnimationSettings(useReducedMotion: Boolean = true): Observable<Int> =
        reducedMotionAware(stickerAnimationSettingsPublisher.getObservable(), useReducedMotion) { setting, reduced ->
            if (reduced && setting == ModelUserSettings.STICKER_ANIMATION_ALWAYS_ANIMATE) {
                ModelUserSettings.STICKER_ANIMATION_ANIMATE_ON_INTERACTION
            } else {
                setting
            }
        }

    fun observeConsentState(): Observable<ConsentState> = Observable.defer {
        fetchConsents()
        consentStateSubject.computationLatest().distinctUntilChanged()
    }

    // endregion

    // region gateway input

    @StoreThread
    fun handleConnectionOpen(settings: PreloadedUserSettings) {
        handleUserSettingsProto(settings)
        fetchConsents()
    }

    /** Mirrors the settings proto into the local copies the app reads from. */
    @StoreThread
    fun handleUserSettingsProto(settings: PreloadedUserSettings) {
        StoreThreadScheduler.assertCurrentThread()
        if (getIsSyncTextAndImagesEnabled()) {
            putBoolean(KEY_INLINE_EMBED_MEDIA, UserSettings.inlineEmbedMedia.get(settings))
            putBoolean(KEY_INLINE_ATTACHMENT_MEDIA, UserSettings.inlineAttachmentMedia.get(settings))
            setIsRenderEmbedsLocal(UserSettings.renderEmbeds.get(settings))
            allowAnimatedEmojisPublisher.set(UserSettings.animateEmoji.get(settings), persist = true)
            stickerAnimationSettingsPublisher.set(UserSettings.animateStickers.get(settings), persist = true)
            autoPlayGifsPublisher.set(UserSettings.gifAutoPlay.get(settings), persist = true)
        }
        guildFoldersPublisher.set(ArrayList(UserSettings.guildFolders.get(settings)))
        restrictedGuildIdsPublisher.set(ArrayList(UserSettings.restrictedGuildIds.get(settings)))
        putBoolean(KEY_DEVELOPER_MODE, UserSettings.developerMode.get(settings))
        showCurrentGame.set(UserSettings.showCurrentGame.get(settings))
        explicitContentFilterSubject.onNext(UserSettings.explicitContentFilter.get(settings))
        friendSourceFlagsSubject.onNext(UserSettings.friendSourceFlags.get(settings))
        defaultGuildsRestrictedSubject.onNext(UserSettings.defaultGuildsRestricted.get(settings))
        friendDiscoveryFlagsSubject.onNext(UserSettings.friendDiscoveryFlags.get(settings))
        contactSyncUpsellShown = UserSettings.contactSyncEnabled.get(settings)
        updateAllowAccessibilityDetectionInternal(UserSettings.allowAccessibilityDetection.get(settings))
        updateCustomStatusLocal(UserSettings.customStatus.get(settings))
    }

    // endregion

    // region device-local settings

    fun setContactSyncUpsellShown(shown: Boolean?) { contactSyncUpsellShown = shown }
    fun setIsAutoImageCompressionEnabled(enabled: Boolean) = putBoolean(KEY_IMAGE_COMPRESSION, enabled)
    fun setIsChromeCustomTabsEnabled(enabled: Boolean) = putBoolean(KEY_CHROME_CUSTOM_TABS, enabled)
    fun setIsShiftEnterToSendEnabled(enabled: Boolean) = putBoolean(KEY_SHIFT_ENTER_TO_SEND, enabled)
    fun setIsStickerSuggestionsEnabled(enabled: Boolean) = stickerSuggestionsPublisher.set(enabled, persist = true)

    /**
     * Turning sync back on pushes what the device is holding, rather than waiting for the next
     * inbound change to decide the question.
     */
    fun setIsSyncTextAndImagesEnabled(enabled: Boolean) {
        putBoolean(KEY_SYNC_TEXT_AND_IMAGES, enabled)
        if (!enabled) return
        UserSettings.inlineEmbedMedia.set(getIsEmbedMediaInlined())
        UserSettings.inlineAttachmentMedia.set(getIsAttachmentMediaInline())
        UserSettings.renderEmbeds.set(getIsRenderEmbedsEnabled())
        UserSettings.animateEmoji.set(getIsAnimatedEmojisEnabled())
        UserSettings.animateStickers.set(getStickerAnimationSettings())
        UserSettings.gifAutoPlay.set(getIsAutoPlayGifsEnabled())
    }

    fun setIsMobileOverlayEnabled(enabled: Boolean) {
        if (getIsMobileOverlayEnabled() == enabled) return
        putBoolean(KEY_MOBILE_OVERLAY, enabled)
        mobileOverlaySubject.onNext(enabled)
        // TODO(analytics): reference also fires AnalyticsTracker.overlayToggled(enabled).
    }

    // endregion

    // region server-synced settings

    @JvmOverloads
    fun setIsAutoPlayGifsEnabled(enabled: Boolean, activity: AppActivity? = null) {
        autoPlayGifsPublisher.set(enabled, persist = true)
        syncTextAndImages { UserSettings.gifAutoPlay.set(enabled) }
    }

    @JvmOverloads
    fun setIsAttachmentMediaInline(enabled: Boolean, activity: AppActivity? = null) {
        putBoolean(KEY_INLINE_ATTACHMENT_MEDIA, enabled)
        syncTextAndImages { UserSettings.inlineAttachmentMedia.set(enabled) }
    }

    @JvmOverloads
    fun setIsEmbedMediaInlined(enabled: Boolean, activity: AppActivity? = null) {
        putBoolean(KEY_INLINE_EMBED_MEDIA, enabled)
        syncTextAndImages { UserSettings.inlineEmbedMedia.set(enabled) }
    }

    @JvmOverloads
    fun setIsRenderEmbedsEnabled(enabled: Boolean, activity: AppActivity? = null) {
        setIsRenderEmbedsLocal(enabled)
        syncTextAndImages { UserSettings.renderEmbeds.set(enabled) }
    }

    @JvmOverloads
    fun setIsAnimatedEmojisEnabled(enabled: Boolean, activity: AppActivity? = null) {
        allowAnimatedEmojisPublisher.set(enabled, persist = true)
        syncTextAndImages { UserSettings.animateEmoji.set(enabled) }
    }

    @JvmOverloads
    fun setStickerAnimationSettings(setting: Int, activity: AppActivity? = null) {
        require(setting in ModelUserSettings.STICKER_ANIMATION_ALWAYS_ANIMATE..ModelUserSettings.STICKER_ANIMATION_NEVER_ANIMATE)
        stickerAnimationSettingsPublisher.set(setting, persist = true)
        syncTextAndImages { UserSettings.animateStickers.set(setting) }
    }

    /** Not gated on the sync switch: "display current activity" is an account-level privacy choice. */
    @JvmOverloads
    fun setIsShowCurrentGameEnabled(enabled: Boolean, activity: AppActivity? = null) {
        showCurrentGame.set(enabled, persist = true)
        UserSettings.showCurrentGame.set(enabled)
    }

    @JvmOverloads
    fun setIsDeveloperMode(enabled: Boolean, activity: AppActivity? = null) {
        if (getIsDeveloperMode() == enabled) return
        putBoolean(KEY_DEVELOPER_MODE, enabled)
        UserSettings.developerMode.set(enabled)
        activity?.let { Toast.makeText(it, R.string.theme_updated, Toast.LENGTH_SHORT).show() }
    }

    fun setIsAccessibilityDetectionAllowed(allowed: Boolean): Observable<Unit> {
        UserSettings.allowAccessibilityDetection.set(allowed)
        updateAllowAccessibilityDetectionInternal(allowed)
        return Observable.just(Unit)
    }

    fun setExplicitContentFilter(activity: AppActivity?, explicitContentFilter: Int) =
        UserSettings.explicitContentFilter.set(explicitContentFilter)

    fun setFriendDiscoveryFlags(activity: AppActivity?, flags: Int) =
        UserSettings.friendDiscoveryFlags.set(flags)

    fun setFriendSourceFlags(activity: AppActivity?, all: Boolean?, mutualGuilds: Boolean?, mutualFriends: Boolean?) {
        val current = UserSettings.friendSourceFlags.get()
        UserSettings.friendSourceFlags.set(
            ModelUserSettings.FriendSourceFlags(
                all = all ?: current.all,
                mutualFriends = mutualFriends ?: current.mutualFriends,
                mutualGuilds = mutualGuilds ?: current.mutualGuilds,
            ),
        )
    }

    fun setDefaultGuildsRestricted(
        activity: AppActivity?,
        defaultGuildsRestricted: Boolean,
        restrictedGuilds: Collection<Long>?,
    ) {
        UserSettings.defaultGuildsRestricted.set(defaultGuildsRestricted)
        restrictedGuilds?.let { UserSettings.restrictedGuildIds.set(it.toList()) }
    }

    fun setRestrictedGuildId(activity: AppActivity?, guildId: Long, restricted: Boolean) =
        setRestrictedGuildIds(activity, restrictedGuildIdsPublisher.get(), guildId, restricted)

    /**
     * The server owns the whole restricted-guild list, so a single toggle is sent as the mutated
     * list rather than a delta.
     */
    fun setRestrictedGuildIds(
        activity: AppActivity?,
        restrictedGuilds: Collection<Long>?,
        guildId: Long,
        restricted: Boolean,
    ) {
        val updated = ArrayList(restrictedGuilds.orEmpty())
        if (restricted && !updated.contains(guildId)) updated.add(guildId)
        if (!restricted) updated.remove(guildId)
        UserSettings.restrictedGuildIds.set(updated)
    }

    /** Records server-side that the contact-sync upsell has been shown, so no other client repeats it. */
    fun updateContactSyncShown() = UserSettings.contactSyncEnabled.set(true)

    /** Sets or clears the custom status; a null [customStatus] clears it. */
    fun updateCustomStatus(customStatus: ModelCustomStatusSetting?): Observable<Unit> {
        UserSettings.customStatus.set(customStatus ?: ModelCustomStatusSetting.CLEAR)
        return Observable.just(Unit)
    }

    // endregion

    @StoreThread
    internal fun reset() {
        StoreThreadScheduler.assertCurrentThread()
        consentsFetch?.unsubscribe()
        consentsFetch = null
        currentConsents = null
        consentRequested = false
        consentStateSubject.onNext(ConsentState.Loading(null))
        contactSyncUpsellShown = null
        shouldRenderEmbedsSubject.onNext(true)
        explicitContentFilterSubject.onNext(ModelUserSettings.EXPLICIT_CONTENT_FILTER_DISABLED)
        defaultGuildsRestrictedSubject.onNext(false)
        friendSourceFlagsSubject.onNext(ModelUserSettings.FriendSourceFlags())
        friendDiscoveryFlagsSubject.onNext(0)
        customStatus = ModelCustomStatusSetting.CLEAR
        customStatusSubject.onNext(customStatus)
        customStatusExpiration?.unsubscribe()
        customStatusExpiration = null
    }

    @Synchronized
    private fun fetchConsents() {
        consentRequested = true
        if (consentsFetch?.isUnsubscribed == false) return
        val api = consentApi ?: api()
        if (api == null) {
            consentStateSubject.onNext(
                ConsentState.Failed(currentConsents, IllegalStateException("Privacy consent service is unavailable.")),
            )
            return
        }
        consentsFetch?.unsubscribe()
        consentStateSubject.onNext(ConsentState.Loading(currentConsents))
        val subscriber = object : Subscriber<Consents>() {
            private var emitted = false

            override fun onNext(consents: Consents) {
                emitted = true
                currentConsents = consents
                consentStateSubject.onNext(ConsentState.Loaded(consents))
            }

            override fun onError(error: Throwable) {
                consentsFetch = null
                consentStateSubject.onNext(ConsentState.Failed(currentConsents, error))
            }

            override fun onCompleted() {
                consentsFetch = null
                if (!emitted) {
                    consentStateSubject.onNext(
                        ConsentState.Failed(
                            currentConsents,
                            IllegalStateException("Privacy consent response was empty."),
                        ),
                    )
                }
            }
        }
        consentsFetch = subscriber
        Observable.defer { api.getConsents() }
            .restSubscribeOn()
            .subscribe(subscriber)
    }

    /** The media/animation group only reaches the account while "sync across devices" is on. */
    private fun syncTextAndImages(write: () -> Unit) {
        if (getIsSyncTextAndImagesEnabled()) write()
    }

    private fun setIsRenderEmbedsLocal(enabled: Boolean) {
        if (getIsRenderEmbedsEnabled() == enabled) return
        putBoolean(KEY_RENDER_EMBEDS, enabled)
        shouldRenderEmbedsSubject.onNext(enabled)
    }

    /**
     * Clears an expired custom status. Local expiry alone would leave the status up on every other
     * client the account is signed in to.
     */
    private fun expireCustomStatus() {
        UserSettings.customStatus.set(ModelCustomStatusSetting.CLEAR)
    }

    private fun updateAllowAccessibilityDetectionInternal(allowed: Boolean) {
        allowAccessibilityDetectionPublisher.set(allowed)
        dispatcher.schedule { accessibilityStore.updateDetectionAllowed(allowed) }
    }

    @Synchronized
    private fun updateCustomStatusLocal(status: ModelCustomStatusSetting) {
        customStatusExpiration?.unsubscribe()
        customStatusExpiration = null
        customStatus = status
        customStatusSubject.onNext(status)
        if (status == ModelCustomStatusSetting.CLEAR) return
        val expiresAt = status.expiresAt ?: return
        customStatusExpiration = Observable.timer(
            (TimeUtils.parseUTCDate(expiresAt) - clock.currentTimeMillis()).coerceAtLeast(0),
            TimeUnit.MILLISECONDS,
        ).subscribe(
            {
                customStatus = ModelCustomStatusSetting.CLEAR
                customStatusSubject.onNext(customStatus)
                expireCustomStatus()
            },
            {},
        )
    }

    private fun putBoolean(key: String, value: Boolean) {
        if (preferences.getBoolean(key, !value) != value) preferences.edit { putBoolean(key, value) }
    }

    private fun <T, R> reducedMotionAware(
        source: Observable<T>,
        enabled: Boolean,
        combine: (T, Boolean) -> R,
    ): Observable<R> = if (enabled) {
        Observable.combineLatest(source, accessibilityStore.observeReducedMotionEnabled(), combine).distinctUntilChanged()
    } else {
        source.map { combine(it, false) }.distinctUntilChanged()
    }

    companion object {
        private const val KEY_INLINE_ATTACHMENT_MEDIA = "CACHE_KEY_INLINE_ATTACHMENT_MEDIA"
        private const val KEY_IMAGE_COMPRESSION = "CACHE_KEY_IMAGE_COMPRESSION"
        private const val KEY_CHROME_CUSTOM_TABS = "CACHE_KEY_USE_CHROME_CUSTOM_TABS"
        private const val KEY_DEVELOPER_MODE = "CACHE_KEY_DEVELOPER_MODE"
        private const val KEY_INLINE_EMBED_MEDIA = "CACHE_KEY_INLINE_EMBED_MEDIA"
        private const val KEY_MOBILE_OVERLAY = "CACHE_KEY_MOBILE_OVERLAY"
        private const val KEY_RENDER_EMBEDS = "CACHE_KEY_RENDER_EMBEDS"
        private const val KEY_SHIFT_ENTER_TO_SEND = "CACHE_KEY_SHIFT_ENTER_TO_SEND"
        private const val KEY_SYNC_TEXT_AND_IMAGES = "CACHE_KEY_SYNC_TEXT_AND_IMAGES"

        private fun <T> subject(): SerializedSubject<T, T> = SerializedSubject(BehaviorSubject.create())
        private fun <T> subject(value: T): SerializedSubject<T, T> = SerializedSubject(BehaviorSubject.create(value))
    }
}
