package com.discord.utilities.analytics

import com.discord.api.channel.Channel
import com.discord.api.activity.ActivityPlatform
import com.discord.api.thread.ThreadMemberFlags
import com.discord.api.voice.state.VoiceState
import com.discord.models.domain.ModelGift
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelSku
import com.discord.models.member.GuildMember
import com.discord.native.engine.VideoInputDeviceDescription
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.rtcconnection.mediaengine.MediaEngine
import com.discord.stores.StoreMediaSettings
import com.discord.utilities.collections.filterNonNullValues
import java.util.Locale

/**
 * Every analytics event the app raises, as a named method with typed arguments.
 *
 * Call sites never build a property map or spell an event name: they call the method that matches
 * what the user did. The method owns the event name, the property keys and the throttling window,
 * so a rename or a key change happens once.
 *
 * Events that need store state (a guild snapshot, the selected channel, the current voice channel)
 * are raised through [com.discord.stores.StoreAnalytics] instead, which reads that state on the
 * store thread and calls in here with it. `StoreAnalytics` is the only caller of the
 * snapshot-taking overloads below.
 *
 * **Nothing here transmits anything by default.** All of it lands in [AnalyticsUtils.Tracker],
 * whose [AnalyticsSink] is [AnalyticsSink.Disabled] unless something installs another one.
 *
 * Not reconstructed, and deliberately so:
 * - **Adjust and Firebase events.** The reference fires install-attribution events through the
 *   Adjust SDK using event tokens baked into the APK, and a handful of events (`handle_ext_share`,
 *   `unhandled_uri`) only through Firebase Analytics. Those identifiers belong to Discord and
 *   `PLAN.md` forbids reusing them; Phase 13 registers Manticord-owned configuration.
 * - Events whose parameter types have no target equivalent yet (`callReportProblem`,
 *   `failedMessageResolved`, `fileUploadAlertViewed`, `deepLinkReceived`, `quickSwitcherSelect`,
 *   the payment-flow family, the account-link family). Their `StoreAnalytics` entry points are
 *   listed in the handoff so they can be added with the types they need.
 */
object AnalyticsTracker {

    /** Events that fire on a repeating user action are throttled per key over these windows. */
    const val THROTTLE_SHORT = 60_000L
    const val THROTTLE_MEDIUM = 300_000L
    const val THROTTLE_LONG = 900_000L

    const val ATTACHMENT_SOURCE_KEYBOARD = "keyboard"
    const val ATTACHMENT_SOURCE_PICKER = "media_picker"
    const val ATTACHMENT_SOURCE_SHARE = "share"

    @JvmStatic
    val tracker: AnalyticsUtils.Tracker
        get() = AnalyticsUtils.Tracker.instance

    /**
     * Properties describing the currently selected channel.
     *
     * `StoreAnalytics.init` installs this. The reference reads `StoreStream` directly from
     * [addAttachment]; the target's store graph is private to `StoreStream`, so the one event that
     * needs the selected channel without going through `StoreAnalytics` gets it from here.
     */
    @Volatile
    @JvmStatic
    var selectedChannelProperties: () -> Map<String, Any> = { emptyMap() }

    /**
     * Properties describing what the user is currently playing.
     *
     * Voice events in the reference are enriched with the local presence's game activity. Installed
     * by `StoreAnalytics.init`; until then voice events carry no game properties.
     */
    @Volatile
    @JvmStatic
    var gameProperties: (meId: Long) -> Map<String, Any> = { emptyMap() }

    // -- Application lifecycle ----------------------------------------------------------------

    /** The unauthenticated landing screen was shown. */
    @JvmStatic
    fun appLandingViewed(lastLogoutTs: Long) {
        val properties = lastLogoutTs
            .takeIf { it != 0L }
            ?.let { mapOf("last_logout_ts" to it as Any) }
        tracker.track("app_landing_viewed", properties)
    }

    /** The previous process died in a crash. Raised once, on the next start. */
    fun appCrashed() {
        tracker.track("app_crashed")
    }

    /** A caught exception worth reporting. Only the stack trace is sent, never the message. */
    fun appExceptionThrown(stacktrace: String) {
        tracker.track("app_exception_thrown", mapOf("stacktrace" to stacktrace))
    }

    /** The very first successful login on this install. */
    fun appFirstLogin() {
        tracker.track("app_first_login", mapOf("platform" to "Android"))
    }

    /** The `READY` payload's shape, used to track payload size regressions. */
    @JvmStatic
    fun readyPayloadReceived(properties: Map<String, Any>) {
        tracker.track("ready_payload_received", properties)
    }

    /** A notification was tapped. */
    @JvmStatic
    fun appNotificationClicked(properties: Map<String, Any>) {
        if (properties.isNotEmpty()) tracker.track("notification_clicked", properties)
    }

    /** A notification was received but not shown. */
    fun appNotificationDropped(properties: Map<String, Any>) {
        tracker.track("notification_dropped", properties)
    }

    /**
     * The session fingerprint changed while one was already assigned.
     *
     * Both values identify an anonymous session rather than a person, and the backend needs them to
     * stitch the pre-login and post-login halves of a funnel together.
     */
    @JvmStatic
    fun externalFingerprintDropped(fingerprint: String, droppedFingerprint: String) {
        tracker.track(
            "external_fingerprint_dropped",
            mapOf("fingerprint" to fingerprint, "dropped_fingerprint" to droppedFingerprint),
        )
    }

    // -- Experiments -------------------------------------------------------------------------

