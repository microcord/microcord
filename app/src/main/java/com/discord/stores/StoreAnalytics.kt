package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isThread
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.api.thread.ThreadMemberFlags
import com.discord.api.voice.state.VoiceState
import com.discord.app.AppComponent
import com.discord.app.AppLog
import com.discord.app.AppLogger
import com.discord.app.ImpressionTracker
import com.discord.models.domain.ModelGift
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.native.engine.VideoInputDeviceDescription
import com.discord.rtcconnection.RtcConnection
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.rtcconnection.mediaengine.MediaEngine
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.AnalyticsUtils
import com.discord.utilities.analytics.AppStartAnalyticsTracker
import com.discord.utilities.analytics.SearchType
import com.discord.utilities.analytics.Traits
import com.discord.utilities.analytics.analyticsProperties
import com.discord.utilities.collections.filterNonNullValues
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Raises every analytics event that needs store state to describe itself.
 *
 * A screen knows *what* happened; only the store graph knows the guild's size, the channel's
 * permissions, whether the user is in a voice channel, or which screen came up first after launch.
 * This store is where the two meet: a call site names the event, this store takes the snapshot on
 * the store thread, and [AnalyticsTracker] owns the wire format.
 *
 * It also owns the session's tracking data. `handleConnectionOpen`, `handleAuthToken` and
 * `handleFingerprint` feed the analytics token and fingerprint in; [updateTrackingData] decides
 * which of the three states the session is in — fingerprinted-anonymous, authenticated, or neither
 * — and tells the tracker, which holds events back until one of the first two applies.
 *
 * **Nothing raised here is transmitted by default.** See
 * [com.discord.utilities.analytics.AnalyticsSink].
 */
