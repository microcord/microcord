package com.discord.stores

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelGuildFolder
import com.discord.models.domain.ModelUserSettings
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.persister.Persister
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

class StoreUserSettings(
    private val dispatcher: Dispatcher,
    private val accessibilityStore: StoreAccessibility,
    private val preferences: SharedPreferences = SharedPreferencesProvider.get(),
) : Store() {
    private val shouldRenderEmbedsSubject = subject<Boolean>()
    private val explicitContentFilterSubject = subject<Int>()
    private val defaultGuildsRestrictedSubject = subject<Boolean>()
    private val friendSourceFlagsSubject = subject<ModelUserSettings.FriendSourceFlags>()
    private val friendDiscoveryFlagsSubject = subject(0)
    private val customStatusSubject = subject<ModelCustomStatusSetting>()
    private val restrictedGuildIdsPublisher = Persister("RESTRICTED_GUILD_IDS", arrayListOf<Long>())
    private val guildFoldersPublisher = Persister("STORE_SETTINGS_FOLDERS_V1", arrayListOf<ModelGuildFolder>())
    private val allowAnimatedEmojisPublisher = Persister("STORE_SETTINGS_ALLOW_ANIMATED_EMOJIS", true)
    private val stickerAnimationSettingsPublisher = Persister("CACHE_KEY_STICKER_ANIMATION_SETTINGS_V1", 0)
    private val showCurrentGame = Persister("STORE_SETTINGS_ALLOW_GAME_STATUS", true)
    private val stickerSuggestionsPublisher = Persister("CACHE_KEY_STICKER_SUGGESTIONS", true)
    private val allowAccessibilityDetectionPublisher = Persister("STORE_SETTINGS_ALLOW_ACCESSIBILITY_DETECTION", false)
    private val autoPlayGifsPublisher = Persister("STORE_SETTINGS_AUTO_PLAY_GIFS", true)
    private var contactSyncUpsellShown: Boolean? = null

    override fun init(context: Context) {
        initForTesting()
    }

    internal fun initForTesting() {
        shouldRenderEmbedsSubject.onNext(getIsRenderEmbedsEnabled())
        updateAllowAccessibilityDetectionInternal(allowAccessibilityDetectionPublisher.get())
    }

    fun getContactSyncUpsellShown() = contactSyncUpsellShown
    fun getIsAttachmentMediaInline() = preferences.getBoolean(KEY_INLINE_ATTACHMENT_MEDIA, true)
    fun getIsAutoImageCompressionEnabled() = preferences.getBoolean(KEY_IMAGE_COMPRESSION, false)
    fun getIsAutoPlayGifsEnabled() = preferences.getBoolean(KEY_AUTO_PLAY_GIFS, true)
    fun getIsChromeCustomTabsEnabled() = preferences.getBoolean(KEY_CHROME_CUSTOM_TABS, true)
    fun getIsDeveloperMode() = preferences.getBoolean(KEY_DEVELOPER_MODE, false)
    fun getIsEmbedMediaInlined() = preferences.getBoolean(KEY_INLINE_EMBED_MEDIA, true)
    fun getIsMobileOverlayEnabled() = preferences.getBoolean(KEY_MOBILE_OVERLAY, false)
    fun getIsRenderEmbedsEnabled() = preferences.getBoolean(KEY_RENDER_EMBEDS, true)
    fun getIsShiftEnterToSendEnabled() = preferences.getBoolean(KEY_SHIFT_ENTER_TO_SEND, false)
    fun getIsStickerSuggestionsEnabled() = stickerSuggestionsPublisher.get()
    fun getIsSyncTextAndImagesEnabled() = preferences.getBoolean(KEY_SYNC_TEXT_AND_IMAGES, true)
    fun getStickerAnimationSettings() = stickerAnimationSettingsPublisher.get()

    fun observeCustomStatus(): Observable<ModelCustomStatusSetting> = customStatusSubject.distinctUntilChanged()
    fun observeExplicitContentFilter(): Observable<Int> = explicitContentFilterSubject.distinctUntilChanged()
    fun observeFriendDiscoveryFlags(): Observable<Int> = friendDiscoveryFlagsSubject.distinctUntilChanged()
    fun observeFriendSourceFlags(): Observable<ModelUserSettings.FriendSourceFlags> = friendSourceFlagsSubject.distinctUntilChanged()
    fun observeGuildFolders(): Observable<List<ModelGuildFolder>> = guildFoldersPublisher.getObservable().map { it.toList() }.distinctUntilChanged()
    internal fun getGuildFolders(): List<ModelGuildFolder> = guildFoldersPublisher.get().toList()
    fun observeIsAccessibilityDetectionAllowed(): Observable<Boolean> = allowAccessibilityDetectionPublisher.getObservable().distinctUntilChanged()
    @JvmOverloads
    fun observeIsAnimatedEmojisEnabled(useReducedMotion: Boolean = true): Observable<Boolean> =
        reducedMotionAware(allowAnimatedEmojisPublisher.getObservable(), useReducedMotion) { enabled, reduced -> enabled && !reduced }
    @JvmOverloads
    fun observeIsAutoPlayGifsEnabled(useReducedMotion: Boolean = true): Observable<Boolean> =
        reducedMotionAware(autoPlayGifsPublisher.getObservable(), useReducedMotion) { enabled, reduced -> enabled && !reduced }
    fun observeIsDefaultGuildsRestricted(): Observable<Boolean> = defaultGuildsRestrictedSubject.distinctUntilChanged()
    fun observeIsRenderEmbedsEnabled(): Observable<Boolean> = shouldRenderEmbedsSubject.distinctUntilChanged()
    fun observeIsShowCurrentGameEnabled(): Observable<Boolean> = showCurrentGame.getObservable()
    fun observeIsStickerSuggestionsEnabled(): Observable<Boolean> = stickerSuggestionsPublisher.getObservable()
    fun observeRestrictedGuildIds(): Observable<List<Long>> = restrictedGuildIdsPublisher.getObservable().map { it.toList() }.distinctUntilChanged()
    @JvmOverloads
    fun observeStickerAnimationSettings(useReducedMotion: Boolean = true): Observable<Int> =
        reducedMotionAware(stickerAnimationSettingsPublisher.getObservable(), useReducedMotion) { setting, reduced ->
            if (reduced) ModelUserSettings.STICKER_ANIMATION_NEVER_ANIMATE else setting
        }

    @StoreThread
    fun handleConnectionOpen(userSettings: ModelUserSettings) = handleUserSettings(userSettings)

    @StoreThread
    fun handleUserSettingsUpdate(userSettings: ModelUserSettings) = handleUserSettings(userSettings)

    @StoreThread
    private fun handleUserSettings(settings: ModelUserSettings) {
        StoreThreadScheduler.assertCurrentThread()
        if (getIsSyncTextAndImagesEnabled()) {
            settings.inlineEmbedMedia?.let { putBoolean(KEY_INLINE_EMBED_MEDIA, it) }
            settings.inlineAttachmentMedia?.let { putBoolean(KEY_INLINE_ATTACHMENT_MEDIA, it) }
            settings.renderEmbeds?.let {
                putBoolean(KEY_RENDER_EMBEDS, it)
                shouldRenderEmbedsSubject.onNext(it)
            }
            settings.animateEmoji?.let(allowAnimatedEmojisPublisher::set)
            settings.animateStickers?.let(stickerAnimationSettingsPublisher::set)
        }
        settings.guildFolders?.let { guildFoldersPublisher.set(ArrayList(it)) }
        settings.restrictedGuilds?.let { restrictedGuildIdsPublisher.set(ArrayList(it)) }
        settings.developerMode?.let { putBoolean(KEY_DEVELOPER_MODE, it) }
        settings.showCurrentGame?.let(showCurrentGame::set)
        settings.explicitContentFilter?.let(explicitContentFilterSubject::onNext)
        settings.friendSourceFlags?.let(friendSourceFlagsSubject::onNext)
        settings.defaultGuildsRestricted?.let(defaultGuildsRestrictedSubject::onNext)
        settings.customStatus?.let(customStatusSubject::onNext)
        settings.friendDiscoveryFlags?.let(friendDiscoveryFlagsSubject::onNext)
        settings.contactSyncUpsellShown?.let { contactSyncUpsellShown = it }
        settings.allowAccessibilityDetection?.let(::updateAllowAccessibilityDetectionInternal)
    }

    fun setContactSyncUpsellShown(shown: Boolean?) { contactSyncUpsellShown = shown }
    fun setIsAutoImageCompressionEnabled(enabled: Boolean) = putBoolean(KEY_IMAGE_COMPRESSION, enabled)
    fun setIsAutoPlayGifsEnabled(enabled: Boolean) = autoPlayGifsPublisher.set(enabled)
    fun setIsChromeCustomTabsEnabled(enabled: Boolean) = putBoolean(KEY_CHROME_CUSTOM_TABS, enabled)
    fun setIsMobileOverlayEnabled(enabled: Boolean) = putBoolean(KEY_MOBILE_OVERLAY, enabled)
    fun setIsShiftEnterToSendEnabled(enabled: Boolean) = putBoolean(KEY_SHIFT_ENTER_TO_SEND, enabled)
    fun setIsStickerSuggestionsEnabled(enabled: Boolean) = stickerSuggestionsPublisher.set(enabled, persist = true)
    fun setIsSyncTextAndImagesEnabled(enabled: Boolean) = putBoolean(KEY_SYNC_TEXT_AND_IMAGES, enabled)

    fun setIsAccessibilityDetectionAllowed(allowed: Boolean) {
        allowAccessibilityDetectionPublisher.set(allowed)
        updateAllowAccessibilityDetectionInternal(allowed)
    }

    @StoreThread
    internal fun reset() {
        StoreThreadScheduler.assertCurrentThread()
        contactSyncUpsellShown = null
        shouldRenderEmbedsSubject.onNext(true)
        explicitContentFilterSubject.onNext(ModelUserSettings.EXPLICIT_CONTENT_FILTER_DISABLED)
        defaultGuildsRestrictedSubject.onNext(false)
        friendSourceFlagsSubject.onNext(ModelUserSettings.FriendSourceFlags())
        friendDiscoveryFlagsSubject.onNext(0)
        customStatusSubject.onNext(ModelCustomStatusSetting.CLEAR)
    }

    private fun updateAllowAccessibilityDetectionInternal(allowed: Boolean) {
        dispatcher.schedule { accessibilityStore.updateDetectionAllowed(allowed) }
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
        private const val KEY_AUTO_PLAY_GIFS = "STORE_SETTINGS_AUTO_PLAY_GIFS"
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