    @JvmStatic
    fun guildExperimentTriggered(name: String, revision: Int, bucket: Int, guildId: Long) {
        tracker.track(
            "experiment_guild_triggered",
            mapOf("name" to name, "revision" to revision, "bucket" to bucket, "guild_id" to guildId),
        )
    }

    @JvmStatic
    fun userExperimentTriggered(experimentName: String, revision: Int, population: Int, bucket: Int) {
        tracker.track(
            "experiment_user_triggered",
            mapOf(
                "name" to experimentName,
                "revision" to revision,
                "population" to population,
                "bucket" to bucket,
            ),
        )
    }

    // -- Modals, popouts and settings panes ----------------------------------------------------

    @JvmStatic
    @JvmOverloads
    fun openModal(modalName: String, location: String, guildId: Long? = null) {
        val properties = mutableMapOf<String, Any>("location_section" to location, "type" to modalName)
        guildId?.let { properties["guild_id"] = it.toString() }
        tracker.track("open_modal", properties)
    }

    fun openModal(modalName: String, locationTrait: Traits.Location) {
        val properties = mutableMapOf<String, Any>()
        locationTrait.serializeTo(properties)
        properties["type"] = modalName
        tracker.track("open_modal", properties)
    }

    fun openModal(modalName: String, sourceTrait: Traits.Source) {
        val properties = mutableMapOf<String, Any>()
        sourceTrait.serializeTo(properties)
        properties["type"] = modalName
        tracker.track("open_modal", properties)
    }

    @JvmOverloads
    fun dismissModal(modalName: String, location: String, dismissType: String, guildId: Long? = null) {
        val properties = mutableMapOf<String, Any>(
            "location_section" to location,
            "type" to modalName,
            "dismiss_type" to dismissType,
        )
        guildId?.let { properties["guild_id"] = it.toString() }
        tracker.track("modal_dismissed", properties)
    }

    fun openPopout(popoutName: String, locationTrait: Traits.Location) {
        val properties = mutableMapOf<String, Any>()
        locationTrait.serializeTo(properties)
        properties["type"] = popoutName
        tracker.track("open_popout", properties)
    }

    fun openPopout(popoutName: String, location: String) {
        tracker.track("open_popout", mapOf("location_section" to location, "type" to popoutName))
    }

    fun openUserSheet(
        hasPremiumCustomization: Boolean,
        gameName: String?,
        gamePlatform: ActivityPlatform?,
        guildMember: GuildMember?,
    ) {
        val properties = mutableMapOf<String, Any>(
            "profile_has_nitro_customization" to hasPremiumCustomization,
            "type" to "User Sheet",
        )
        gameName?.let { properties["game_name"] = it }
        gamePlatform?.let { properties["game_platform"] = it.name.lowercase(Locale.ROOT) }
        guildMember?.let {
            properties["has_nickname"] = !it.nick.isNullOrEmpty()
            properties["has_guild_member_avatar"] = it.hasAvatar()
            properties["has_guild_member_banner"] = it.hasBanner()
            properties["has_guild_member_bio"] = !it.bio.isNullOrEmpty()
        }
        tracker.track("open_popout", properties)
    }

    /** A settings pane was opened. [settingsType] is `"user"` or `"guild"`. */
    @JvmOverloads
    fun settingsPaneViewed(settingsType: String, pane: String, extraProperties: Map<String, Any>? = null) {
        val properties = mutableMapOf<String, Any>("settings_type" to settingsType, "destination_pane" to pane)
        extraProperties?.let(properties::putAll)
        tracker.track("settings_pane_viewed", properties)
    }

    fun openGuildProfileSheet(guildId: Long) {
        tracker.track("open_popout", mapOf("guild_id" to guildId, "type" to "Guild Profile"))
    }

    fun openGuildScheduledEventSheet(guildId: Long, numEvents: Int) {
        tracker.track(
            "open_modal",
            mapOf("type" to "Guild Events Modal", "guild_id" to guildId, "guild_events_count" to numEvents),
        )
    }

    fun openUserStatusSheet(locationTrait: Traits.Location, hasCustomStatus: Boolean) {
        val properties = mutableMapOf<String, Any>()
        locationTrait.serializeTo(properties)
        properties["type"] = "User Status Menu"
        properties["has_custom_status"] = hasCustomStatus
        tracker.track("open_popout", properties)
    }

    fun openCallFeedbackSheet(guildId: Long, channelId: Long, channelType: Int) {
        tracker.track(
            "open_popout",
            mapOf(
                "guild_id" to guildId,
                "channel_id" to channelId,
                "channel_type" to channelType,
                "type" to "Call Session Feedback",
            ),
        )
    }

    fun viewedDisableCommunicationModal(guildId: Long, targetUserId: Long) {
        tracker.track(
            "open_modal",
            mapOf(
                "type" to "Set Communication Disabled Modal",
                "guild_id" to guildId,
                "other_user_id" to targetUserId,
            ),
        )
    }

    fun viewedEnableCommunicationModal(guildId: Long, targetUserId: Long) {
        tracker.track(
            "open_modal",
            mapOf(
                "type" to "Clear Communication Disabled Modal",
                "guild_id" to guildId,
                "other_user_id" to targetUserId,
            ),
        )
    }

    fun reviewRequestTriggered() = openModal("App Store Ratings Prompt", "auto")

    fun reviewRequestAccepted() =
        dismissModal("App Store Ratings Prompt", "auto", "accepted")

    fun reviewRequestDismissed() =
        dismissModal("App Store Ratings Prompt", "auto", "dismissed")