class StoreAnalytics(
    private val stores: Stores,
    private val dispatcher: Dispatcher,
    private val clock: Clock,
) : Store() {

    /**
     * The slice of the store graph the analytics snapshots read.
     *
     * The reference takes `StoreStream` itself and reads its stores directly. That is not possible
     * here: every store `StoreStream` owns is `private`, so this store declares exactly what it
     * needs and `StoreStream` implements it. Every member has an inert default, so an unwired or
     * partially-wired graph produces empty snapshots rather than failing to build — see [None].
     */
    interface Stores {
        // Channels
        fun getChannel(channelId: Long): Channel? = null
        fun getChannelsForGuild(guildId: Long): Collection<Channel> = emptyList()
        fun getSelectedChannelId(): Long = 0L

        // Guilds
        fun getGuild(guildId: Long): Guild? = null
        fun getSelectedGuildId(): Long = 0L
        fun getApproximateMemberCount(guildId: Long): Int = 0
        fun getGuildRoleCount(guildId: Long): Int = 0
        fun getGuildMemberRoleCount(guildId: Long, userId: Long): Int = 0
        fun isGuildMember(guildId: Long, userId: Long): Boolean = false
        fun isGuildMemberPending(guildId: Long, userId: Long): Boolean = false
        fun getGuildNotificationSettings(guildId: Long): ModelNotificationSettings? = null

        // Permissions
        fun getGuildPermissions(guildId: Long): Long? = null
        fun getChannelPermissions(channelId: Long): Long? = null

        // Users
        fun getMeId(): Long = 0L

        // Threads
        fun getThreadMessageCount(channelId: Long): Int? = null

        // Voice and media
        fun getVoiceStatesForGuild(guildId: Long): Map<Long, VoiceState> = emptyMap()
        fun getSelectedVoiceChannelId(): Long = 0L
        fun getLastSelectedVoiceChannelId(): Long = 0L
        fun observeSelectedVoiceChannel(): Observable<Channel?> = Observable.empty()
        fun observeInputMode(): Observable<MediaEngineConnection.InputMode> = Observable.empty()
        fun observeAudioInfo(): Observable<MediaEngine.AudioInfo> = Observable.empty()
        fun getRtcConnectionMetadata(): RtcConnection.Metadata? = null
        fun getPreferredRtcRegion(): String? = null
        fun getVoiceConfiguration(): StoreMediaSettings.VoiceConfiguration? = null
        fun isScreenSharing(): Boolean = false
        fun getSelectedVideoInputDevice(): VideoInputDeviceDescription? = null
        fun getMaxViewersForStream(userId: Long, channelId: Long, guildId: Long?): Int? = null

        // Misc
        fun getTheme(): String = ""

        /** What the user is currently playing, for voice-event enrichment. */
        fun getLocalGameProperties(meId: Long): Map<String, Any> = emptyMap()

        /** Gift state, for the gift-acceptance modal. */
        fun getKnownGift(giftCode: String): ModelGift? = null
    }

    /** Every accessor at its default. Lets the store be constructed before the graph is wired. */
    object None : Stores

    private val screenViewedSubject = PublishSubject.create<ScreenViewed>()
    private val hasTrackedAppUiShown = AtomicBoolean(false)

    private var analyticsToken: String? = null
    private var authToken: String? = null
    private var fingerprint: String? = null
    private var inputMode: String? = null
    private var selectedVoiceChannel: Channel? = null

    override fun init(context: Context) {
        super.init(context)

        // Give the two singletons that cannot reach the store graph a way to read it.
        AnalyticsTracker.selectedChannelProperties = {
            stores.getChannel(stores.getSelectedChannelId())
                ?.let { mapOf<String, Any>("guild_id" to it.guildId) + it.analyticsProperties }
                .orEmpty()
        }
        AnalyticsTracker.gameProperties = { meId -> stores.getLocalGameProperties(meId) }
        AnalyticsUtils.guildTraitResolver = { trackGuild ->
            val guildId = trackGuild.guildId
            val guild = guildId?.let(stores::getGuild)
            if (guild == null) {
                trackGuild
            } else {
                val meId = stores.getMeId()
                trackGuild.copy(
                    isMember = stores.isGuildMember(guild.id, meId),
                    isOwner = guild.isOwner(meId),
                )
            }
        }
        AppStartAnalyticsTracker.install(
            AppStartAnalyticsTracker(clock = clock, themeProvider = stores::getTheme),
        )

        // Screen impressions are raised by AppLogger, which every AppFragment/AppBottomSheet owns.
        // Its default tracker only records the schema type name locally; route it into the real
        // pipeline now that one exists. It is still subject to the disabled-by-default sink.
        AppLogger.defaultTracker = ImpressionTracker { schema -> AnalyticsTracker.tracker.track(schema) }

        stores.observeInputMode()
            .computationBuffered()
            .appSubscribe(errorClass = StoreAnalytics::class.java) { inputMode = it.toString() }

        stores.observeSelectedVoiceChannel()
            .computationBuffered()
            .appSubscribe(errorClass = StoreAnalytics::class.java) { selectedVoiceChannel = it }

        screenViewedSubject
            .first()
            .appSubscribe(errorClass = StoreAnalytics::class.java, onNext = ::onScreenViewed)
    }

    // -- Session tracking data ---------------------------------------------------------------------

    @StoreThread
    fun handleConnectionOpen(payload: com.discord.models.domain.ModelPayload) {
        analyticsToken = payload.analyticsToken
        updateTrackingData()
    }

    @StoreThread
    fun handleAuthToken(authToken: String?) {
        this.authToken = authToken
        updateTrackingData()
    }

    @StoreThread
    fun handleFingerprint(fingerprint: String?) {
        this.fingerprint = fingerprint
        updateTrackingData()
    }

    @StoreThread
    fun handleConnected(connected: Boolean) {
        if (!connected) {
            analyticsToken = null
            updateTrackingData()
        }
    }

    /**
     * Records when the user logged out, so the next `app_landing_viewed` can report how long the
     * client sat logged out. Written to the session-durable preferences because it has to outlive
     * the logout wipe.
     */
    @StoreThread
    fun handlePreLogout() {
        prefsSessionDurable.edit().putLong(CACHE_KEY_LOGOUT_TS, clock.currentTimeMillis()).apply()
    }

    /**
     * Chooses the session's tracking mode.
     *
     * Anonymous-with-fingerprint wins over an authenticated token: a session that has a fingerprint
     * but no auth token yet is mid-registration, and the backend needs the fingerprint to join the
     * pre-login funnel to the account that comes out of it.
     */
    private fun updateTrackingData() {
        val isAuthenticated = !authToken.isNullOrEmpty()
        val isFingerprintedOnly = !isAuthenticated && !fingerprint.isNullOrEmpty()
        val hasAnalyticsToken = isAuthenticated && !analyticsToken.isNullOrEmpty()

        when {
            isFingerprintedOnly -> AnalyticsTracker.tracker.setTrackingData(null, fingerprinted = true)
            hasAnalyticsToken -> AnalyticsTracker.tracker.setTrackingData(analyticsToken)
            else -> AnalyticsTracker.tracker.setTrackingData(null, fingerprinted = false)
        }
    }

    // -- Screens --------------------------------------------------------------------------------------

    /**
     * A screen was shown. Only the first one per process produces an event: this measures cold
     * start, not navigation.
     */
    fun appUiViewed(screen: Class<out AppComponent>) {
        screenViewedSubject.onNext(ScreenViewed(screen, clock.currentTimeMillis()))
    }

    fun appLandingViewed() {
        AnalyticsTracker.appLandingViewed(prefsSessionDurable.getLong(CACHE_KEY_LOGOUT_TS, 0L))
    }

    private fun onScreenViewed(screenViewed: ScreenViewed) {
        if (hasTrackedAppUiShown.getAndSet(true)) return
        dispatcher.schedule {
            AppStartAnalyticsTracker.instance.appUiViewed(screenNameOf(screenViewed.screen), screenViewed.timestamp)
        }
    }

    /**
     * The analytics name for a landing screen.
     *
     * Matched on the simple class name rather than a class literal: the reference compares against
     * four `Widget*` classes and one of them (`WidgetGuildInvite`) does not exist in the target yet,
     * so a literal would not compile and a missing screen would silently stop matching later.
     */
    private fun screenNameOf(screen: Class<out AppComponent>): String = when (val name = screen.simpleName) {
        "WidgetHome" -> if (stores.getSelectedGuildId() == 0L) "private_channel" else "guild"
        "WidgetGuildInvite" -> "invite"
        "WidgetIncomingShare" -> "share"
        "WidgetAuthLanding" -> "app_landing"
        else -> name
    }

    private data class ScreenViewed(val screen: Class<out AppComponent>, val timestamp: Long)

    // -- Guilds and channels ----------------------------------------------------------------------------

    /** The user switched to [guildId]. Throttled per guild inside the tracker. */
    @StoreThread
    fun trackGuildViewed(guildId: Long) {
        val guild = stores.getGuild(guildId) ?: return
        val meId = stores.getMeId()
        val isPending = stores.isGuildMemberPending(guildId, meId)
        val isPreviewEnabled = runCatching { GuildFeature.PREVIEW_ENABLED in guild.features }
            .onFailure { AppLog.e("Guild is missing feature set", it, mapOf("guild_id" to guildId.toString())) }
            .getOrDefault(false)

        AnalyticsTracker.guildViewed(guildId) {
            getGuildAnalyticsPropertiesInternal(guildId) + mapOf(
                "is_pending" to isPending,
                "guild_is_preview_enabled" to isPreviewEnabled,
            )
        }
    }

    /** The user opened [channelId]. Throttled per channel inside the tracker. */
    @StoreThread
    fun trackChannelOpened(
        channelId: Long,
        channelView: ChannelAnalyticsViewType,
        analyticsLocation: SelectedChannelAnalyticsLocation?,
    ) {
        val channel = stores.getChannel(channelId) ?: return
        AnalyticsTracker.channelOpened(channelId) {
            val base = if (channel.isThread()) {
                getThreadSnapshotAnalyticsProperties(channel)
            } else {
                getChannelSnapshotAnalyticsProperties(channel, includeNsfw = true)
            }
            base +
                mapOf("channel_view" to channelView.analyticsValue) +
                (analyticsLocation?.let { mapOf("location" to it.analyticsValue) } ?: emptyMap())
        }
    }

    /** The text channel attached to a voice channel was opened. */
    @StoreThread
    fun trackTextInVoiceOpened(channelId: Long, analyticsLocation: SelectedChannelAnalyticsLocation?) {
        val channel = stores.getChannel(channelId) ?: return
        AnalyticsTracker.textInVoiceOpened(channelId) {
            getChannelSnapshotAnalyticsProperties(channel, includeNsfw = true) +
                (analyticsLocation?.let { mapOf("location" to it.analyticsValue) } ?: emptyMap())
        }
    }

    fun trackGuildProfileOpened(guildId: Long) = AnalyticsTracker.openGuildProfileSheet(guildId)

    fun trackThreadBrowserTabChanged(channelId: Long, tabType: String) {
        AnalyticsTracker.threadBrowserTabChanged(getGuildAndChannelSnapshotAnalyticsProperties(channelId), tabType)
    }

    fun ackMessage(channelId: Long) = dispatcher.schedule {
        val channel = stores.getChannel(channelId) ?: return@schedule
        val properties = getGuildAndChannelSnapshotAnalyticsProperties(channelId)
        AnalyticsTracker.ackMessage(channel) { properties }
    }

    // -- Settings panes ---------------------------------------------------------------------------------

    fun onGuildSettingsPaneViewed(pane: String, guildId: Long) = dispatcher.schedule {
        AnalyticsTracker.settingsPaneViewed("guild", pane, getGuildAnalyticsPropertiesInternal(guildId))
    }

    @JvmOverloads
    fun onUserSettingsPaneViewed(pane: String, locationSection: String? = null) {
        AnalyticsTracker.settingsPaneViewed(
            "user",
            pane,
            locationSection?.let { mapOf("location_section" to it) },
        )
    }

    /**
     * Guild or channel notification settings changed.
     *
     * The channel is only resolved when the guild actually carries an override for it: without one,
     * the update is a guild-level change that happens to have been made from a channel screen.
     */
    fun onNotificationSettingsUpdated(notifSettings: ModelNotificationSettings, channelId: Long?) =
        dispatcher.schedule {
            val overriddenChannelId = channelId
                ?.takeIf { id -> notifSettings.channelOverrides.any { it.channelId == id } }
            val channel = overriddenChannelId?.let(stores::getChannel)
            AnalyticsTracker.notificationSettingsUpdated(notifSettings, channel)
        }

    fun onThreadNotificationSettingsUpdated(channelId: Long, parentChannelId: Long, flags: Int, oldFlags: Int) =
        dispatcher.schedule {
            val thread = stores.getChannel(channelId) ?: return@schedule
            val parent = stores.getChannel(parentChannelId) ?: return@schedule
            val guildSettings = stores.getGuildNotificationSettings(thread.guildId) ?: return@schedule
            val parentOverride = guildSettings.getChannelOverride(parent.id)
            AnalyticsTracker.threadNotificationSettingsUpdated(
                threadProperties = getThreadSnapshotAnalyticsProperties(thread),
                hasInteractedWith = (oldFlags and ThreadMemberFlags.HAS_INTERACTED) != 0,
                parentIsMuted = parentOverride?.isMuted ?: guildSettings.isMuted,
                parentNotificationSetting = parentOverride?.messageNotifications
                    ?: guildSettings.messageNotifications,
                oldFlags = oldFlags,
                flags = flags,
            )
        }

    // -- Chat input, autocomplete and commands ------------------------------------------------------------

    fun trackChatInputComponentViewed(type: String) = AnalyticsTracker.chatInputComponentViewed(type)

    fun trackAutocompleteOpen(channelId: Long, autocompleteType: String, numEmojiResults: Int, numStickerResults: Int) {
        AnalyticsTracker.autocompleteOpen(
            getGuildAndChannelSnapshotAnalyticsProperties(channelId).filterNonNullValues(),
            autocompleteType,
            numEmojiResults,
            numStickerResults,
        )
    }

    fun trackAutocompleteSelect(
        channelId: Long,
        autocompleteType: String,
        numEmojiResults: Int,
        numStickerResults: Int,
        selectionType: String?,
        selection: String?,
        stickerId: Long?,
    ) {
        AnalyticsTracker.autocompleteSelect(
            getGuildAndChannelSnapshotAnalyticsProperties(channelId).filterNonNullValues(),
            autocompleteType,
            numEmojiResults,
            numStickerResults,
            selectionType,
            selection,
            stickerId,
        )
    }

    fun trackApplicationCommandBrowserOpened(channelId: Long) {
        AnalyticsTracker.applicationCommandBrowserOpened(getGuildAndChannelSnapshotAnalyticsProperties(channelId))
    }

    fun trackApplicationCommandBrowserScrolled() = AnalyticsTracker.applicationCommandBrowserScrolled()

    fun trackApplicationCommandBrowserJump(targetApplicationId: Long) =
        AnalyticsTracker.applicationCommandBrowserJump(targetApplicationId)

    fun trackApplicationCommandSelected(channelId: Long, applicationId: Long, commandId: Long) {
        AnalyticsTracker.applicationCommandSelected(
            applicationId,
            commandId,
            getGuildAndChannelSnapshotAnalyticsProperties(channelId),
        )
    }

    fun trackApplicationCommandValidationFailure(
        applicationId: Long,
        commandId: Long,
        argumentType: String,
        isRequired: Boolean,
    ) = AnalyticsTracker.applicationCommandValidationFailure(applicationId, commandId, argumentType, isRequired)

    // -- Emoji popouts and premium upsells -------------------------------------------------------------------

    fun openCustomEmojiPopout(
        channel: Channel,
        emojiId: Long,
        isPremium: Boolean,
        joinedSourceGuild: Boolean,
        sourceGuildPrivate: Boolean,
    ) = AnalyticsTracker.openCustomEmojiPopout(
        channel.guildId,
        emojiId,
        isPremium,
        joinedSourceGuild,
        sourceGuildPrivate,
        getChannelSnapshotAnalyticsProperties(channel).filterNonNullValues(),
    )

    fun openUnicodeEmojiPopout(channel: Channel) = AnalyticsTracker.openUnicodeEmojiPopout(
        channel.guildId,
        getChannelSnapshotAnalyticsProperties(channel).filterNonNullValues(),
    )

    fun emojiAutocompleteUpsellInlineViewed() = AnalyticsTracker.premiumUpsellViewed(
        AnalyticsTracker.PremiumUpsellType.EmojiAutocompleteInline,
    )

    fun emojiAutocompleteUpsellModalViewed() = AnalyticsTracker.premiumUpsellViewed(
        AnalyticsTracker.PremiumUpsellType.EmojiAutocompleteModal,
    )

    fun streamQualityIndicatorViewed(hasPremiumStreamResolution: Boolean, hasPremiumStreamFps: Boolean?) =
        AnalyticsTracker.premiumUpsellViewed(
            AnalyticsTracker.PremiumUpsellType.StreamQualityIndicator,
            properties = mapOf<String, Any?>(
                "has_premium_stream_fps" to hasPremiumStreamFps,
                "has_premium_stream_resolution" to hasPremiumStreamResolution,
            ).filterNonNullValues(),
        )

    /** Where an emoji-picker upsell was shown: a DM and a guild channel are different funnels. */
    val emojiPickerUpsellLocation: Traits.Location
        get() {
            val channel = stores.getChannel(stores.getSelectedChannelId())
            val page = if (channel != null && channel.guildId == 0L) "DM Channel" else "Guild Channel"
            return Traits.Location(page = page, section = Traits.Location.Section.EMOJI_PICKER_POPOUT)
        }

    fun emojiPickerUpsellHeaderViewed(isDmChannel: Boolean) =
        emojiPickerUpsellViewed(AnalyticsTracker.PremiumUpsellType.EmojiPickerHeaderViewed, isDmChannel)

    fun emojiPickerUpsellLockedItemClicked(isDmChannel: Boolean, isAnimated: Boolean) =
        emojiPickerUpsellViewed(
            AnalyticsTracker.PremiumUpsellType.EmojiPickerLockedItemClicked,
            isDmChannel,
            mapOf("is_animated" to isAnimated, "is_external" to true),
        )

    private fun emojiPickerUpsellViewed(
        type: AnalyticsTracker.PremiumUpsellType,
        isDmChannel: Boolean,
        properties: Map<String, Any> = emptyMap(),
    ) {
        AnalyticsTracker.premiumUpsellViewed(
            type,
            Traits.Location(
                page = if (isDmChannel) "DM Channel" else "Guild Channel",
                section = Traits.Location.Section.EMOJI_PICKER_POPOUT,
                _object = Traits.Location.Obj.BUTTON_UPSELL,
                objectType = Traits.Location.ObjType.SEARCH,
            ),
            properties + mapOf("has_search_query" to true),
        )
    }

    // -- Search --------------------------------------------------------------------------------------------------

    @JvmOverloads
    fun trackSearchStarted(
        searchType: SearchType,
        locationTrait: Traits.Location? = null,
        throttle: Boolean = false,
    ) = AnalyticsTracker.searchStart(searchType, locationTrait, snapshotAnalyticsProperties, throttle)

    @JvmOverloads
    fun trackSearchResultsEmpty(
        searchType: SearchType,
        locationTrait: Traits.Location? = null,
        throttle: Boolean = false,
    ) = AnalyticsTracker.searchResultsEmpty(searchType, locationTrait, snapshotAnalyticsProperties, throttle)

    @JvmOverloads
    fun trackSearchResultViewed(
        searchType: SearchType,
        totalResultsCount: Int,
        lockedResultsCount: Int? = null,
        locationTrait: Traits.Location? = null,
        throttle: Boolean = false,
    ) = AnalyticsTracker.searchResultViewed(
        searchType, totalResultsCount, lockedResultsCount, locationTrait, snapshotAnalyticsProperties, throttle,
    )

    @JvmOverloads
    fun trackSearchResultSelected(
        searchType: SearchType,
        totalResultsCount: Int,
        locationTrait: Traits.Location? = null,
        sourceTrait: Traits.Source? = null,
    ) = AnalyticsTracker.searchResultSelected(
        searchType, locationTrait, sourceTrait, totalResultsCount, snapshotAnalyticsProperties,
    )

    // -- Invites and gifts ------------------------------------------------------------------------------------------

    /**
     * An invite was sent into a channel.
     *
     * The invite's own identity is supplied by the caller — this store only knows the message it
     * went into.
     */
    fun inviteSent(
        location: String,
        messageId: Long,
        messageChannelId: Long,
        inviteGuildId: Long?,
        inviteChannelId: Long?,
        inviteGuildScheduledEventId: Long?,
    ) = AnalyticsTracker.inviteSent(
        mapOf<String, Any?>(
            "location" to location,
            "message_id" to messageId,
            "invite_guild_id" to inviteGuildId,
            "invite_channel_id" to inviteChannelId,
            "invite_guild_scheduled_event_id" to inviteGuildScheduledEventId,
        ) + getGuildAndChannelSnapshotAnalyticsProperties(messageChannelId),
    )

    fun inviteSuggestionOpened(guildId: Long, numFriends: Int, numDms: Int, numGroupDms: Int) =
        AnalyticsTracker.inviteSuggestionOpened(guildId, numFriends + numDms + numGroupDms, numFriends, numDms, numGroupDms)

    fun trackOpenGiftAcceptModal(giftCode: String, location: String, channelId: Long) = dispatcher.schedule {
        val gift = stores.getKnownGift(giftCode) ?: return@schedule
        AnalyticsTracker.openGiftModal(gift, stores.getChannel(channelId), location)
    }

    // -- Overlay ---------------------------------------------------------------------------------------------------

    fun onOverlayVoiceEvent(isActive: Boolean) = dispatcher.schedule {
        val connectionProperties = stores.getRtcConnectionMetadata()?.analyticsProperties.orEmpty()
        val voiceChannel = selectedVoiceChannel
        if (isActive) {
            val guildProperties = voiceChannel
                ?.let { getGuildAnalyticsPropertiesInternal(it.guildId) }
                .orEmpty()
            AnalyticsTracker.overlayVoiceOpened(
                guildProperties + connectionProperties + voiceChannel?.analyticsProperties.orEmpty(),
            )
        } else {
            AnalyticsTracker.overlayVoiceClosed(connectionProperties)
        }
    }

    // -- Voice and video -------------------------------------------------------------------------------------------

    @StoreThread
    fun handleUserSpeaking(speakingUsers: Set<Long>) {
        val mode = inputMode ?: return
        val channel = selectedVoiceChannel ?: return
        val meId = stores.getMeId()
        AnalyticsTracker.userSpeaking(meId, speakingUsers, mode, channel)
        AnalyticsTracker.userListening(meId, speakingUsers, mode, channel)
    }

    @StoreThread
    fun handleIsScreenSharingChanged(isScreenSharing: Boolean) =
        handleVideoInputUpdate(stores.getSelectedVideoInputDevice(), isScreenSharing)

    @StoreThread
    fun handleVideoInputDeviceSelected(videoInputDevice: VideoInputDeviceDescription?) =
        handleVideoInputUpdate(videoInputDevice, stores.isScreenSharing())

    @StoreThread
    private fun handleVideoInputUpdate(
        selectedVideoInputDevice: VideoInputDeviceDescription?,
        isScreenSharing: Boolean,
    ) {
        val channel = selectedVoiceChannel ?: return
        AnalyticsTracker.videoInputsUpdate(
            meId = stores.getMeId(),
            channel = channel,
            guildVoiceStates = stores.getVoiceStatesForGuild(channel.guildId),
            videoInputDevice = selectedVideoInputDevice,
            isScreenSharing = isScreenSharing,
            mediaSessionId = stores.getRtcConnectionMetadata()?.mediaSessionId,
        )
    }

    fun trackVideoLayoutToggled(videoLayout: String, meId: Long, channel: Channel?) =
        AnalyticsTracker.videoLayoutToggled(videoLayout, meId, channel)

    /**
     * A stream ended. The caller's properties are augmented with the peak viewer count, which only
     * the streaming store has.
     */
    @StoreThread
    fun trackVideoStreamEnded(properties: MutableMap<String, Any>) {
        val senderUserId = properties["sender_user_id"] as? Long ?: return
        val channelId = properties["channel_id"] as? Long ?: return
        val guildId = properties["guild_id"] as? Long
        stores.getMaxViewersForStream(senderUserId, channelId, guildId)?.let { properties["max_viewers"] = it }
        AnalyticsTracker.videoStreamEnded(properties)
    }

    fun trackMediaSessionJoined(properties: Map<String, Any>) = dispatcher.schedule {
        AnalyticsTracker.mediaSessionJoined(
            properties,
            stores.getChannel(stores.getLastSelectedVoiceChannelId()),
        )
    }

    fun trackVoiceConnectionFailure(properties: Map<String, Any>) = dispatcher.schedule {
        AnalyticsTracker.voiceConnectionFailure(
            properties,
            stores.getChannel(stores.getSelectedVoiceChannelId()),
            stores.getPreferredRtcRegion(),
        )
    }

    fun trackVoiceConnectionSuccess(properties: Map<String, Any>) = dispatcher.schedule {
        val channel = stores.getChannel(stores.getSelectedVoiceChannelId())
        val region = stores.getPreferredRtcRegion()
        stores.observeAudioInfo()
            .take(1)
            .appSubscribe(errorClass = StoreAnalytics::class.java) { audioInfo ->
                AnalyticsTracker.voiceConnectionSuccess(properties, audioInfo, channel, region)
            }
    }

    @StoreThread
    fun trackVoiceDisconnect(properties: Map<String, Any>) = dispatcher.schedule {
        AnalyticsTracker.voiceDisconnect(
            properties,
            stores.getVoiceConfiguration(),
            stores.getChannel(stores.getLastSelectedVoiceChannelId()),
            stores.getPreferredRtcRegion(),
        )
    }

    fun trackVoiceAudioOutputModeSelected(
        channelId: Long,
        fromAudioOutputMode: DiscordAudioManager.DeviceTypes,
        toAudioOutputMode: DiscordAudioManager.DeviceTypes,
    ) = dispatcher.schedule {
        val channel = stores.getChannel(channelId) ?: return@schedule
        val metadata = stores.getRtcConnectionMetadata()
        AnalyticsTracker.voiceAudioOutputModeSelected(
            stores.getMeId(),
            metadata?.rtcConnectionId,
            metadata?.mediaSessionId,
            channel,
            fromAudioOutputMode,
            toAudioOutputMode,
        )
    }

    fun trackShowCallFeedbackSheet(channelId: Long) = dispatcher.schedule {
        val channel = stores.getChannel(channelId) ?: return@schedule
        AnalyticsTracker.openCallFeedbackSheet(channel.guildId, channel.id, channel.type)
    }

    // -- Snapshots ---------------------------------------------------------------------------------------------------

    /** Guild + channel snapshot for whatever channel is currently selected. */
    private val snapshotAnalyticsProperties: Map<String, Any?>
        get() = getGuildAndChannelSnapshotAnalyticsProperties(stores.getSelectedChannelId())

    private fun getGuildAndChannelSnapshotAnalyticsProperties(channelId: Long): Map<String, Any?> {
        val channel = stores.getChannel(channelId) ?: return emptyMap()
        return getGuildSnapshotAnalyticsProperties(channel.guildId) +
            getChannelSnapshotAnalyticsProperties(channel)
    }

    private fun getChannelSnapshotAnalyticsProperties(
        channelId: Long,
        includeNsfw: Boolean = false,
    ): Map<String, Any?> = stores.getChannel(channelId)
        ?.let { getChannelSnapshotAnalyticsProperties(it, includeNsfw) }
        .orEmpty()

    /**
     * `channel_hidden` reports whether the `@everyone` role is denied `VIEW_CHANNEL`, which is how
     * a channel is made private. The overwrite for `@everyone` is the one whose id equals the guild
     * id.
     */
    private fun getChannelSnapshotAnalyticsProperties(
        channel: Channel,
        includeNsfw: Boolean = false,
    ): Map<String, Any?> {
        val everyoneOverwrite = channel.permissionOverwrites?.firstOrNull { it.id == channel.guildId }
        val properties = mutableMapOf<String, Any?>()
        properties.putAll(channel.analyticsProperties)
        properties["channel_member_perms"] = stores.getChannelPermissions(channel.id)
        properties["channel_hidden"] = everyoneOverwrite
            ?.let { (it.deny and Permission.VIEW_CHANNEL) == Permission.VIEW_CHANNEL }
        if (includeNsfw) properties["channel_is_nsfw"] = channel.nsfw
        return properties
    }

    private fun getGuildSnapshotAnalyticsProperties(guildId: Long): Map<String, Any> =
        stores.getGuild(guildId)?.let(::getGuildSnapshotAnalyticsProperties).orEmpty()

    private fun getGuildSnapshotAnalyticsProperties(guild: Guild): Map<String, Any> = guildPropertiesMap(
        guildId = guild.id,
        guildSize = stores.getApproximateMemberCount(guild.id),
        guildChannels = stores.getChannelsForGuild(guild.id),
        numGuildRoles = stores.getGuildRoleCount(guild.id),
        guildMemberNumRoles = stores.getGuildMemberRoleCount(guild.id, stores.getMeId()),
        guildPermissions = stores.getGuildPermissions(guild.id) ?: 0L,
        isVip = GuildFeature.VIP_REGIONS in guild.features,
    )

    /**
     * Same snapshot as [getGuildSnapshotAnalyticsProperties], read from the store-thread-internal
     * maps. Call sites already on the store thread use this so the snapshot matches the dispatch
     * that produced the event.
     */
    @StoreThread
    private fun getGuildAnalyticsPropertiesInternal(guildId: Long): Map<String, Any> =
        getGuildSnapshotAnalyticsProperties(guildId)

    private fun guildPropertiesMap(
        guildId: Long,
        guildSize: Int,
        guildChannels: Collection<Channel>,
        numGuildRoles: Int,
        guildMemberNumRoles: Int,
        guildPermissions: Long,
        isVip: Boolean,
    ): Map<String, Any> = mapOf(
        "guild_id" to guildId,
        "guild_size_total" to guildSize,
        "guild_num_channels" to guildChannels.size,
        "guild_num_text_channels" to guildChannels.count(Channel::isGuildTextyChannel),
        "guild_num_voice_channels" to guildChannels.count(Channel::isGuildVocalChannel),
        "guild_num_roles" to numGuildRoles,
        "guild_member_num_roles" to guildMemberNumRoles,
        "guild_member_perms" to guildPermissions,
        "guild_is_vip" to isVip,
    )

    /**
     * Threads report their own shape rather than a channel snapshot.
     *
     * The message count is capped at 50 because the reference caps it: beyond that the exact number
     * stops being a useful bucket and starts being an activity fingerprint for a small thread.
     */
    private fun getThreadSnapshotAnalyticsProperties(channel: Channel): Map<String, Any?> = mapOf(
        "channel_id" to channel.id,
        "guild_id" to channel.guildId,
        "parent_id" to channel.parentId,
        "channel_type" to channel.type,
        "thread_approximate_message_count" to
            (stores.getThreadMessageCount(channel.id) ?: 0).coerceAtMost(MAX_REPORTED_THREAD_MESSAGES),
        "thread_archived" to (channel.threadMetadata?.archived == true),
        "thread_locked" to (channel.threadMetadata?.locked == true),
        "thread_auto_archive_duration_minutes" to channel.threadMetadata?.autoArchiveDuration,
        "thread_approximate_creation_date" to SnowflakeUtils.toTimestamp(channel.id),
    )

    companion object {
        private const val CACHE_KEY_LOGOUT_TS = "CACHE_KEY_LOGOUT_TS"
        private const val MAX_REPORTED_THREAD_MESSAGES = 50
    }
}