    /** The gift-acceptance modal. */
    @JvmStatic
    fun openGiftModal(gift: ModelGift, channel: Channel?, location: String) {
        tracker.track(
            "open_modal",
            mapOf<String, Any>("location" to location, "type" to "gift") +
                channel?.analyticsProperties.orEmpty() +
                skuProperties(gift.storeListing?.sku),
        )
    }

    // -- Tutorials ------------------------------------------------------------------------------

    fun showFirstServerTipTutorial() = showTutorial(FIRST_SERVER_TIP)

    fun closeFirstServerTipTutorial(acknowledged: Boolean) = closeTutorial(FIRST_SERVER_TIP, acknowledged)

    private fun showTutorial(tutorial: String) {
        tracker.track("show_tutorial", mapOf("tutorial" to tutorial))
    }

    private fun closeTutorial(tutorial: String, acknowledged: Boolean) {
        tracker.track("close_tutorial", mapOf("tutorial" to tutorial, "acknowledged" to acknowledged))
    }

    // -- Local settings -------------------------------------------------------------------------

    fun updateNotifications(enabled: Boolean) {
        tracker.track("local_settings_updated", mapOf("notifications_enabled" to enabled))
    }

    fun updateNotificationsInApp(enabled: Boolean) {
        tracker.track("local_settings_updated", mapOf("notifications_in_app_enabled" to enabled))
    }

    @JvmStatic
    fun overlayToggled(isEnabled: Boolean) {
        tracker.track("mobile_overlay_toggled", mapOf("enabled" to isEnabled))
    }

    fun permissionsRequested(permissionType: String) {
        tracker.track("permissions_requested", mapOf("type" to permissionType))
    }

    fun permissionsAcked(permissionType: String, granted: Boolean) {
        tracker.track(
            "permissions_acked",
            mapOf("type" to permissionType, "action" to if (granted) "granted" else "denied"),
        )
    }

    // -- Authentication and onboarding ----------------------------------------------------------

    fun nameSubmitted(numWords: Int, numChars: Int) {
        tracker.track("name_submitted", mapOf("num_words" to numWords, "num_chars" to numChars))
    }

    fun captchaFailed(reason: String) {
        tracker.track("captcha_failed", mapOf("reason" to reason))
    }

    fun oauth2AuthorizedViewed(applicationId: Long) {
        tracker.track("oauth2_authorize_viewed", mapOf("application_id" to applicationId))
    }

    fun smartLockAutoLogin() = tracker.track("smart_lock_auto_login")

    fun smartLockLogin() = tracker.track("smart_lock_login")

    fun smartLockLoginCredentialsFailed() = tracker.track("smart_lock_login_credentials_failed")

    // -- Invites, gifts and templates -----------------------------------------------------------

    @JvmStatic
    fun inviteOpened(inviteCode: String) {
        tracker.track("invite_opened", mapOf("invite_code" to inviteCode))
    }

    @JvmStatic
    @JvmOverloads
    fun inviteResolveFailed(
        inviteCode: String,
        location: String,
        errorMessage: String? = null,
        errorCode: Int? = null,
    ) {
        tracker.track(
            "resolve_invite",
            mapOf<String, Any?>(
                "invite_code" to inviteCode,
                "location" to location,
                "resolved" to false,
                "error_message" to errorMessage,
                "error_code" to errorCode,
            ).filterNonNullValues(),
        )
    }

    @JvmStatic
    fun guildTemplateResolveFailed(guildTemplateCode: String) {
        tracker.track(
            "guild_template_resolved",
            mapOf("resolved" to false, "guild_template_code" to guildTemplateCode),
        )
    }

    fun giftResolvedFailed(giftCode: String) {
        tracker.track("gift_code_resolved", mapOf("gift_code" to giftCode, "resolved" to false))
    }

    @JvmOverloads
    fun guildBoostPromotionOpened(
        guildId: Long,
        locationTrait: Traits.Location,
        channelId: Long? = null,
    ) {
        val properties = mutableMapOf<String, Any>("guild_id" to guildId)
        channelId?.let { properties["channel_id"] = it }
        locationTrait.serializeTo(properties)
        tracker.track("premium_guild_promotion_opened", properties)
    }

    /** [snapshotProperties] and the invite fields both come from `StoreAnalytics`. */
    fun inviteSent(snapshotProperties: Map<String, Any?>) {
        tracker.track("invite_sent", snapshotProperties.filterNonNullValues())
    }

    /**
     * The invite-suggestion sheet was opened.
     *
     * The breakdown counts come from `StoreAnalytics`, which resolves the suggested users against
     * the relationship and private-channel stores.
     */
    fun inviteSuggestionOpened(
        guildId: Long,
        numSuggestions: Int,
        numFriends: Int,
        numDms: Int,
        numGroupDms: Int,
    ) {
        tracker.track(
            "invite_suggestion_opened",
            mapOf(
                "num_suggestions" to numSuggestions,
                "num_friends" to numFriends,
                "num_dms" to numDms,
                "num_group_dms" to numGroupDms,
                "guild_id" to guildId,
            ),
        )
    }

    // -- Friends ---------------------------------------------------------------------------------

    fun friendsListViewed() = tracker.track("friends_list_viewed")

    fun friendAddViewed(type: String) {
        tracker.track("friend_add_viewed", mapOf("friend_add_type" to type))
    }

    /**
     * A friend request could not be sent.
     *
     * Only the *length* of what the user typed is reported. The reference sends the raw query and
     * the target username; that is another user's identity typed into a text field, and this
     * project does not transmit it.
     */
    @JvmOverloads
    fun friendRequestFailed(query: CharSequence, reason: String? = null) {
        tracker.track(
            "friend_request_failed",
            mapOf<String, Any?>("query_length" to query.length, "reason" to reason).filterNonNullValues(),
        )
    }

    // -- Chat input, autocomplete and expressions ------------------------------------------------

    /** An attachment was added. The channel context comes from [selectedChannelProperties]. */
    @JvmStatic
    fun addAttachment(source: String, mimeType: String, totalAttachments: Int) {
        tracker.track(
            "message_attachment_updated",
            mapOf<String, Any>(
                "source" to source,
                "action_type" to ATTACHMENT_ADD,
                "mime_type" to mimeType,
                "total_attachments" to totalAttachments,
            ) + selectedChannelProperties(),
        )
    }

    /** One of [ChatInputComponentTypes]. */
    fun chatInputComponentViewed(type: String) {
        tracker.track("chat_input_component_viewed", mapOf("type" to type))
    }

    fun autocompleteOpen(
        snapshotProperties: Map<String, Any>,
        autocompleteType: String,
        numEmojiResults: Int,
        numStickerResults: Int,
    ) {
        tracker.track(
            "channel_autocomplete_open",
            snapshotProperties + mapOf(
                "autocomplete_type" to autocompleteType,
                "num_emoji_results" to numEmojiResults,
                "num_sticker_results" to numStickerResults,
            ),
        )
    }

    fun autocompleteSelect(
        snapshotProperties: Map<String, Any>,
        autocompleteType: String,
        numEmojiResults: Int,
        numStickerResults: Int,
        selectionType: String?,
        selection: String?,
        stickerId: Long?,
    ) {
        tracker.track(
            "channel_autocomplete_selected",
            snapshotProperties + mapOf<String, Any?>(
                "autocomplete_type" to autocompleteType,
                "num_emoji_results" to numEmojiResults,
                "num_sticker_results" to numStickerResults,
                "selection_type" to selectionType,
                "selection" to selection,
                "sticker_id" to stickerId,
            ).filterNonNullValues(),
        )
    }

    fun expressionPickerOpened(tab: String, badged: Boolean) {
        tracker.track("expression_picker_opened", mapOf("badged" to badged, "tab" to tab))
    }

    fun expressionPickerTabClicked(tab: String, badged: Boolean) {
        tracker.track("expression_picker_tab_clicked", mapOf("badged" to badged, "tab" to tab))
    }

    fun expressionPickerStickerShopViewed(badged: Boolean) {
        tracker.track("expression_picker_sticker_shop_viewed", mapOf("badged" to badged))
    }

    fun expressionSuggestionsDisplayed(suggestionTrigger: String) {
        tracker.track("auto_suggest_displayed", mapOf("suggestion_trigger" to suggestionTrigger))
    }

    fun expressionSuggestionsSelected(stickerId: Long, suggestionTrigger: String) {
        tracker.track(
            "auto_suggest_expression_selected",
            mapOf("sticker_id" to stickerId, "suggestion_trigger" to suggestionTrigger),
        )
    }

    fun stickerSuggestionsEnabledToggled(enabled: Boolean, location: Traits.Location?) {
        val properties = mutableMapOf<String, Any>("enabled" to enabled)
        location?.serializeTo(properties)
        tracker.track("sticker_suggestions_enabled_toggled", properties)
    }

    // -- Emoji popouts and premium upsells --------------------------------------------------------

    /**
     * The custom-emoji popout. Its `type` encodes whether it doubled as a Nitro upsell, which is
     * how the reference distinguishes a plain popout from an upsell impression.
     */
    fun openCustomEmojiPopout(
        guildId: Long,
        emojiId: Long,
        isPremium: Boolean,
        joinedSourceGuild: Boolean,
        sourceGuildPrivate: Boolean,
        channelProperties: Map<String, Any>,
    ) {
        val isUpsell = !isPremium && joinedSourceGuild
        val isSoftUpsell = !isPremium && !joinedSourceGuild && sourceGuildPrivate
        val suffix = when {
            isUpsell -> " (Upsell)"
            isSoftUpsell -> " (Soft Upsell)"
            else -> ""
        }
        tracker.track(
            "open_popout",
            channelProperties + mapOf(
                "type" to "Custom Emoji Popout$suffix",
                "guild_id" to guildId,
                "emoji_id" to emojiId,
            ),
        )
    }

    fun openUnicodeEmojiPopout(guildId: Long, channelProperties: Map<String, Any>) {
        tracker.track(
            "open_popout",
            channelProperties + mapOf("type" to "Standard Emoji Popout", "guild_id" to guildId),
        )
    }

    @JvmOverloads
    fun premiumUpsellViewed(
        type: PremiumUpsellType,
        locationTrait: Traits.Location? = null,
        properties: Map<String, Any> = emptyMap(),
        sourceTrait: Traits.Source? = null,
    ) {
        val merged = properties.toMutableMap()
        merged["type"] = type.analyticsName
        locationTrait?.serializeTo(merged)
        sourceTrait?.serializeTo(merged)
        tracker.track("premium_upsell_viewed", merged)
    }

    fun premiumSettingsOpened(locationTrait: Traits.Location) {
        val properties = mutableMapOf<String, Any>()
        locationTrait.serializeTo(properties)
        tracker.track("premium_promotion_opened", properties)
    }

    fun upgradePremiumYearlyClosed(locationTrait: Traits.Location) {
        val properties = mutableMapOf<String, Any>()
        locationTrait.serializeTo(properties)
        tracker.track("upgrade_premium_yearly_closed", properties)
    }

    /** The upsell surface that raised a `premium_upsell_viewed`. */
    enum class PremiumUpsellType(val analyticsName: String) {
        EmojiPickerHeaderViewed("emoji_picker_search"),
        EmojiPickerLockedItemClicked("emoji_picker_emoji_clicked"),
        StreamQualityIndicator("stream_quality_indicator"),
        EmojiAutocompleteInline("emoji_autocomplete_inline"),
        EmojiAutocompleteModal("emoji_autocomplete_modal"),
        CustomProfileUpsellModal("custom profiles upsell modal"),
        CustomProfileBannerUpsell("custom profiles settings banner upsell"),
        StickerPickerUpsell("sticker_picker_upsell"),
        EmptyStickerPickerUpsell("empty_sticker_picker_upsell"),
        PerGuildIdentityUpsellModal("per server identity upsell modal"),
        PerGuildIdentityInline("per server identity upsell inline"),
    }

    // -- Search -----------------------------------------------------------------------------------

    /**
     * Search events are throttled over a one-second window rather than the usual fifteen minutes:
     * they fire on every keystroke, but a burst of them is one search.
     */
    private const val SEARCH_THROTTLE_MS = 1_000L

    fun searchStart(
        searchType: SearchType,
        locationTrait: Traits.Location?,
        properties: Map<String, Any?>,
        throttle: Boolean = false,
    ) = trackSearchEvent("search_started", throttle) {
        searchProperties(searchType, locationTrait, properties)
    }

    fun searchResultsEmpty(
        searchType: SearchType,
        locationTrait: Traits.Location?,
        properties: Map<String, Any?>,
        throttle: Boolean,
    ) = trackSearchEvent("search_result_empty", throttle) {
        searchProperties(searchType, locationTrait, properties)
    }

    fun searchResultViewed(
        searchType: SearchType,
        totalResultsCount: Int,
        lockedResultsCount: Int?,
        locationTrait: Traits.Location?,
        properties: Map<String, Any?>,
        throttle: Boolean,
    ) = trackSearchEvent("search_result_viewed", throttle) {
        val merged = mutableMapOf<String, Any>("search_type" to searchType.name, "total_results" to totalResultsCount)
        lockedResultsCount?.let { merged["num_results_locked"] = it }
        locationTrait?.serializeTo(merged)
        merged + properties.filterNonNullValues()
    }

    fun searchResultSelected(
        searchType: SearchType,
        locationTrait: Traits.Location?,
        sourceTrait: Traits.Source?,
        totalResultsCount: Int,
        properties: Map<String, Any?>,
    ) {
        val merged = mutableMapOf<String, Any>("search_type" to searchType.name, "total_results" to totalResultsCount)
        locationTrait?.serializeTo(merged)
        sourceTrait?.serializeTo(merged)
        tracker.track("search_result_selected", merged + properties.filterNonNullValues())
    }

    private fun searchProperties(
        searchType: SearchType,
        locationTrait: Traits.Location?,
        properties: Map<String, Any?>,
    ): Map<String, Any> {
        val merged = mutableMapOf<String, Any>("search_type" to searchType.name)
        locationTrait?.serializeTo(merged)
        return merged + properties.filterNonNullValues()
    }

    private fun trackSearchEvent(event: String, throttle: Boolean, properties: () -> Map<String, Any>) {
        if (throttle) {
            tracker.track(event to null, SEARCH_THROTTLE_MS, properties)
        } else {
            tracker.track(event, properties())
        }
    }

    // -- Channels, guilds and threads ---------------------------------------------------------------

    /**
     * A channel was opened. Throttled per channel: switching back and forth between two channels
     * should not report a view every time.
     */
    fun channelOpened(channelId: Long, lazyPropertyProvider: () -> Map<String, Any?>) {
        tracker.track("channel_opened" to channelId, THROTTLE_LONG) {
            lazyPropertyProvider().filterNonNullValues()
        }
    }

    /** A guild was viewed. Throttled per guild, like [channelOpened]. */
    fun guildViewed(guildId: Long, lazyPropertyProvider: () -> Map<String, Any>) {
        tracker.track("guild_viewed" to guildId, THROTTLE_LONG, lazyPropertyProvider)
    }

    /** The text channel attached to a voice channel was opened. */
    fun textInVoiceOpened(channelId: Long, lazyPropertyProvider: () -> Map<String, Any?>) {
        tracker.track("text_in_voice_opened" to channelId, THROTTLE_LONG) {
            lazyPropertyProvider().filterNonNullValues()
        }
    }

    /** Messages were acknowledged. Throttled per guild. */
    fun ackMessage(channel: Channel, propertyProvider: () -> Map<String, Any?>) {
        tracker.track("ack_messages" to channel.guildId, THROTTLE_LONG) {
            propertyProvider().filterNonNullValues()
        }
    }

    fun threadBrowserTabChanged(snapshotProperties: Map<String, Any?>, type: String) {
        tracker.track(
            "thread_browser_tab_changed",
            (snapshotProperties + ("type" to type)).filterNonNullValues(),
        )
    }

    fun threadCreationStarted(channelId: Long, guildId: Long, location: String?) {
        tracker.track(
            "thread_creation_started",
            mapOf<String, Any?>(
                "channel_id" to channelId,
                "guild_id" to guildId,
                "location" to location,
            ).filterNonNullValues(),
        )
    }

    // -- Application commands -----------------------------------------------------------------------

    fun applicationCommandBrowserOpened(snapshotProperties: Map<String, Any?>) {
        tracker.track("application_command_browser_opened", snapshotProperties.filterNonNullValues())
    }

    fun applicationCommandBrowserScrolled() = tracker.track("application_command_browser_scrolled")

    fun applicationCommandBrowserJump(targetApplicationId: Long) {
        tracker.track("application_command_browser_jumped", mapOf("target_application_id" to targetApplicationId))
    }

    fun applicationCommandSelected(applicationId: Long, commandId: Long, snapshotProperties: Map<String, Any?>) {
        tracker.track(
            "application_command_selected",
            snapshotProperties.filterNonNullValues() +
                mapOf("application_id" to applicationId, "command_id" to commandId),
        )
    }

    fun applicationCommandValidationFailure(
        applicationId: Long,
        commandId: Long,
        argumentType: String,
        isRequired: Boolean,
    ) {
        tracker.track(
            "application_command_validation_failed",
            mapOf(
                "application_id" to applicationId,
                "command_id" to commandId,
                "argument_type" to argumentType,
                "is_required" to isRequired,
            ),
        )
    }

    // -- Notification settings ------------------------------------------------------------------------

    /**
     * Guild or channel notification settings changed.
     *
     * `update_type` is `"channel"` whenever the guild carries per-channel overrides. The reference
     * tests the overrides list for null, which its DTO allows and the target's does not, so an
     * empty list takes the `"guild"` branch here.
     */
    fun notificationSettingsUpdated(userGuildSettings: ModelNotificationSettings, channel: Channel?) {
        val override = channel?.let { userGuildSettings.getChannelOverride(it.id) }
        val properties = mutableMapOf<String, Any?>(
            "update_type" to if (userGuildSettings.channelOverrides.isEmpty()) "guild" else "channel",
            "guild_suppress_everyone" to userGuildSettings.isSuppressEveryone,
            "guild_suppress_roles" to userGuildSettings.isSuppressRoles,
            "guild_is_muted" to userGuildSettings.isMuted,
            "guild_muted_until" to userGuildSettings.muteConfig?.endTimeMs,
            "guild_receive_mobile_push" to userGuildSettings.isMobilePush,
            "guild_message_notification_settings" to
                notificationFrequencyValue(userGuildSettings.messageNotifications),
            "parent_id" to channel?.parentId,
            "channel_is_muted" to override?.isMuted,
            "channel_muted_until" to override?.muteConfig?.endTimeMs,
            "channel_message_notification_settings" to
                notificationFrequencyValue(override?.messageNotifications),
        )
        if (channel != null && channel.guildId != 0L && channel.guildId != -1L) {
            properties["channel_is_overridden"] = override?.isMuted == true || override?.messageNotifications != null
        }
        tracker.track("notification_settings_updated", properties.filterNonNullValues())
    }

    /** A thread's per-member notification flags changed. */
    fun threadNotificationSettingsUpdated(
        threadProperties: Map<String, Any?>,
        hasInteractedWith: Boolean,
        parentIsMuted: Boolean,
        parentNotificationSetting: Int,
        oldFlags: Int,
        flags: Int,
    ) {
        tracker.track(
            "notification_settings_updated",
            (
                threadProperties + mapOf<String, Any?>(
                    "has_interacted_with_thread" to hasInteractedWith,
                    "parent_is_muted" to parentIsMuted,
                    "old_thread_notification_setting" to threadNotificationFrequencyValue(oldFlags),
                    "new_thread_notification_setting" to threadNotificationFrequencyValue(flags),
                    "parent_notification_setting" to notificationFrequencyValue(parentNotificationSetting),
                )
                ).filterNonNullValues(),
        )
    }

    private fun notificationFrequencyValue(messageNotifications: Int?): String? = when (messageNotifications) {
        ModelNotificationSettings.FREQUENCY_ALL -> "All"
        ModelNotificationSettings.FREQUENCY_MENTIONS -> "Mentions"
        ModelNotificationSettings.FREQUENCY_NOTHING -> "Nothing"
        ModelNotificationSettings.FREQUENCY_UNSET -> "Unset"
        else -> null
    }

    private fun threadNotificationFrequencyValue(flags: Int): String = when {
        flags == (flags and ThreadMemberFlags.ALL_MESSAGES) -> "All"
        flags == (flags and ThreadMemberFlags.ONLY_MENTIONS) -> "Mentions"
        flags == (flags and ThreadMemberFlags.NO_MESSAGES) -> "Nothing"
        else -> "Unset"
    }

    // -- Overlay ---------------------------------------------------------------------------------------

    fun overlayVoiceOpened(properties: Map<String, Any>) {
        tracker.track("mobile_overlay_opened", mapOf("type" to "voice") + properties)
    }

    fun overlayVoiceClosed(properties: Map<String, Any>) {
        tracker.track("mobile_overlay_closed", mapOf("type" to "voice") + properties)
    }

    // -- Voice and video ---------------------------------------------------------------------------------

    fun voiceChannelJoin(
        meId: Long,
        rtcConnectionId: String,
        channel: Channel,
        guildVoiceStates: Map<Long, VoiceState>,
        videoInputDevice: VideoInputDeviceDescription?,
        networkType: Int?,
        stageInstanceId: Long?,
        guildScheduledEventId: Long?,
    ) {
        val properties = voiceChannelProperties(
            meId, channel,
            guildVoiceStates = guildVoiceStates,
            videoInputDevice = videoInputDevice,
            rtcConnectionId = rtcConnectionId,
        )
        properties["connection_type"] = networkTypeValue(networkType)
        properties["stage_instance_id"] = stageInstanceId
        properties["guild_scheduled_event_id"] = guildScheduledEventId
        properties.putAll(gameProperties(meId))
        tracker.track("join_voice_channel", properties.filterNonNullValues())
    }

    fun voiceChannelLeave(
        meId: Long,
        rtcConnectionId: String,
        channel: Channel,
        guildVoiceStates: Map<Long, VoiceState>,
        mediaSessionId: String?,
        voiceProps: Map<String, Any>,
        durationMs: Long?,
        stageInstanceId: Long?,
        guildScheduledEventId: Long?,
    ) {
        val properties = voiceChannelProperties(
            meId, channel,
            durationMs = durationMs,
            guildVoiceStates = guildVoiceStates,
            rtcConnectionId = rtcConnectionId,
        )
        properties.putAll(voiceProps)
        properties["media_session_id"] = mediaSessionId
        properties["stage_instance_id"] = stageInstanceId
        properties["guild_scheduled_event_id"] = guildScheduledEventId
        properties.putAll(gameProperties(meId))
        tracker.track("leave_voice_channel", properties.filterNonNullValues())
    }

    fun voiceConnectionFailure(properties: Map<String, Any>, channel: Channel?, cloudflareBestRegion: String?) {
        val merged = properties.toMutableMap()
        addVoiceConnectionProperties(merged, channel, cloudflareBestRegion)
        tracker.track("voice_connection_failure", merged)
    }

    fun voiceConnectionSuccess(
        properties: Map<String, Any>,
        audioInfo: MediaEngine.AudioInfo?,
        channel: Channel?,
        cloudflareBestRegion: String?,
    ) {
        val merged = properties.toMutableMap()
        addVoiceConnectionProperties(merged, channel, cloudflareBestRegion)
        audioInfo?.let {
            merged["audio_subsystem"] = it.audioSubsystem
            merged["audio_layer"] = it.audioLayer
        }
        tracker.track("voice_connection_success", merged)
    }

    fun voiceDisconnect(
        properties: Map<String, Any>,
        voiceConfig: StoreMediaSettings.VoiceConfiguration?,
        channel: Channel?,
        cloudflareBestRegion: String?,
    ) {
        val merged = properties.toMutableMap()
        addVoiceConnectionProperties(merged, channel, cloudflareBestRegion)
        if (channel != null) {
            merged["channel_type"] = channel.type
            merged["channel_bitrate"] = channel.bitrate
        }
        tracker.track("voice_disconnect", merged.filterNonNullValues() + voiceConfigProperties(voiceConfig))
    }

    fun voiceAudioOutputModeSelected(
        meId: Long,
        rtcConnectionId: String?,
        mediaSessionId: String?,
        channel: Channel,
        fromAudioOutputMode: DiscordAudioManager.DeviceTypes,
        toAudioOutputMode: DiscordAudioManager.DeviceTypes,
    ) {
        tracker.track(
            "voice_audio_output_mode_selected",
            mapOf<String, Any?>(
                "channel_id" to channel.id,
                "from_audio_output_mode" to audioOutputDeviceTypeValue(fromAudioOutputMode),
                "to_audio_output_mode" to audioOutputDeviceTypeValue(toAudioOutputMode),
                "rtc_connection_id" to rtcConnectionId,
                "media_session_id" to mediaSessionId,
            ).filterNonNullValues(),
        )
    }

    fun mediaSessionJoined(properties: Map<String, Any>, channel: Channel?) {
        val merged = properties.toMutableMap()
        if (channel != null) {
            merged["channel_id"] = channel.id
            merged["channel_type"] = channel.type
            channel.guildId.takeIf { it > 0L }?.let { merged["guild_id"] = it }
        }
        tracker.track("media_session_joined", merged)
    }

    fun videoLayoutToggled(videoLayout: String, meId: Long, channel: Channel?) {
        if (channel == null) return
        val properties = voiceChannelProperties(meId, channel)
        properties["video_layout"] = videoLayout
        tracker.track("video_layout_toggled", properties.filterNonNullValues())
    }

    fun videoStreamEnded(properties: Map<String, Any>) {
        tracker.track("video_stream_ended", properties)
    }

    /** The set of enabled video inputs changed (camera on/off, screen share started/stopped). */
    fun videoInputsUpdate(
        meId: Long,
        channel: Channel,
        guildVoiceStates: Map<Long, VoiceState>,
        videoInputDevice: VideoInputDeviceDescription?,
        isScreenSharing: Boolean,
        mediaSessionId: String?,
    ) {
        val properties = voiceChannelProperties(
            meId, channel,
            guildVoiceStates = guildVoiceStates,
            videoInputDevice = videoInputDevice,
        )
        properties["enabled_inputs"] = listOfNotNull(
            "screen".takeIf { isScreenSharing },
            "camera".takeIf { videoInputDevice != null },
        )
        properties["media_session_id"] = mediaSessionId
        properties.putAll(gameProperties(meId))
        tracker.track("video_input_toggled", properties.filterNonNullValues())
    }

    /** Throttled per channel; speaking state changes many times a second. */
    fun userSpeaking(meId: Long, speakingUsers: Set<Long>, inputMode: String, channel: Channel) {
        if (meId !in speakingUsers) return
        trackUserVoiceEvent(channel, meId, inputMode, "user_speaking" to channel.id)
    }

    /** Throttled per channel, like [userSpeaking]. */
    fun userListening(meId: Long, speakingUsers: Set<Long>, inputMode: String, channel: Channel) {
        if (speakingUsers.isEmpty() || speakingUsers == setOf(meId)) return
        trackUserVoiceEvent(channel, meId, inputMode, "user_listening" to channel.id)
    }

    private fun trackUserVoiceEvent(
        channel: Channel,
        meId: Long,
        inputMode: String,
        throttleKey: Pair<String, Long?>,
    ) {
        tracker.track(throttleKey, THROTTLE_LONG) {
            gameProperties(meId) + mapOf(
                "mode" to inputMode,
                "channel" to channel.id,
                "channel_type" to channel.type,
                "server" to channel.guildId,
            )
        }
    }

    private fun voiceChannelProperties(
        meId: Long,
        channel: Channel,
        durationMs: Long? = null,
        guildVoiceStates: Map<Long, VoiceState>? = null,
        videoInputDevice: VideoInputDeviceDescription? = null,
        rtcConnectionId: String? = null,
    ): MutableMap<String, Any?> {
        val othersInChannel = guildVoiceStates
            ?.values
            ?.filter { it.channelId == channel.id && it.userId != meId }
            .orEmpty()
        val properties = mutableMapOf<String, Any?>(
            "channel_id" to channel.id,
            "channel_type" to channel.type,
            "voice_state_count" to othersInChannel.size,
            "video_stream_count" to othersInChannel.count(VoiceState::selfVideo),
        )
        if (channel.guildId > 0L) properties["guild_id"] = channel.guildId
        properties["video_enabled"] = videoInputDevice != null
        durationMs?.let { properties["duration"] = it }
        rtcConnectionId?.let { properties["rtc_connection_id"] = it }
        return properties
    }

    private fun addVoiceConnectionProperties(
        properties: MutableMap<String, Any>,
        channel: Channel?,
        cloudflareBestRegion: String?,
    ) {
        channel?.let { properties["channel_id"] = it.id }
        channel?.guildId?.takeIf { it > 0L }?.let { properties["guild_id"] = it }
        cloudflareBestRegion?.let { properties["cloudflare_best_region"] = it }
    }

    private fun voiceConfigProperties(config: StoreMediaSettings.VoiceConfiguration?): Map<String, Any> {
        if (config == null) return emptyMap()
        return mapOf(
            "audio_input_mode" to config.inputMode,
            "automatic_audio_input_sensitivity_enabled" to config.automaticVad,
            "audio_input_sensitivity" to config.sensitivity,
            "echo_cancellation_enabled" to config.echoCancellation,
            "noise_suppression_enabled" to
                (config.noiseProcessing == StoreMediaSettings.NoiseProcessing.Suppression),
            "noise_cancellation_enabled" to
                (config.noiseProcessing == StoreMediaSettings.NoiseProcessing.Cancellation),
            "automatic_gain_control_enabled" to config.automaticGainControl,
            "voice_output_volume" to config.outputVolume,
        )
    }

    fun guildAutoModFeedback(
        decisionId: String,
        messageId: Long,
        feedbackType: String,
        content: String,
    ) = tracker.track(
        "guild_automod_feedback",
        mapOf(
            "decision_id" to decisionId,
            "message_id" to messageId,
            "feedback_type" to feedbackType,
            "content" to content,
        ),
    )

    fun guildAutoModIncidentAction(guildId: Long, messageId: Long, action: String) = tracker.track(
        "guild_automod_incident_action",
        mapOf("guild_id" to guildId, "message_id" to messageId, "action" to action),
    )

    private fun audioOutputDeviceTypeValue(type: DiscordAudioManager.DeviceTypes): String? = when (type) {
        DiscordAudioManager.DeviceTypes.DEFAULT -> "default"
        DiscordAudioManager.DeviceTypes.INVALID -> null
        DiscordAudioManager.DeviceTypes.SPEAKERPHONE -> "speaker"
        DiscordAudioManager.DeviceTypes.WIRED_HEADSET -> "wired"
        DiscordAudioManager.DeviceTypes.EARPIECE -> "earpiece"
        DiscordAudioManager.DeviceTypes.BLUETOOTH_HEADSET -> "bluetooth"
    }

    private fun networkTypeValue(networkType: Int?): String? = when (networkType) {
        NETWORK_TYPE_WIFI -> "wifi"
        NETWORK_TYPE_CELLULAR -> "cellular"
        NETWORK_TYPE_ETHERNET -> "ethernet"
        else -> null
    }

    private fun skuProperties(sku: ModelSku?): Map<String, Any> {
        if (sku == null) return emptyMap()
        val properties = mutableMapOf<String, Any>(
            "sku_id" to sku.id,
            "sku_type" to sku.type,
            "application_id" to sku.applicationId,
            "store_title" to sku.name,
            "distribution_type" to if (sku.premium) "premium" else "distribution",
        )
        sku.application?.name?.let { properties["application_name"] = it }
        return properties
    }

    /** `action_type` on `message_attachment_updated` reports adds and removes; this is the add. */
    private const val ATTACHMENT_ADD = 0

    private const val FIRST_SERVER_TIP = "create-first-server-tip"

    private const val NETWORK_TYPE_WIFI = 1
    private const val NETWORK_TYPE_CELLULAR = 2
    private const val NETWORK_TYPE_ETHERNET = 3
}
