package com.discord.stores

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import android.util.Log
import com.discord.utilities.user.NicknameUtils
import com.discord.BuildConfig
import com.discord.api.activity.ActivityEmoji
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isThread
import com.discord.api.guild.Guild
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.friendsuggestions.FriendSuggestion
import com.discord.api.guildmember.GuildMember
import com.discord.api.guildmember.GuildMemberRemove
import com.discord.api.guildmember.GuildMembersChunk
import com.discord.api.message.Message as ApiMessage
import com.discord.api.presence.Presence
import com.discord.api.role.GuildRoleCreate
import com.discord.api.role.GuildRoleDelete
import com.discord.api.role.GuildRoleUpdate
import com.discord.api.stageinstance.StageInstance
import com.discord.api.thread.ThreadListing
import com.discord.api.user.User
import com.discord.api.user.TypingUser
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.models.authentication.AuthState
import com.discord.models.domain.ModelChannelUnreadUpdate
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.models.domain.ModelSession
import com.discord.models.domain.ModelGuildFolder
import com.discord.models.domain.ModelGuildMemberListUpdate
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.domain.ModelMuteConfig
import com.discord.models.domain.ModelUserNote
import com.discord.gateway.GatewayCancellable
import com.discord.gateway.GatewayDiscovery
import com.discord.gateway.GatewaySocket
import com.discord.gateway.RealGatewayScheduler
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.domain.ModelUserSettings
import com.discord.models.domain.auth.ModelLoginResult
import com.discord.restapi.RequiredHeadersInterceptor
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.restapi.cookie.CookieJars
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.utilities.Batched
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.streams.StreamContextService
import com.discord.utilities.media.AppSound
import com.discord.utilities.media.AppSoundManager
import com.discord.utilities.networking.NetworkMonitor
import com.discord.rtcconnection.VoiceTransport
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.billing.BillingUtils
import com.discord.utilities.logging.BootLog
import com.discord.utilities.logging.BootLogEvent
import com.discord.utilities.logging.AppGatewaySocketLogger
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import com.discord.utilities.websocket.OkHttpGatewayWebSocketFactory
import com.discord.models.message.Message as DomainMessage
import com.discord.utilities.messagesend.MessageSendCoordinator
import com.discord.utilities.notifications.NotificationData
import com.discord.utilities.notifications.NotificationKind
import com.discord.utilities.notifications.NotificationRenderer
import com.discord.utilities.notifications.NotificationSource
import com.discord.utilities.fcm.NotificationClient
import com.discord.widgets.settings.NotificationStore
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemCallMessage
import com.discord.widgets.chat.input.ChatInputGuardActions
import com.discord.widgets.chat.input.ChatInputStoreBindings
import com.discord.widgets.chat.input.WidgetChatInput
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.overlay.WidgetChatOverlay
import com.discord.widgets.friends.FriendRelationshipUi
import com.discord.widgets.friends.FriendRelationship
import com.discord.widgets.friends.FriendUserUi
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.WidgetUserStatusSheet
import com.discord.widgets.user.WidgetUserSetCustomStatus
import com.discord.widgets.user.WidgetUserMutualFriends
import com.discord.widgets.user.WidgetUserMutualGuilds
import com.discord.widgets.user.profile.UserStatusPresenceCustomView
import com.discord.widgets.settings.account.WidgetSettingsBlockedUsers
import com.discord.widgets.friends.BlockedUsersBuilder
import com.discord.widgets.home.WidgetHomeViewModel
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.home.WidgetHomeModel
import com.discord.widgets.home.WidgetHomePanelLoading
import com.discord.widgets.home.WidgetHomePanelNsfw
import com.discord.widgets.stage.StageStoreAccess
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.api.presence.ClientStatus
import com.discord.utilities.error.RestApiError
import com.discord.workers.MessageAckWorker
import com.discord.widgets.chat.list.actions.MessageActionCoordinator
import com.discord.utilities.messagesend.NonceGenerator
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.attachments.UploadAttachmentPreprocessor
import com.discord.utilities.attachments.toUploadAttachment
import com.discord.api.message.MessageReference
import com.discord.utilities.rest.MessageUploadPayloadBuilder
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.textprocessing.Rules
import com.discord.restapi.PayloadJSON
import com.google.gson.Gson
import com.discord.workers.BackgroundMessageSendWorker
import com.discord.workers.MessageWorkerActions
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import rx.Subscription
import java.io.File
import rx.Observable
import rx.Scheduler
import rx.subjects.BehaviorSubject
import rx.schedulers.Schedulers
import com.discord.utilities.user.UserUtils.displayName

class StoreStream private constructor(
    val dispatcher: Dispatcher,
    val storeThreadScheduler: Scheduler,
    val clock: Clock,
    val observationDeck: ObservationDeck,
    private val preferences: SharedPreferences,
    private val sessionDurablePreferences: SharedPreferences,
) {
    private val authentication = StoreAuthentication(
        dispatcher,
        preferences,
        sessionDurablePreferences,
        onAuthStateChanged = ::handleAuthStateInternal,
        onLoginResult = ::handleLoginResultInternal,
        onPreLogout = ::handlePreLogout,
    )
    private val accessibility = StoreAccessibility(dispatcher, observationDeck)
    private val userSettings = StoreUserSettings(dispatcher, accessibility, preferences)
    private val userSettingsSystem = StoreUserSettingsSystem(
        accessibility::updateFontScale,
        dispatcher,
        observationDeck,
        preferences,
        sessionDurablePreferences,
    )
    private val users = StoreUser(
        dispatcher = dispatcher,
        observationDeck = observationDeck,
        requestUsers = { ids, callback ->
            if (::restApi.isInitialized) ids.forEach { userId ->
                restApi.userGet(userId).subscribeOn(Schedulers.io()).subscribe(callback, {})
            }
        },
    )
    private val userRelationships = StoreUserRelationships(observationDeck)
    private lateinit var lurking: StoreLurking
    private val guilds = StoreGuilds(
        users,
        dispatcher,
        observationDeck,
        getLurkingGuildIds = { if (::lurking.isInitialized) lurking.getLurkingGuildIdsSync() else emptySet() },
    )
    private val channels = StoreChannels(
        dispatcher,
        observationDeck,
        getLurkingGuildIds = {
            if (::lurking.isInitialized) lurking.getLurkingGuildIdsSync().toList() else emptyList()
        },
        isAuthenticated = { authentication.isAuthed() || users.getMeInternal().id != 0L },
    )
    // -- Threads --------------------------------------------------------------------------------
    // Declared before `permissions` because StorePermissions takes `storeThreadsJoined` eagerly:
    // private-thread permissions depend on whether the user is a member (StorePermissions.java:92-95).
    private val threadsJoined = StoreThreadsJoined(users, observationDeck)
    private val threadsActive = StoreThreadsActive(channels, observationDeck)
    private val threadsActiveJoined = StoreThreadsActiveJoined(threadsActive, threadsJoined, observationDeck)
    private val threadMembers = StoreThreadMembers(observationDeck, channels)
    private val threadDraft = StoreThreadDraft(dispatcher)
    private val stageInstances = StoreStageInstances(observationDeck)
    private val permissions = StorePermissions(
        users, channels, guilds, observationDeck,
        storeStageInstances = stageInstances,
        storeThreadsJoined = threadsJoined,
    )
    // -- Guild administration ---------------------------------------------------------------------
    // Every store here resolves its REST client through `RestAPI.apiOrNull` and no-ops while it is
    // null, so construction order against `createRestApi` does not matter.
    private val bans = StoreBans(dispatcher, observationDeck)
    /** Deliberately NOT a `StoreV2` — the reference implements it as a plain subject store. */
    private val guildIntegrations = StoreGuildIntegrations(dispatcher)
    private val guildJoinRequests = StoreGuildJoinRequest(users, guilds, dispatcher, observationDeck)
    private val guildTemplates = StoreGuildTemplates(dispatcher, observationDeck)
    private val guildMemberVerificationForm = StoreGuildMemberVerificationForm(dispatcher, observationDeck)
    private val auditLog = StoreAuditLog(dispatcher, observationDeck)
    private val guildRoleSubscriptions = StoreGuildRoleSubscriptions(dispatcher, observationDeck)
    private val guildBoost = StoreGuildBoost(dispatcher, observationDeck)
    private val guildScheduledEvents = StoreGuildScheduledEvents(
        dispatcher, permissions, users, guilds, clock, observationDeck,
    )
    private val instantInvites = StoreInstantInvites(
        dispatcher,
        observationDeck,
        onGuildScheduledEventResolved = guildScheduledEvents::addGuildScheduledEventFromInvite,
    )
    private val readyPayloadHydrator = ReadyPayloadHydrator(guilds, channels)
    private val guildSelected = StoreGuildSelected(
        dispatcher,
        observationDeck,
        guildStore = guilds,
        getLurkingGuildIds = { if (::lurking.isInitialized) lurking.getLurkingGuildIdsSync() else emptySet() },
    )
    private val channelsSelected = StoreChannelsSelected(
        channels, permissions, guildSelected, dispatcher, observationDeck,
    )
    private val guildsNsfw = StoreGuildsNsfw(dispatcher, channels, observationDeck)
    private val search = StoreSearch(this, dispatcher, guildsNsfw, users, observationDeck, channels, guilds)
    private val mediaFavorites = StoreMediaFavorites(observationDeck, dispatcher)
    private val gifPicker = StoreGifPicker(
        dispatcher,
        userSettingsSystem,
        restAPI = { if (::restApi.isInitialized) restApi else null },
        observationDeck = observationDeck,
        mediaFavorites = mediaFavorites,
    )
    private val expressionPickerNavigation = StoreExpressionPickerNavigation(observationDeck, dispatcher)
    private val expressionSuggestions = StoreExpressionSuggestions(observationDeck, dispatcher, clock)
    private val maskedLinks = StoreMaskedLinks(dispatcher, channelsSelected, userRelationships)
    private val channelMembers = StoreChannelMembers(observationDeck) { guildId, roleId ->
        guilds.getGuildRolesInternal()[guildId]?.get(roleId)
    }
    private val guildSubscriptions = StoreGuildSubscriptions(
        observationDeck,
        dispatcher,
        getSelectedGuildId = guildSelected::getSelectedGuildIdInternal,
    )
    private val guildsSorted = StoreGuildsSorted(observationDeck, dispatcher)
    private val expandedGuildFolders = StoreExpandedGuildFolders(dispatcher, observationDeck)
    private val collapsedChannelCategories = StoreCollapsedChannelCategories(dispatcher, observationDeck)
    private var guildFolders: List<ModelGuildFolder> = emptyList()
    private var guildFolderUpdateVersion = 0
    private val guildSettings = StoreUserGuildSettings(
        dispatcher,
        clock,
        observationDeck,
        getChannel = channels::findChannelById,
        getJoinedThreadFlags = { threadsJoined.getJoinedThread(it)?.flags ?: 0 },
    )
    private val guildMemberCounts = StoreGuildMemberCounts(observationDeck)
    private val experiments = StoreExperiments(
        clock,
        dispatcher,
        users,
        guilds,
        authentication,
        approximateMemberCount = { guildId -> guildMemberCounts.getApproximateMemberCount(guildId) },
        observationDeck = observationDeck,
        api = { if (::restApi.isInitialized) restApi else null },
    )

    // -- Emoji, stickers and notices ----------------------------------------------------------------
    // Declared here because `presences` below takes the emoji resolvers eagerly, and `emoji` needs
    // `emojiCustom`, `permissions` and `guildsSorted`, all of which are already built.
    private val notices = StoreNotices(clock, sessionDurablePreferences)
    private val nux = StoreNux(dispatcher)
    private val reviewRequest = StoreReviewRequest(clock, notices, sessionDurablePreferences)
    private val emojiCustom = StoreEmojiCustom(guilds)
    private val emoji = StoreEmoji(
        emojiCustom,
        users,
        permissions,
        guildsSorted,
        observeFavoriteAndFrequentEmojiIds = {
            Observable.combineLatest(
                mediaFavorites.observeFavoriteEmojiKeys(),
                mediaFavorites.observeEmojiFrecency(),
            ) { favorites, _ -> favorites to mediaFavorites.getEmojiFrecencyKeys() }
        },
        getFrequentEmojiKeys = mediaFavorites::getEmojiFrecencyKeys,
        trackEmoji = mediaFavorites::onEmojiUsed,
    )
    private val emojiGuild = StoreEmojiGuild(observationDeck, dispatcher)
    private val stickers = StoreStickers(dispatcher, observationDeck = observationDeck, clock = clock)
    private val guildStickers = StoreGuildStickers(dispatcher, observationDeck) { stickerIds ->
        stickers.handleDeletedStickers(stickerIds)
    }
    private val changeLog = StoreChangeLog(
        clock, notices, users, userSettingsSystem, experiments, dispatcher, preferences,
    )

    private val presences = StoreUserPresence(
        clock, observationDeck, this,
        publishLocalPresence = { presence ->
            gatewayConnection.presenceUpdate(presence.status, activities = presence.activities.orEmpty())
        },
        resolveCustomEmoji = { emojiId ->
            emoji.getCustomEmojiInternal(emojiId)?.let { custom ->
                ActivityEmoji(custom.id.toString(), custom.name, custom.isAnimated)
            }
        },
        // Keyed on the surrogate, not the shortcode: a unicode custom status stores the character
        // itself in `emoji_name`. Confirmed against the decompile (`StoreUserPresence.kt:214-220`,
        // `getUnicodeEmojiSurrogateMap()[emojiName]` → `ActivityEmoji(null, surrogates, false)`).
        resolveUnicodeEmoji = { name ->
            emoji.unicodeEmojiSurrogateMap[name]?.let { ActivityEmoji(null, it.surrogates, false) }
        },
    )
    private val userProfiles = StoreUserProfile(
        dispatcher,
        observationDeck,
        fetchProfile = { userId, guildId, mutual, success, failure ->
            if (!::restApi.isInitialized) failure() else
                restApi.userProfileGet(userId, mutual, guildId).subscribeOn(Schedulers.io()).subscribe(
                    { profile ->
                        success(profile)
                        dispatcher.schedule { users.handleUserUpdated(profile.user) }
                    },
                    { failure() },
                )
        },
        propagateMember = { member, guildId -> guildId?.let { guilds.handleGuildMember(member, it) } },
        fetchProfileEffect = { skuId, success, failure ->
            if (!::restApi.isInitialized) failure() else
                restApi.getCollectiblesProduct(skuId, userSettingsSystem.getLocale())
                    .subscribeOn(Schedulers.io()).subscribe(success, { failure() })
        },
    )
    private val guildProfiles = StoreGuildProfiles(dispatcher, observationDeck)
    private val guildWelcomeScreens = StoreGuildWelcomeScreens(dispatcher, observationDeck)
    private val userNotes = StoreUserNotes(
        dispatcher,
        observationDeck,
        loadNote = { userId, success, failure ->
            if (!::restApi.isInitialized) failure() else
                restApi.getUserNote(userId).subscribeOn(Schedulers.io()).subscribe(success, { failure() })
        },
        saveNote = { userId, note, success, failure ->
            if (!::restApi.isInitialized) failure() else
                restApi.updateUserNotes(userId, RestAPIParams.UserNoteUpdate(note.takeUnless(String::isBlank)))
                    .subscribeOn(Schedulers.io()).subscribe({}, { failure() }, success)
        },
    )
    private val friendSuggestions = StoreFriendSuggestions(
        dispatcher,
        observationDeck,
        fetchSuggestions = { success, failure ->
            if (!::restApi.isInitialized) failure() else
                restApi.getFriendSuggestions().subscribeOn(Schedulers.io()).subscribe(success, { failure() })
        },
        hydrateUsers = users::handleFriendSuggestionsLoaded,
    )
    private val userAffinities = StoreUserAffinities(
        dispatcher,
        observationDeck,
        fetchAffinities = { success, failure ->
            if (!::restApi.isInitialized) failure() else
                restApi.getUserAffinities().subscribeOn(Schedulers.io()).subscribe(success, { failure() })
        },
    )
    private val messagesMostRecent = StoreMessagesMostRecent(
        observationDeck,
        storeChannels = channels,
    )
    private val readStates = StoreReadStates(clock)
    private val messages = StoreMessages(observationDeck)
    private val chat = StoreChat(
        dispatcher,
        observationDeck,
        requestGuildMembers = { guildId, query -> gatewayConnection.requestGuildMembers(guildId, query) },
    )
    private val threadMessages = StoreThreadMessages(messages, channels, observationDeck)
    private val messageReplies = StoreMessageReplies(dispatcher, messages, observationDeck)
    private val messageReactions = StoreMessageReactions(dispatcher, users, observationDeck) {
        if (::restApi.isInitialized) restApi else null
    }
    private val forumPostMessages = StoreForumPostMessages(dispatcher, users, observationDeck) {
        if (::restApi.isInitialized) restApi else null
    }
    private val archivedThreads = ArchivedThreadsStore(
        dispatcher,
        forumPostMessages,
        observationDeck,
        restApi = { if (::restApi.isInitialized) restApi else null },
        handleThreadCreateOrUpdate = { thread -> handleThreadCreateOrUpdateInternal(thread) },
        forumApi = RestAPI::reactNativeApiOrNull,
    )
    /** Mention counting and automatic acknowledgement feed the authoritative read-state ledger. */
    private val messageAck: StoreMessageAck = StoreMessageAck(
        dispatcher,
        observationDeck,
        channels,
        messages,
        messagesMostRecent,
        threadsActiveJoined,
        threadsActive,
        guilds,
        clock,
        meId = { users.getMeInternal().id },
        ackRequest = { channelId, messageId, manual, mentionCount ->
            if (::restApi.isInitialized) {
                restApi.postChannelMessagesAck(
                    channelId,
                    messageId,
                    RestAPIParams.ChannelMessagesAck(manual, mentionCount),
                ).subscribeOn(Schedulers.io())
                    .doOnCompleted { analytics.ackMessage(channelId) }
                    .subscribe({}, {})
            }
        },
        processMarkUnread = { channelId, messagesAfter -> mentions.processMarkUnread(channelId, messagesAfter) },
    )
    private val mentions: StoreMentions = StoreMentions(
        userRelationships,
        permissions,
        messageAck,
        guildSettings,
        channels,
        observationDeck,
        hasJoinedThread = threadsJoined::hasJoinedInternal,
    )
    private val slowMode = StoreSlowMode(clock, channels, permissions)
    private val messagesLoader = StoreMessagesLoader(loader = { request, callback ->
        val generation = authGeneration
        val source = when (request.direction) {
            StoreMessagesLoader.Direction.INITIAL -> restApi.getChannelMessages(request.channelId, limit = request.limit)
            StoreMessagesLoader.Direction.BEFORE -> restApi.getChannelMessages(request.channelId, before = request.anchorMessageId, limit = request.limit)
            StoreMessagesLoader.Direction.AFTER -> restApi.getChannelMessages(request.channelId, after = request.anchorMessageId, limit = request.limit)
            StoreMessagesLoader.Direction.AROUND -> restApi.getChannelMessagesAround(
                request.channelId, request.limit, requireNotNull(request.anchorMessageId),
            )
        }
        source.subscribeOn(Schedulers.io()).map { apiMessages -> apiMessages.map(::DomainMessage) }.subscribe(
            { if (generation == authGeneration) callback(Result.success(it)) },
            { if (generation == authGeneration) callback(Result.failure(it)) },
        )
    })
    private val pendingReplies = StorePendingReplies(dispatcher, observationDeck)
    private val pinnedMessages = StorePinnedMessages(dispatcher, observationDeck) { channelId, callback ->
        val generation = authGeneration
        restApi.getChannelPins(channelId).subscribeOn(Schedulers.io()).map { it.map(::DomainMessage) }.subscribe(
            { if (generation == authGeneration) callback(Result.success(it)) },
            { if (generation == authGeneration) callback(Result.failure(it)) },
        )
    }
    private val messageUploads = StoreMessageUploads(observationDeck)
    private val messageState = StoreMessageState(dispatcher)
    private val chatInputEditing = StoreChatInputEditing()
    private val usersTyping = StoreUsersTyping(dispatcher, observationDeck, { users.getMeInternal().id })
    private lateinit var messageSendCoordinator: MessageSendCoordinator
    private lateinit var messageActionCoordinator: MessageActionCoordinator
    private val uploadNonceGenerator = NonceGenerator(System::currentTimeMillis)
    private lateinit var applicationContext: Context

    /**
     * Play a feedback sound, unless the process has no Application yet.
     *
     * `AppSoundManager` needs one, and unit tests build the store graph without it — the guard is
     * what keeps a mute toggle from throwing in a test.
     */
    private fun playAppSound(sound: AppSound) {
        if (::applicationContext.isInitialized) AppSoundManager.Provider.get().play(sound)
    }
    private val clientDataState = StoreClientDataState(observationDeck)
    private val connectionOpen = StoreConnectionOpen(observationDeck)
    private val connectivity = StoreConnectivity(dispatcher, clock, observationDeck)
    private val gatewayConnection = StoreGatewayConnection(this, clock)
    private val applicationCommandFrecency = StoreApplicationCommandFrecency(dispatcher, observationDeck)
    private val applicationCommands = StoreApplicationCommands(
        dispatcher = dispatcher,
        storeApplicationCommandFrecency = applicationCommandFrecency,
        gatewayRequest = gatewayConnection::requestApplicationCommands,
        storePermissions = permissions,
        restApi = { if (::restApi.isInitialized) restApi else null },
        observationDeck = observationDeck,
    )
    private val localActionComponentState = StoreLocalActionComponentState(dispatcher, observationDeck)
    private val applicationInteractions = StoreApplicationInteractions(
        dispatcher = dispatcher,
        storeMessages = messages,
        storeLocalActionComponentState = localActionComponentState,
        sharedPrefs = preferences,
        observationDeck = observationDeck,
        restApi = { if (::restApi.isInitialized) restApi else null },
    )
    private val applicationDirectory = StoreApplicationDirectory(dispatcher, observationDeck)
    private val applicationStreamPreviews = StoreApplicationStreamPreviews(dispatcher, clock)
    private val embeddedActivities = StoreEmbeddedActivities(observationDeck, dispatcher = dispatcher)
    private val applications = StoreApplication(
        dispatcher,
        observationDeck,
        restApi = { if (::restApi.isInitialized) restApi else null },
    )
    private val applicationAssets = StoreApplicationAssets(
        dispatcher,
        observationDeck,
        restApi = { if (::restApi.isInitialized) restApi else null },
    )
    private val entitlements = StoreEntitlements(
        dispatcher = dispatcher,
        observationDeck = observationDeck,
        restApi = { if (::restApi.isInitialized) restApi else null },
    )
    private val googlePlayPurchases = StoreGooglePlayPurchases(
        observationDeck = observationDeck,
        dispatcher = dispatcher,
        restApi = { if (::restApi.isInitialized) restApi else null },
        clock = clock,
        analytics = BillingUtils.paymentFlowAnalytics,
        verifyPurchases = BillingUtils::verifyPurchases,
    )
    private val googlePlaySkuDetails = StoreGooglePlaySkuDetails(observationDeck, dispatcher)
    private val subscriptions = StoreSubscriptions(
        observationDeck = observationDeck,
        dispatcher = dispatcher,
        restApi = { if (::restApi.isInitialized) restApi else null },
    )
    private val paymentSources = StorePaymentSources(
        dispatcher = dispatcher,
        observationDeck = observationDeck,
        restApi = { if (::restApi.isInitialized) restApi else null },
    )
    private val navigation = StoreNavigation(this)
    private val tabsNavigation = StoreTabsNavigation(dispatcher, this, observationDeck)

    // -- Stores that need the gateway socket ------------------------------------------------------
    private val forumPostReadStates = StoreForumPostReadStates(
        dispatcher = dispatcher,
        requestForumUnreadsOverGateway = gatewayConnection::requestForumUnreads,
        storeThreadsActive = threadsActive,
        storeMessageAck = messageAck,
        storeChannels = channels,
        storeUser = users,
        observationDeck = observationDeck,
    )
    private val notificationRenderer = NotificationRenderer()
    private val notifications = StoreNotifications(
        dispatcher,
        clock,
        observationDeck,
        clearChannelNotifications = { channelId ->
            if (::applicationContext.isInitialized) notificationRenderer.clearChannel(applicationContext, channelId)
        },
        onMessageCandidate = ::deliverNotification,
        // `registerPushToken` stays at its inert default: the reference posts the FCM token through
        // `RestAPI.postScienceTrack`-adjacent `/users/@me/devices`, which is not in RestAPIInterface yet.
    )
    private val gameParty = StoreGameParty(observationDeck, presences, users)
    private val userRequiredActions = StoreUserRequiredActions(observationDeck)
    private val mfa = StoreMFA(dispatcher, observationDeck) { users.getMeInternal().id }
    private val phone = StorePhone(dispatcher, observationDeck)
    private val userSurvey = StoreUserSurvey(
        dispatcher,
        users,
        guilds,
        permissions,
        observationDeck,
        api = { if (::restApi.isInitialized) restApi else null },
        clock = clock,
        sharedPreferences = preferences,
    )
    private val guildMemberRequester = StoreGuildMemberRequester(
        dispatcher,
        guilds,
        channels,
        requestGuildMembers = { guildId, userIds ->
            gatewayConnection.requestGuildMembers(guildId, userIds = userIds)
        },
    )
    private val channelConversions = StoreChannelConversions(
        selectChannel = { guildId, channelId -> selectChannelInternal(guildId, channelId) },
    )
    private val userConnections: StoreUserConnections = StoreUserConnections(
        dispatcher,
        observationDeck,
        api = { if (::restApi.isInitialized) restApi else null },
        onConnectionsLoaded = { accounts ->
            userConnections.handleUserConnections(accounts)
            spotify.handleUserConnections(accounts)
        },
        connectionUpdates = gatewayConnection.userConnectionUpdate,
    )
    private val spotify = StoreSpotify(
        dispatcher = dispatcher,
        clock = clock,
        presences = presences,
        userConnections = userConnections,
        currentUserId = { users.getMeInternal().id },
    )
    private val contactSync = StoreContactSync(
        dispatcher,
        clock,
        userConnections,
        users,
        userSettings,
        api = { if (::restApi.isInitialized) restApi else null },
    )
    private val inviteSettings = StoreInviteSettings()
    private val notificationUpsells = StoreNotificationUpsells(dispatcher, observationDeck)

    // -- Voice and stages ---------------------------------------------------------------------------
    private lateinit var voiceTransport: VoiceTransport
    private lateinit var rtcRegion: StoreRtcRegion
    private val voiceSpeaking = StoreVoiceSpeaking(observationDeck)
    private val videoStreams = StoreVideoStreams(observationDeck)
    private val voiceStates = StoreVoiceStates(
        notifyVoiceStatesUpdated = { guildId, channelId, userId ->
            if (::voiceTransport.isInitialized) voiceTransport.storeAudioManagerV2.handleVoiceStatesUpdated()
            stageChannels.handleVoiceStatesUpdated(guildId, channelId, userId)
        },
        observationDeck = observationDeck,
    )
    // The cache is passed explicitly rather than left to its default: the default reads
    // `SharedPreferencesProvider.get()`, which throws under `initializeForTesting`, where the graph
    // is handed its preferences directly.
    private val mediaSettings = StoreMediaSettings(
        findChannel = channels::findChannelByIdInternal,
        voiceConfigurationCache = VoiceConfigurationCache(preferences),
        playSound = { sound ->
            playAppSound(
                when (sound) {
                    StoreMediaSettings.VoiceSound.MUTE -> AppSound.SOUND_MUTE
                    StoreMediaSettings.VoiceSound.UNMUTE -> AppSound.SOUND_UNMUTE
                    StoreMediaSettings.VoiceSound.DEAFEN -> AppSound.SOUND_DEAFEN
                    StoreMediaSettings.VoiceSound.UNDEAFEN -> AppSound.SOUND_UNDEAFEN
                },
            )
        },
    )
    private val callsIncoming = StoreCallsIncoming(dispatcher, users, observationDeck)
    private val calls = StoreCalls(
        callConnectRequest = { channelId -> gatewayConnection.callConnect(channelId) },
        ringRequest = { channelId, messageId, recipients ->
            RestAPI.apiOrNull?.ring(
                channelId, RestAPIParams.Ring(recipients), callContext(messageId),
            )?.subscribeOn(Schedulers.io())?.subscribe({}, {})
        },
        stopRingingRequest = { channelId, messageId, recipients ->
            RestAPI.apiOrNull?.stopRinging(
                channelId, RestAPIParams.Ring(recipients), callContext(messageId),
            )?.subscribeOn(Schedulers.io())?.subscribe({}, {})
        },
        onStopRingingWithoutCall = { channelId -> callsIncoming.removeIncomingCall(channelId) },
    )
    private val applicationStreaming: StoreApplicationStreaming = StoreApplicationStreaming(
        dispatcher,
        users,
        getSelectedVoiceChannelId = { voiceChannelSelected.getSelectedVoiceChannelId() },
        streamCreateRequest = { streamKey, region -> gatewayConnection.streamCreate(streamKey, region) },
        streamWatchRequest = { streamKey -> gatewayConnection.streamWatch(streamKey) },
        // The server echoes STREAM_DELETE back, which re-enters through the gateway listener below.
        streamDeleteRequest = { delete -> gatewayConnection.streamDelete(delete.streamKey) },
        targetStreamRequest = ::handleStreamTargeted,
        getRtcConnectedChannel = {
            if (!::voiceTransport.isInitialized) null else
                voiceTransport.storeRtcConnection.getRtcConnectionMetadata()?.let { metadata ->
                    metadata.channelId?.let { metadata.guildId to it }
                }
        },
        observationDeck = observationDeck,
    )
    private val voiceChannelSelected = StoreVoiceChannelSelected(
        dispatcher,
        clock,
        observationDeck,
        channels,
        voiceStates,
        permissions,
        getMeId = { users.getMeInternal().id },
        playSound = { sound ->
            playAppSound(
                when (sound) {
                    StoreVoiceChannelSelected.Sound.JOINED -> AppSound.SOUND_USER_JOINED
                    StoreVoiceChannelSelected.Sound.LEFT -> AppSound.SOUND_USER_LEFT
                    StoreVoiceChannelSelected.Sound.MOVED -> AppSound.SOUND_USER_MOVED
                },
            )
        },
        setAudioInputEnabled = { enabled ->
            if (::voiceTransport.isInitialized) voiceTransport.storeMediaEngine.setAudioInputEnabled(enabled)
        },
        getMaxVideoChannelUsers = { guildId ->
            (guilds.getGuildsInternal()[guildId]?.maxVideoChannelUsers as? GuildMaxVideoChannelUsers.Limited)?.limit
        },
    )
    private val stageChannels: StoreStageChannels = StoreStageChannels(
        users, guilds, channels, voiceStates, permissions, stageInstances, observationDeck,
    )
    private val requestedStageChannels = StoreRequestedStageChannels(dispatcher, observationDeck)
    private val stageChannelSelfPresence = StoreStageChannelSelfPresence(
        dispatcher, users, presences, stageChannels, voiceChannelSelected, userSettings, observationDeck,
    )
    private val voiceParticipants = StoreVoiceParticipants(
        channels,
        users,
        guilds,
        voiceStates,
        voiceSpeaking,
        calls,
        videoStreams,
        applicationStreaming,
        mediaSettings,
        observeSelectedVoiceChannelId = { voiceChannelSelected.observeSelectedVoiceChannelId() },
        streamContextForUser = { userId -> streamContextService.getForUser(userId, true) },
    )
    private val analytics = StoreAnalytics(
        stores = object : StoreAnalytics.Stores {
            override fun getChannel(channelId: Long) = channels.findChannelById(channelId)
            override fun getChannelsForGuild(guildId: Long) = channels.getChannelsForGuild(guildId).values
            override fun getSelectedChannelId() = channelsSelected.getId()
            override fun getGuild(guildId: Long) = guilds.getGuild(guildId)
            override fun getSelectedGuildId() = guildSelected.getSelectedGuildId()
            override fun getApproximateMemberCount(guildId: Long) = guildMemberCounts.getApproximateMemberCount(guildId)
            override fun getGuildRoleCount(guildId: Long) = guilds.getRoles()[guildId]?.size ?: 0
            override fun getGuildMemberRoleCount(guildId: Long, userId: Long) =
                guilds.getMember(guildId, userId)?.getRoles()?.size ?: 0
            override fun isGuildMember(guildId: Long, userId: Long) = guilds.getMember(guildId, userId) != null
            override fun isGuildMemberPending(guildId: Long, userId: Long) =
                guilds.getMember(guildId, userId)?.pending == true
            override fun getGuildNotificationSettings(guildId: Long) = guildSettings.getGuildSettings()[guildId]
            override fun getGuildPermissions(guildId: Long) = permissions.getGuildPermissions()[guildId]
            override fun getChannelPermissions(channelId: Long) = permissions.getPermissionsByChannel()[channelId]
            override fun getMeId() = users.getMe().id
            override fun getThreadMessageCount(channelId: Long) = threadMessages.getThreadMessageCount(channelId)
            override fun getVoiceStatesForGuild(guildId: Long) = voiceStates.get()[guildId].orEmpty()
            override fun getSelectedVoiceChannelId() = voiceChannelSelected.getSelectedVoiceChannelId()
            override fun getLastSelectedVoiceChannelId() = voiceChannelSelected.getLastSelectedVoiceChannelId()
            override fun observeSelectedVoiceChannel() = voiceChannelSelected.observeSelectedChannel()
            override fun observeInputMode() = mediaSettings.getInputMode()
            override fun getRtcConnectionMetadata() =
                if (::voiceTransport.isInitialized) voiceTransport.storeRtcConnection.getRtcConnectionMetadata() else null
            override fun getPreferredRtcRegion() = if (::rtcRegion.isInitialized) rtcRegion.getPreferredRegion() else null
            override fun getVoiceConfiguration() = mediaSettings.getVoiceConfigurationBlocking()
            override fun isScreenSharing() = applicationStreaming.isScreenSharing()
            override fun getSelectedVideoInputDevice() =
                if (::voiceTransport.isInitialized) voiceTransport.storeMediaEngine.getSelectedVideoInputDeviceBlocking() else null
            override fun getMaxViewersForStream(userId: Long, channelId: Long, guildId: Long?) =
                applicationStreaming.getMaxViewersForStream(userId, channelId, guildId)
            override fun getTheme() = userSettingsSystem.getTheme()
        },
        dispatcher = dispatcher,
        clock = clock,
    )

    /**
     * `by lazy` because it takes the stores declared above it and is only touched once a voice
     * tile subscribes — building it eagerly here would depend on property initialisation order.
     */
    private val streamContextService by lazy {
        StreamContextService(
            applicationStreamingStore = applicationStreaming,
            guildStore = guilds,
            permissionsStore = permissions,
            userStore = users,
            voiceChannelSelectedStore = voiceChannelSelected,
            voiceStateStore = voiceStates,
            channelStore = channels,
        )
    }
    private val storesV2 = mutableListOf(
        accessibility,
        userSettingsSystem,
        users,
        userRelationships,
        guilds,
        permissions,
        guildSelected,
        channelsSelected,
        channelMembers,
        guildSubscriptions,
        guildsSorted,
        expandedGuildFolders,
        collapsedChannelCategories,
        guildSettings,
        channels,
        stageInstances,
        presences,
        userProfiles,
        guildProfiles,
        guildWelcomeScreens,
        userNotes,
        friendSuggestions,
        userAffinities,
        usersTyping,
        messages,
        messagesMostRecent,
        pendingReplies,
        pinnedMessages,
        messageUploads,
        tabsNavigation,
        clientDataState,
        connectionOpen,
        connectivity,
        applicationCommandFrecency,
        applicationCommands,
        localActionComponentState,
        applicationInteractions,
        applicationDirectory,
        embeddedActivities,
        applications,
        applicationAssets,
        applicationStreamPreviews,
        entitlements,
        googlePlayPurchases,
        googlePlaySkuDetails,
        subscriptions,
        paymentSources,
        // Threads and forums
        threadsJoined,
        threadsActive,
        threadsActiveJoined,
        threadMembers,
        threadMessages,
        threadDraft,
        forumPostMessages,
        forumPostReadStates,
        archivedThreads,
        // Chat and messages
        chat,
        messageReplies,
        messageReactions,
        messageAck,
        mentions,
        // Emoji and stickers. `emoji`, `notices`, `nux`, `reviewRequest` and `changeLog` are plain
        // `Store`s and must NOT be listed here.
        emojiCustom,
        emojiGuild,
        stickers,
        guildStickers,
        // Cross-cutting
        experiments,
        guildsNsfw,
        gifPicker,
        expressionPickerNavigation,
        expressionSuggestions,
        maskedLinks,
        mediaFavorites,
        notificationUpsells,
        notifications,
        gameParty,
        guildMemberCounts,
        userRequiredActions,
        userConnections,
        contactSync,
        mfa,
        phone,
        userSurvey,
        channelConversions,
        // Guild administration. `guildIntegrations` is deliberately absent: the reference
        // implements it as a plain subject store, not a StoreV2.
        bans,
        guildJoinRequests,
        guildTemplates,
        guildMemberVerificationForm,
        auditLog,
        guildRoleSubscriptions,
        guildBoost,
        guildScheduledEvents,
        instantInvites,
        // Voice and stages
        voiceStates,
        voiceSpeaking,
        voiceChannelSelected,
        callsIncoming,
        applicationStreaming,
        videoStreams,
        stageChannels,
        requestedStageChannels,
        stageChannelSelfPresence,
    )
    private val storeV2DispatchHandler = StoreV2DispatchHandler(storesV2, observationDeck)
    private val initialized = BehaviorSubject.create(false)
    private val gatewayScheduler = RealGatewayScheduler()
    private var gateway: GatewaySocket? = null
    private var gatewayInitTimeout: GatewayCancellable? = null
    private var gatewayToken: String? = null
    @Volatile private var authGeneration = 0
    /** Drives `StoreNotifications`' foreground/system split. Fed by `Backgrounded.observe()`. */
    @Volatile private var isBackgrounded = false
    private val activeUploads = ConcurrentHashMap<String, Pair<Subscription, com.discord.utilities.rest.MessageUploadPayload>>()
    private val preprocessingUploads = ConcurrentHashMap<String, Subscription>()
    private data class FailedUpload(
        val channelId: Long,
        val content: String,
        val reference: MessageReference?,
        val attachments: List<UploadAttachment>,
    )
    private val failedUploads = ConcurrentHashMap<String, FailedUpload>()

    init {
        lurking = StoreLurking(
            guildsStore = guilds,
            guildSelectedStore = guildSelected,
            channelsStore = channels,
            dispatcher = dispatcher,
            getSelectedVoiceChannelId = voiceChannelSelected::getSelectedVoiceChannelId,
            selectChannel = ::selectChannelInternal,
            selectGuild = ::selectGuildInternal,
            onGuildJoined = guildWelcomeScreens::handleGuildJoined,
            launchLurkNotice = { guildId ->
                navigation.launchNotice("LURK:$guildId") {
                    navigation.setNavigationPanelAction(StoreNavigation.PanelAction.OPEN)
                    true
                }
            },
        )
        StageStoreAccess.install(lurking)
        WidgetHomeViewModel.storeLurking = lurking
        WidgetHomeViewModel.storeGuildsNsfw = guildsNsfw
        WidgetHomeModel.guildNsfwGateAgreed = guildsNsfw::isGuildNsfwGateAgreed
        WidgetHomePanelNsfw.onGuildNsfwGateAllowed = guildsNsfw::allow
        WidgetHomePanelNsfw.onGuildNsfwGateDenied = guildsNsfw::deny
        WidgetHomePanelLoading.onHomeShown = { analytics.appUiViewed(WidgetHome::class.java) }
        ChatInputStoreBindings.chatEvents = chat::observeEvents
        ChatInputStoreBindings.slowModeCooldown = { channelId ->
            slowMode.observeCooldownSecs(channelId, StoreSlowMode.Type.MessageSend)
        }
        ChatInputStoreBindings.joinedThreads = threadsActiveJoined::observeActiveJoinedThreadsChannelsForGuild
        ChatInputStoreBindings.isLurking = lurking::isLurkingObs
        ChatInputStoreBindings.threadDraftState = threadDraft::observeDraftState
        com.discord.widgets.search.SearchStoreProvider.install(search.asWidgetStore())
        ChatInputGuardActions.joinGuildAsMember = { guildId, onSuccess, onFailure ->
            lurking.postJoinGuildAsMember(guildId, { onSuccess() }, onFailure)
        }
    }

    private fun init(context: Context) {
        applicationContext = context.applicationContext
        val api = createRestApi(context)
        val networkMonitor = NetworkMonitor(context)
        val rtcLatencyApi = RestAPIBuilder(API_BASE_URL, CookieJars.persistent(context)).build(
            RestAPIInterface.RtcLatency::class.java,
            addVersion = false,
            clientName = "rtc-latency",
        )
        rtcRegion = StoreRtcRegion(
            dispatcher = dispatcher,
            clock = clock,
            fetchRegionsWithIps = { rtcLatencyApi.get(RTC_LATENCY_URL) },
            hasNativeEngineEverInitialized = {
                voiceTransport.storeMediaEngine.hasNativeEngineEverInitialized()
            },
            rankRegionsByLatency = { regions, callback ->
                voiceTransport.storeMediaEngine.getRankedRtcRegions(regions, callback)
            },
        ).also(StoreRtcRegion::init)
        voiceTransport = VoiceTransport.install {
            VoiceTransport(
                context = context,
                dispatcher = dispatcher,
                clock = clock,
                networkMonitor = networkMonitor,
                storeRtcRegion = rtcRegion,
                storeUser = users,
                storeMediaSettings = mediaSettings,
                storeVideoStreams = videoStreams,
                videoUseDetector = StoreAudioManagerV2.VideoUseDetector(channels, voiceStates, users),
                findChannel = channels::findChannelByIdInternal,
                sendVoiceStateUpdate = { guildId, channelId, mute, deaf, video, region, includeRegion ->
                    gatewayConnection.voiceStateUpdate(
                        guildId, channelId, mute, deaf, video, region, includeRegion,
                    )
                },
                getSelectedVoiceChannelId = voiceChannelSelected::getSelectedVoiceChannelId,
            )
        }
        storesV2 += voiceTransport.storeStreamRtcConnection
        storesV2 += voiceTransport.storeAudioManagerV2
        dispatcher.registerDispatchHandlers(storeV2DispatchHandler)
        voiceTransport.storeRtcConnection.speakingUpdates.subscribe { updates ->
            dispatcher.schedule { voiceSpeaking.handleSpeakingUpdates(updates) }
        }
        voiceSpeaking.observeSpeakingUsers().subscribe { speakingUsers ->
            dispatcher.schedule { analytics.handleUserSpeaking(speakingUsers) }
        }
        applicationStreaming.observeActiveStream()
            .map { applicationStreaming.isScreenSharing() }
            .distinctUntilChanged()
            .subscribe { isScreenSharing ->
                dispatcher.schedule { analytics.handleIsScreenSharingChanged(isScreenSharing) }
            }
        voiceTransport.storeMediaEngine.getSelectedVideoInputDevice().subscribe { device ->
            dispatcher.schedule { analytics.handleVideoInputDeviceSelected(device) }
        }
        voiceTransport.storeRtcConnection.connectionState.filter { it }.subscribe {
            gatewayConnection.handleRtcConnectionLive(null)
        }
        voiceTransport.storeStreamRtcConnection.observeState()
            .filter { it.isConnected && it.streamKey != null }
            .map { requireNotNull(it.streamKey) }
            .distinctUntilChanged()
            .subscribe(gatewayConnection::handleRtcConnectionLive)
        BootLog.record(BootLogEvent.REST_READY)
        authentication.bindApi(api)
        userSettings.bindApi(api)
        guildProfiles.bindApi(api)
        guildWelcomeScreens.bindApi(api)
        lurking.bindApi(api)
        userSettingsSystem.init(context)
        messageSendCoordinator = MessageSendCoordinator(api, contentResolver = context.contentResolver)
        messages.installSendPipeline(
            dispatcher = dispatcher,
            sendCoordinator = messageSendCoordinator,
            slowMode = slowMode,
            contentResolver = context.contentResolver,
            deleteRemoteMessage = { channelId, messageId ->
                api.deleteMessage(channelId, messageId).subscribeOn(Schedulers.io()).subscribe({}, {})
            },
            scheduleBackgroundSend = { scheduleBackgroundMessageSend(true) },
            cancelBackgroundSend = {
                WorkManager.getInstance(applicationContext).cancelUniqueWork("BACKGROUND_MESSAGE_SENDING")
            },
        )
        messageActionCoordinator = MessageActionCoordinator(
            MessageActionCoordinator.Callbacks(
                deleteRemote = { channelId, messageId, completed ->
                    api.deleteMessage(channelId, messageId).subscribeOn(Schedulers.io()).subscribe(
                        {}, { completed(Result.failure(it)) }, { completed(Result.success(Unit)) },
                    )
                },
                editRemote = { channelId, messageId, content, completed ->
                    api.editMessage(channelId, messageId, RestAPIParams.Message(content = content)).subscribeOn(Schedulers.io()).subscribe(
                        { completed(Result.success(Unit)) }, { completed(Result.failure(it)) },
                    )
                },
                toggleReactionRemote = { channelId, messageId, reaction, removing, completed ->
                    val call = if (removing) api.removeSelfReaction(channelId, messageId, reaction)
                    else api.addReaction(channelId, messageId, reaction)
                    call.subscribeOn(Schedulers.io()).subscribe(
                        {}, { completed(Result.failure(it)) }, { completed(Result.success(Unit)) },
                    )
                },
                setPinnedRemote = { channelId, messageId, pinned, completed ->
                    val call = if (pinned) api.addChannelPin(channelId, messageId) else api.deleteChannelPin(channelId, messageId)
                    call.subscribeOn(Schedulers.io()).subscribe(
                        {}, { completed(Result.failure(it)) }, { completed(Result.success(Unit)) },
                    )
                },
                messageUpdated = { message -> dispatcher.schedule {
                    messages.handleMessageUpdate(message.channelId, message.id) { message }
                } },
                messageDeleted = { channelId, messageId -> dispatcher.schedule {
                    messages.handleMessageDelete(channelId, messageId)
                } },
                createPendingReply = { channel, message, mention, showToggle ->
                    pendingReplies.onCreatePendingReply(channel, message, mention, showToggle)
                },
                retryLocal = { message ->
                    if (message.hasLocalUploads) retryFailedUpload(message)
                    else message.nonce?.let {
                        if (!messageSendCoordinator.retry(it)) messageSendCoordinator.resume(message.copy(type = -1))
                    }
                },
                cancelLocal = { message -> message.nonce?.let {
                    messageSendCoordinator.cancel(it)
                    cancelUpload(it)
                } },
            ),
        )
        messageSendCoordinator.observeUploadEvents().subscribe { event ->
            dispatcher.schedule {
                when (event) {
                    is MessageSendCoordinator.UploadEvent.Preprocessing -> messageUploads.onPreprocessing(
                        event.requestId,
                        event.preprocessing.numFiles,
                        event.preprocessing.name,
                        event.preprocessing.mimeType,
                    )
                    is MessageSendCoordinator.UploadEvent.Progress ->
                        messageUploads.bindUpload(event.requestId, event.uploads)
                }
            }
        }
        messageSendCoordinator.observeUpdates().subscribe { update ->
            dispatcher.schedule {
                when (update) {
                    is MessageSendCoordinator.Update.Reconciled -> messageUploads.onSuccess(update.requestId)
                    is MessageSendCoordinator.Update.Failed -> messageUploads.onFailure(update.requestId)
                    is MessageSendCoordinator.Update.Cancelled -> messageUploads.onCancel(update.requestId)
                    is MessageSendCoordinator.Update.Optimistic -> Unit
                }
            }
        }
        installChatStoreWiring()
        readStates.init(context)
        // These mutate store state, so they belong on the store thread. `App.onCreate` calls
        // `initialize` from the main thread, and `dispatcher.schedule` preserves FIFO order, so the
        // relative ordering below is unchanged.
        dispatcher.schedule {
            experiments.init(context)
            guildsNsfw.init(context)
            maskedLinks.init(context)
            userConnections.init(context)
            spotify.init(context)
            contactSync.init(context)
            mediaSettings.init()
            analytics.init(context)
            notifications.restore()
            applicationInteractions.init(context)
        }
        // Order is load-bearing. `DiscordParser` builds its rule set once and the unicode-emoji
        // rule captures the catalog pattern at that moment, so `setEmojiDataProvider` must run
        // after the catalog is loaded and before anything parses a message. Miss it and named and
        // unicode emoji render as literal text app-wide, with no error anywhere.
        notices.init(context)
        reviewRequest.init(context)
        changeLog.init(context.applicationContext as Application)
        emoji.initBlocking(context)
        mediaFavorites.installEmojiResolvers(
            fromServer = { key -> emoji.unicodeEmojisNamesMap[key]?.getUniqueId() ?: key },
            toServer = { key ->
                emoji.unicodeEmojisNamesMap.values.firstOrNull { it.getUniqueId() == key }?.firstName ?: key
            },
        )
        Rules.setEmojiDataProvider(emoji)
        stickers.init()
        authentication.observeFingerprint().subscribe(
            { fingerprint -> dispatcher.schedule {
                experiments.handleFingerprint(fingerprint)
                analytics.handleFingerprint(fingerprint)
            } },
            {},
        )
        com.discord.utilities.lifecycle.Backgrounded.observe().subscribe(
            { backgrounded -> isBackgrounded = backgrounded },
            {},
        )
        WidgetChatList.installStores(messages, messagesLoader)
        // Private-channel titles are built in the api layer, which cannot reach this store.
        com.discord.api.channel.friendNicknameResolver = userRelationships::getNickname
        WidgetChatList.actionCoordinator = messageActionCoordinator
        WidgetChatInput.coordinatorProvider = { messageSendCoordinator }
        WidgetChatInput.pendingRepliesStore = pendingReplies
        WidgetChatInput.editingStore = chatInputEditing
        WidgetChatInput.uploadHandler = { request -> sendUploads(context, request) }
        WidgetChatOverlay.onJumpToPresent = { messagesLoader.requestNewestMessages() }
        WidgetChatOverlay.stateSource = {
            channelsSelected.observeSelectedChannel().switchMap { channel ->
                if (channel == null) Observable.just(WidgetChatOverlay.State()) else Observable.combineLatest(
                    messages.observeIsDetached(channel.id),
                    usersTyping.observeTypingUsers(channel.id),
                    users.observeAllUsers(),
                ) { detached, typingIds, allUsers ->
                    WidgetChatOverlay.State(
                        channelId = channel.id,
                        viewingOldMessages = detached,
                        typingUsers = typingIds.mapNotNull { allUsers[it]?.displayName }.take(4),
                        channelRateLimit = channel.rateLimitPerUser,
                    )
                }
            }
        }
        MessageWorkerActions.sendPendingMessages = {
            val pending = messages.localMessagesHolder.getFlattenedMessages().filter { it.type == -1 }
            val uploads = pending.filter(DomainMessage::hasLocalUploads)
            uploads.forEach(::retryFailedUpload)
            messageSendCoordinator.resumeAndWait(pending.filterNot(DomainMessage::hasLocalUploads)) && uploads.isEmpty()
        }
        MessageWorkerActions.acknowledgeMessage = { channelId, messageId ->
            runCatching {
                restApi.postChannelMessagesAck(
                    channelId, messageId, RestAPIParams.ChannelMessagesAck(false, 0),
                ).subscribeOn(Schedulers.io()).toCompletable().await()
                true
            }.getOrDefault(false)
        }
        MessageWorkerActions.isAuthenticated = authentication::isAuthed
        MessageWorkerActions.isConnected = { networkMonitor.getState() == NetworkMonitor.State.ONLINE }
        MessageWorkerActions.isCurrentAuthGeneration = { it == authGeneration }
        MessageWorkerActions.sendDirectReply = { channelId, channelName, content, nonce, stickerId ->
            runCatching {
                restApi.sendMessage(
                    channelId,
                    RestAPIParams.Message(
                        content = content,
                        nonce = nonce,
                        stickerIds = stickerId.takeIf { it != -1L }?.let(::listOf),
                    ),
                    null,
                ).subscribeOn(Schedulers.io()).toBlocking().single().also {
                    analytics.ackMessage(channelId)
                    NotificationClient.displaySent(applicationContext, channelId, channelName, true)
                }
                true
            }.getOrElse {
                NotificationClient.displaySent(applicationContext, channelId, channelName, false)
                false
            }
        }
        MessageWorkerActions.declineCall = { channelId, messageId ->
            runCatching {
                restApi.stopRinging(
                    channelId, RestAPIParams.Ring(null), callContext(messageId),
                ).subscribeOn(Schedulers.io()).toCompletable().await()
                true
            }.getOrDefault(false)
        }
        MessageWorkerActions.timedMute = { guildId, channelId, untilTimestampMs ->
            runCatching {
                val settings = restApi.updateUserGuildSettings(
                    guildId,
                    RestAPIParams.UserGuildSettings(
                        channelId,
                        RestAPIParams.UserGuildSettings.ChannelOverride(true, ModelMuteConfig(untilTimestampMs)),
                    ),
                ).subscribeOn(Schedulers.io()).toBlocking().single()
                NotificationClient.clear(channelId, applicationContext)
                analytics.onNotificationSettingsUpdated(settings, channelId)
                true
            }.getOrDefault(false)
        }
        configureSocialUi()
        messagesLoader.observeChunks().subscribe { chunk ->
            dispatcher.schedule {
                applicationInteractions.handleMessagesCreateOrLoad(chunk.messages)
                messages.handleMessagesLoaded(chunk)
                channels.handleMessagesLoaded(chunk.messages.mapNotNull(DomainMessage::thread))
                threadMessages.handleMessagesLoaded(chunk)
                messageReplies.handleLoadMessages(chunk.messages)
                guildMemberRequester.handleLoadMessages(chunk.channelId, chunk.messages)
            }
        }
        gateway = GatewaySocket(
            identifyDataProvider = gatewayConnection::getIdentifyData,
            identifyProperties = mapOf(
                "browser" to "Discord Android",
                "browser_user_agent" to USER_AGENT,
                "client_build_number" to BuildConfig.VERSION_CODE,
                "client_version" to BuildConfig.VERSION_NAME,
                "device" to Build.DEVICE,
                "os" to "Android",
                "os_sdk_version" to Build.VERSION.SDK_INT,
                "os_version" to Build.VERSION.RELEASE,
                "system_locale" to userSettingsSystem.getLocale(),
            ),
            eventHandler = gatewayConnection,
            scheduler = gatewayScheduler,
            discovery = GatewayDiscovery(preferences) { api.getGateway().toBlocking().single() },
            webSocketFactory = OkHttpGatewayWebSocketFactory(),
            gatewaySocketLogger = AppGatewaySocketLogger.INSTANCE,
        ).also { socket ->
            gatewayConnection.init(socket, voiceChannelSelected.observeSelectedVoiceChannelId())
            initGatewaySocketListeners()
        }
        connectivity.init(networkMonitor, messagesLoader.observeChannelMessagesLoading())
        com.discord.utilities.lifecycle.Backgrounded.observe().subscribe(
            { backgrounded -> dispatcher.schedule { connectivity.handleBackgrounded(backgrounded) } },
            {},
        )
        BootLog.record(BootLogEvent.GATEWAY_READY)
        authentication.init(context)
        BootLog.record(BootLogEvent.SESSION_READY)
        accessibilityMonitorBind(context)
        accessibility.init(context)
        userSettings.init(context)
        userSettings.observeCustomStatus().subscribe { status ->
            dispatcher.schedule { presences.handleUserSettingsUpdate(ModelUserSettings(customStatus = status)) }
        }
        BootLog.record(BootLogEvent.CACHE_RESTORE_STARTED)
        dispatcher.schedule {
            users.restore()
            userRelationships.init()
            guilds.restore()
            channels.init()
            permissions.init()
            guildSelected.restore()
            channelsSelected.restore()
            expandedGuildFolders.restore()
            collapsedChannelCategories.restore()
            guildSettings.restore()
            messages.restore()
            guildFolders = userSettings.getGuildFolders()
            updateSortedGuilds()
            BootLog.record(BootLogEvent.CACHE_RESTORE_FINISHED)
        }
        initialized.onNext(true)
        BootLog.record(BootLogEvent.RUNTIME_READY)
    }

    private fun initForTesting() {
        dispatcher.registerDispatchHandlers(storeV2DispatchHandler)
        authentication.init()
        userSettingsSystem.restore()
        accessibility.initForTesting()
        userSettings.initForTesting()
        dispatcher.schedule {
            readStates.initForTesting()
            // On the store thread, unlike [initialize]. `installChatStoreWiring` subscribes to
            // `observeSelectedVoiceChannelId`, and `ObservationDeck.connect` invokes the new
            // observer's `onUpdate` while holding the deck monitor — which reaches
            // `dispatcher.schedule`. The test dispatcher is synchronous, so from any other thread
            // that hands the action to the store thread and waits, while the store thread's
            // `onDispatchEnded` needs the deck monitor the waiter is holding: a deadlock. Scheduled
            // here the whole chain runs on one thread, where the monitor is simply re-entered.
            installChatStoreWiring()
        }
        dispatcher.schedule {
            users.restore()
            userRelationships.init()
            guilds.restore()
            channels.init()
            permissions.init()
            guildSelected.restore()
            channelsSelected.restore()
            expandedGuildFolders.restore()
            collapsedChannelCategories.restore()
            guildSettings.restore()
            messages.restore()
            guildFolders = userSettings.getGuildFolders()
            updateSortedGuilds()
        }
        // No socket is attached in tests; the listeners still let a test drive the router directly
        // through handleGatewayDispatch.
        initGatewaySocketListeners()
        initialized.onNext(true)
    }

    /**
     * Connects [StoreChat] to the two stores that consume its interaction state.
     *
     * Without this, `StoreMessagesLoader.handleChatInteraction` is unreachable and interaction
     * driven pagination cannot work at all — the audit's "infinite scroll is inert" finding.
     * Also installs the two seams that widgets left open for stores `StoreStream` did not publish.
     */
    private fun installChatStoreWiring() {
        guildSelected.observeSelectedGuildId().distinctUntilChanged().subscribe { guildId ->
            dispatcher.schedule { guildSubscriptions.handleGuildSelect(guildId) }
        }
        channelsSelected.observeResolvedSelectedChannel().distinctUntilChanged().subscribe(
            { selected -> dispatcher.schedule { handleChannelSelected(selected) } },
            {},
        )
        messagesLoader.init(
            chat,
            messages,
            StoreMessagesLoader.Dependencies(
                observeChannelMessages = { channelId -> messages.observeMessagesForChannel(channelId) },
                observeMessage = { channelId, messageId -> messages.observeMessage(channelId, messageId) },
                observeChannel = { channelId -> channels.observeChannel(channelId) },
                getChannel = channels::getChannel,
                observeConnectionOpen = { connectionOpen.observeConnectionOpen() },
                selectChannel = { channel -> selectChannelInternal(channel.guildId, channel.id) },
                meUserId = { users.getMe().id },
            ),
        )
        messageAck.init(channelsSelected.observeResolvedSelectedChannel(), chat, messagesLoader)
        stageChannelSelfPresence.init()
        NotificationStore.resolver = { notifications }
        WidgetChatListAdapterItemCallMessage.voiceParticipants = { channelId -> voiceParticipants.get(channelId) }

        // The voice-channel selection is a store output, not a gateway event, so it is fanned out
        // here rather than from `initGatewaySocketListeners`.
        voiceChannelSelected.observeSelectedVoiceChannelId().distinctUntilChanged().subscribe(
            { voiceChannelId ->
                dispatcher.schedule {
                    if (::voiceTransport.isInitialized) {
                        voiceTransport.storeRtcConnection.handleVoiceChannelSelected(voiceChannelId)
                        voiceTransport.storeMediaEngine.handleVoiceChannelSelected(voiceChannelId)
                    }
                    voiceSpeaking.handleVoiceChannelSelected(voiceChannelId)
                    videoStreams.handleVoiceChannelSelected(voiceChannelId)
                    applicationStreaming.handleVoiceChannelSelected(voiceChannelId)
                    callsIncoming.handleVoiceChannelSelected(voiceChannelId)
                    mediaSettings.handleVoiceChannelSelected(voiceChannelId)
                    stageChannelSelfPresence.handleVoiceChannelSelected()
                    lurking.handleVoiceChannelSelected(voiceChannelId)
                    messagesLoader.handleVoiceChannelJoined(voiceChannelId)
                }
            },
            {},
        )
    }

    /**
     * Feeds a freshly created message to [StoreNotifications].
     *
     * `StoreNotifications` decides *whether* to deliver; this only assembles the inputs the policy
     * needs, all of which live in other stores. Before this existed the store was never called at
     * all, which is what made it dead code.
     */
    @StoreThread
    private fun maybeNotify(message: DomainMessage) {
        val channel = channels.getChannelsByIdInternal()[message.channelId]
            ?: channels.findChannelByIdInternal(message.channelId)
            ?: return
        val meId = users.getMeInternal().id
        val guildId = channel.guildId
        notifications.handleMessageCreate(
            NotificationPolicy.Input(
                message = message,
                channel = channel,
                parentChannel = channel.parentId.takeIf { it != 0L }
                    ?.let { channels.getChannelsByIdInternal()[it] },
                guild = guilds.getGuildsInternal()[guildId],
                currentUserId = meId,
                relationshipType = message.author?.id?.let { userRelationships.getRelationships()[it] },
                effectivePermissions = permissions.getPermissionsByChannelInternal()[channel.id],
                currentUserRoleIds = guilds.getGuildMembersInternal()[guildId]?.get(meId)?.roles.orEmpty().toSet(),
                guildSettings = guildSettings.getGuildSettingsInternal()[guildId],
                selectedChannelId = channelsSelected.getSelectedChannelInternal().id,
                isDnd = presences.getLocalPresenceInternal().status == ClientStatus.DND,
                appSettings = notifications.getSettings(),
            ),
            isForeground = !isBackgrounded,
        )
    }

    /** Renders a notification the policy accepted. */
    private fun deliverNotification(message: DomainMessage, delivery: NotificationPolicy.Delivery) {
        if (!::applicationContext.isInitialized) return
        val channel = channels.getChannel(message.channelId) ?: return
        val data = NotificationData(
            kind = if (channel.guildId == 0L) NotificationKind.DIRECT_MESSAGE else NotificationKind.MESSAGE,
            channelId = message.channelId,
            messageId = message.id,
            guildId = channel.guildId.takeIf { it != 0L },
            title = channel.getDisplayName(),
            body = message.content.orEmpty(),
            sender = NicknameUtils.getName(channel.guildId.takeIf { it != 0L }, channel.id, message.author),
            timestamp = clock.currentTimeMillis(),
            authGeneration = authGeneration,
            allowReply = true,
            source = NotificationSource.GATEWAY,
        )
        when (delivery) {
            NotificationPolicy.Delivery.FOREGROUND ->
                notificationRenderer.renderLocal(applicationContext, data)
            NotificationPolicy.Delivery.SYSTEM ->
                notificationRenderer.renderGateway(applicationContext, data)
        }
    }

    private fun accessibilityMonitorBind(context: Context) =
        com.discord.utilities.accessibility.AccessibilityMonitor.INSTANCE.bindContext(context)

    fun handleConnectionOpen(userSettings: ModelUserSettings, guilds: List<Guild>) = dispatcher.schedule {
        this.userSettings.handleConnectionOpen(userSettings)
        userSettingsSystem.handleConnectionOpen(userSettings)
        stageInstances.handleConnectionOpen(guilds)
    }

    fun handleConnectionOpen(payload: ModelPayload) = dispatcher.schedule { handleConnectionOpenInternal(payload) }

    @StoreThread
    fun handleSearchFinish(searchState: com.discord.utilities.search.network.state.SearchState) {
        channels.handleSearchFinish(searchState.threads.orEmpty())
        permissions.handleSearchFinish(searchState.hits)
        threadsJoined.handleSearchFinish(searchState.threads, searchState.threadMembers)
    }

    fun handleUserSettingsUpdate(userSettings: ModelUserSettings) = dispatcher.schedule {
        this.userSettings.handleUserSettingsUpdate(userSettings)
        userSettingsSystem.handleUserSettingsUpdate(userSettings)
        userSettings.guildFolders?.let { guildFolders = it }
        updateSortedGuilds()
    }

    fun handleGuildCreateOrUpdate(guild: Guild) = dispatcher.schedule { handleGuildCreateOrUpdateInternal(guild) }
    fun handleGuildRemove(guild: Guild) = dispatcher.schedule { handleGuildRemoveInternal(guild) }
    fun handleChannelOrThreadDelete(channel: Channel) = dispatcher.schedule {
        if (channel.isThread()) handleThreadDeleteInternal(channel) else handleChannelDeleteInternal(channel)
    }
    fun handleStageInstanceCreate(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceCreate(stageInstance) }
    fun handleStageInstanceUpdate(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceUpdate(stageInstance) }
    fun handleStageInstanceDelete(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceDelete(stageInstance) }
    fun handleGatewayAuthenticationFailure() = authentication.invalidate()

    /**
     * Ingress for a gateway dispatch. Publishes onto the matching subject in
     * [StoreGatewayConnection]; the listeners installed by [initGatewaySocketListeners] carry it to
     * the stores. Exposed so a test can drive the router without a socket.
     */
    fun handleGatewayDispatch(type: String?, data: Any) = gatewayConnection.handleDispatch(type, data)

    /**
     * Runs [action] on the store thread for every value of [source].
     *
     * `onBackpressureBuffer` is mandatory here: a READY burst or a large guild's voice traffic
     * arrives faster than the store thread drains, and the default backpressure strategy would drop
     * events rather than queue them.
     */
    private fun <T> dispatchSubscribe(source: Observable<T>, name: String, action: (T) -> Unit) {
        source.onBackpressureBuffer().subscribe(
            { value -> dispatcher.schedule { action(value) } },
            { error -> Log.w(GATEWAY_TAG, "gateway listener '$name' failed", error) },
        )
    }

    /**
     * Batched variant: the coalesced list is replayed one value at a time to [action].
     *
     * `gatewayConnection.voiceStateUpdate` is the only batched subject.
     */
    private fun <T> dispatchSubscribeBatched(source: Batched<T>, name: String, action: (T) -> Unit) =
        dispatchSubscribe(source.observe(), name) { batch -> batch.forEach(action) }

    /**
     * Connects every gateway subject to the store that consumes it.
     *
     * Every typed gateway subject with a reconstructed destination is connected here.
     */
    private fun initGatewaySocketListeners() {
        val events = gatewayConnection

        dispatchSubscribe(events.getConnected(), "streamConnected", ::handleGatewayConnectedInternal)
        dispatchSubscribe(events.getConnectionReady(), "streamReady", ::handleGatewayConnectionReadyInternal)
        dispatchSubscribe(events.ready, "streamConnectionOpen", ::handleConnectionOpenInternal)
        dispatchSubscribe(
            events.userSettingsProtoUpdate,
            "streamUserSettingsProtoUpdate",
            mediaFavorites::handleGatewayUpdate,
        )

        // Guilds
        dispatchSubscribe(events.guildCreate, "streamGuildCreate", ::handleGuildCreateOrUpdateInternal)
        dispatchSubscribe(events.guildUpdate, "streamGuildUpdate", ::handleGuildCreateOrUpdateInternal)
        dispatchSubscribe(events.guildDeleted, "streamGuildRemove", ::handleGuildRemoveInternal)
        dispatchSubscribe(events.guildEmojisUpdate, "streamEmojisUpdate") { update ->
            clientDataState.handleEmojiUpdate(update)
            emojiCustom.handleEmojiUpdate(update)
            emojiGuild.handleEmojiUpdate(update)
        }
        dispatchSubscribe(events.guildStickersUpdate, "streamStickersUpdate") { update ->
            clientDataState.handleStickersUpdate(update)
            guildStickers.handleStickerUpdate(update)
        }

        // Guild roles
        dispatchSubscribe(events.guildRoleCreate, "streamGuildRoleAdd") { event ->
            event.role?.let { role ->
                guilds.handleGuildRoleCreateOrUpdate(event.guildId, role)
                channelMembers.handleGuildRoleCreateOrUpdate(event.guildId, role)
            }
            permissions.handleGuildRolesChanged(event.guildId)
            channelsSelected.handlePermissionsChanged(event.guildId)
            voiceChannelSelected.handleGuildRoleCreateOrUpdate()
            stageChannels.handleGuildRoleCreateOrUpdate(event.guildId)
            clientDataState.handleRoleAddOrRemove(event.guildId, event.guildHashes)
        }
        dispatchSubscribe(events.guildRoleUpdate, "streamGuildRoleUpdate") { event ->
            event.role?.let { role ->
                guilds.handleGuildRoleCreateOrUpdate(event.guildId, role)
                channelMembers.handleGuildRoleCreateOrUpdate(event.guildId, role)
            }
            permissions.handleGuildRolesChanged(event.guildId)
            channelsSelected.handlePermissionsChanged(event.guildId)
            voiceChannelSelected.handleGuildRoleCreateOrUpdate()
            stageChannels.handleGuildRoleCreateOrUpdate(event.guildId)
            clientDataState.handleRoleAddOrRemove(event.guildId, event.guildHashes)
        }
        dispatchSubscribe(events.guildRoleDelete, "streamGuildRoleRemove") { event ->
            guilds.handleGuildRoleRemove(event.guildId, event.roleId)
            permissions.handleGuildRolesChanged(event.guildId)
            channelsSelected.handlePermissionsChanged(event.guildId)
            voiceChannelSelected.handleGuildRoleRemove()
            stageChannels.handleGuildRoleCreateOrUpdate(event.guildId)
            clientDataState.handleRoleAddOrRemove(event.guildId, event.guildHashes)
        }

        // Guild membership
        dispatchSubscribe(events.guildMembersAdd, "streamGuildMemberAdd", ::handleGuildMemberAddInternal)
        dispatchSubscribe(events.guildMemberRemove, "streamGuildMemberRemove") { event ->
            guilds.handleGuildMemberRemove(event.guildId, event.user.id)
            channelMembers.handleGuildMemberRemove(event.guildId, event.user.id)
            presences.handleGuildMemberRemove(event.user.id, event.guildId)
            if (event.user.id == users.getMeInternal().id) permissions.handleGuildRolesChanged(event.guildId)
            channelsSelected.handlePermissionsChanged(event.guildId)
            guildMemberCounts.handleGuildMemberRemove(event.guildId)
            stageChannels.handleGuildMemberRemove(event.guildId, event.user.id)
        }
        dispatchSubscribe(events.guildMembersChunk, "streamGuildMemberChunk") { chunk ->
            val members = chunk.members.orEmpty()
            users.handleGuildMembersChunk(members)
            guilds.handleGuildMembersChunk(chunk.guildId, members)
            chunk.presences.orEmpty().forEach { presence -> presences.handlePresenceUpdate(chunk.guildId, presence) }
            if (members.any { member -> member.user.id == users.getMeInternal().id }) {
                permissions.handleGuildRolesChanged(chunk.guildId)
                channelsSelected.handlePermissionsChanged(chunk.guildId)
            }
            members.forEach { member -> channelMembers.handleGuildMemberUpdate(chunk.guildId, member) }
            guildMemberRequester.handleGuildMembersChunk(chunk)
        }
        dispatchSubscribe(events.guildMemberListUpdate, "guildMemberListUpdate", channelMembers::handleGuildMemberListUpdate)

        // Guild administration
        dispatchSubscribe(events.guildBanAdd, "streamBanAdd", bans::handleBanAdd)
        dispatchSubscribe(events.guildBanRemove, "streamBanRemove", bans::handleBanRemove)
        dispatchSubscribe(events.guildIntegrationsUpdate, "streamGuildIntegrationsUpdate", guildIntegrations::handleUpdate)
        dispatchSubscribe(events.guildJoinRequestCreateOrUpdate, "streamGuildJoinRequestCreateOrUpdate") { event ->
            guildJoinRequests.handleGuildJoinRequestCreateOrUpdate(event.guildId, event.request)
        }
        dispatchSubscribe(events.guildJoinRequestDelete, "streamGuildJoinRequestDelete") { event ->
            guildJoinRequests.handleGuildJoinRequestDelete(event.userId, event.guildId)
        }
        dispatchSubscribe(events.guildScheduledEventCreate, "guildScheduledEventCreate",
            guildScheduledEvents::handleGuildScheduledEventCreate)
        dispatchSubscribe(events.guildScheduledEventUpdate, "guildScheduledEventUpdate",
            guildScheduledEvents::handleGuildScheduledEventUpdate)
        dispatchSubscribe(events.guildScheduledEventDelete, "guildScheduledEventDelete",
            guildScheduledEvents::handleGuildScheduledEventDelete)
        dispatchSubscribe(events.guildScheduledEventUserAdd, "guildScheduledEventUserAdd",
            guildScheduledEvents::handleGuildScheduledEventUserAdd)
        dispatchSubscribe(events.guildScheduledEventUserRemove, "guildScheduledEventUserRemove",
            guildScheduledEvents::handleGuildScheduledEventUserRemove)
        dispatchSubscribe(events.guildApplicationCommands, "guildApplicationCommands",
            applicationCommands::handleApplicationCommandsUpdate)
        dispatchSubscribe(events.guildApplicationCommandIndexUpdate, "guildApplicationCommandIndexUpdate") {
            applicationCommands.handleApplicationCommandIndexUpdate(it.guildId)
        }
        dispatchSubscribe(events.applicationCommandAutocompleteResult, "applicationCommandAutocompleteResult",
            applicationCommands::handleApplicationCommandAutocompleteResult)

        // Channels and threads
        dispatchSubscribe(events.channelCreateOrUpdate, "streamChannelCreateOrUpdate", ::handleChannelCreateOrUpdateInternal)
        dispatchSubscribe(events.channelDeleted, "streamChannelDelete", ::handleChannelDeleteInternal)
        dispatchSubscribe(events.channelUnreadUpdate, "streamChannelUnreadUpdate") { update ->
            messagesMostRecent.handleChannelUnreadUpdate(update)
        }
        dispatchSubscribe(events.channelRecipientAdd, "streamGroupDMRecipientAdd") { recipient ->
            users.handleUserUpdated(recipient.user)
            channels.handleGroupDMRecipient(recipient.channelId, recipient.user, recipient.nick, added = true)
        }
        dispatchSubscribe(events.channelRecipientRemove, "streamGroupDMRecipientRemove") { recipient ->
            channels.handleGroupDMRecipient(recipient.channelId, recipient.user, recipient.nick, added = false)
        }
        // THREAD_CREATE / THREAD_UPDATE / THREAD_DELETE used to be routed into the *channel*
        // handlers, so every thread took the channel branch and none of the thread stores fired.
        dispatchSubscribe(events.threadCreateOrUpdate, "streamThreadCreateOrUpdate", ::handleThreadCreateOrUpdateInternal)
        dispatchSubscribe(events.threadDelete, "streamThreadDelete", ::handleThreadDeleteInternal)
        dispatchSubscribe(events.threadListSync, "streamThreadListSync") { sync ->
            channels.handleThreadListSync(sync.guildId, sync.threads)
            threadsJoined.handleThreadListSync(sync)
            threadsActive.handleThreadListSync(sync)
            threadsActiveJoined.handleThreadListSync(sync)
            threadMessages.handleThreadListSync(sync)
            mentions.handleThreadListSync(sync)
            messagesMostRecent.handleThreadListSync(sync)
            permissions.recomputeAllPermissions()
        }
        dispatchSubscribe(events.threadMemberUpdate, "streamThreadMemberUpdate") { update ->
            threadsJoined.handleThreadMemberUpdate(update)
            threadsActiveJoined.handleThreadMemberUpdate(update)
        }
        dispatchSubscribe(events.threadMembersUpdate, "streamThreadMembersUpdate") { update ->
            threadsJoined.handleThreadMembersUpdate(update)
            threadsActiveJoined.handleThreadMembersUpdate(update)
            threadMembers.handleThreadMembersUpdate(update)
            mentions.handleThreadMembersUpdate(update)
            gameParty.handleThreadMembersUpdate(
                update.guildId,
                update.addedMembers.orEmpty().mapNotNull { it.presence },
            )
        }
        dispatchSubscribe(events.threadMemberListUpdate, "streamThreadMemberListUpdate") { update ->
            threadMembers.handleThreadMemberListUpdate(update)
            gameParty.handleThreadMemberListUpdate(
                update.guildId,
                update.members.orEmpty().mapNotNull { it.presence },
            )
        }
        dispatchSubscribe(events.forumUnreads, "streamForumUnreads", forumPostReadStates::handleForumUnreads)

        // Users, relationships and presence
        dispatchSubscribe(events.userUpdate, "streamUserUpdated") { user ->
            users.handleUserUpdated(user)
            userProfiles.updateUser(user)
            mfa.handleUserUpdated(user)
        }
        dispatchSubscribe(events.userNoteUpdate, "handleUserNoteUpdated", userNotes::handleNoteUpdate)
        dispatchSubscribe(events.relationshipAdd, "streamRelationshipAdd") { relationship ->
            users.handleUserRelationshipAdd(relationship)
            userRelationships.handleRelationshipAdd(relationship)
        }
        // An update is an upsert of the same shape; it is what carries a friend nickname change.
        dispatchSubscribe(events.relationshipUpdate, "streamRelationshipUpdate") { relationship ->
            users.handleUserRelationshipAdd(relationship)
            userRelationships.handleRelationshipAdd(relationship)
        }
        dispatchSubscribe(events.relationshipRemove, "streamRelationshipRemove", userRelationships::handleRelationshipRemove)
        dispatchSubscribe(events.friendSuggestionCreate, "friendSuggestionCreate", friendSuggestions::handleFriendSuggestionCreate)
        dispatchSubscribe(events.friendSuggestionDelete, "friendSuggestionDelete", friendSuggestions::handleFriendSuggestionDelete)
        dispatchSubscribe(events.presenceUpdate, "streamPresenceUpdate") { presence ->
            users.handlePresenceUpdate(presence)
            presences.handlePresenceUpdate(presence.guildId ?: 0L, presence)
            gameParty.handlePresenceUpdate(presence)
        }
        dispatchSubscribe(events.presenceReplace, "streamPresenceReplace") { replaced ->
            replaced.forEach(users::handlePresenceUpdate)
            presences.handlePresenceReplace(replaced)
            gameParty.handlePresenceReplace(replaced)
        }
        dispatchSubscribe(events.sessionsReplace, "streamSessionsReplace", presences::handleSessionsReplace)
        dispatchSubscribe(events.typingStart, "streamTypingStart") { typing ->
            typing.member?.let { member -> guilds.handleGuildMember(member, typing.guildId) }
            usersTyping.handleTypingStart(typing)
        }

        // Messages
        dispatchSubscribe(events.messageCreate, "streamMessageCreate", ::handleMessageCreateInternal)
        dispatchSubscribe(events.messageUpdate, "streamMessageUpdate") { message ->
            users.handleMessageCreateOrUpdate(DomainMessage(message))
            messages.handleMessageUpdate(message)
            pinnedMessages.handleMessageUpdate(message)
            messageState.handleMessageUpdate(message)
            messageReplies.handleMessageUpdate(message)
            threadMessages.handleMessageUpdate(message)
            forumPostMessages.handleMessageUpdate(message)
            mentions.handleMessageCreateOrUpdate(message)
            applicationInteractions.handleMessageUpdate(message)
        }
        dispatchSubscribe(events.messageDelete, "streamMessageDelete") { deleted ->
            messages.handleMessageDelete(deleted)
            pinnedMessages.handleMessageDeleteBulk(deleted.channelId, deleted.messageIds.orEmpty())
            pendingReplies.handleMessageDelete(deleted)
            messageState.handleMessageDelete(deleted)
            messageReplies.handleMessageDelete(deleted)
            threadMessages.handleMessageDelete(deleted)
            forumPostMessages.handleMessageDelete(deleted)
            mentions.handleMessageDeleted(deleted)
        }
        dispatchSubscribe(events.messageAck, "streamMessageAck") { readState ->
            mentions.handleMessageAck(readState)
            messageAck.handleMessageAck(readState)
            forumPostReadStates.handleMessageAck(readState)
            clientDataState.handleMessageAck(readState)
        }
        dispatchSubscribe(events.messageReactionAdd, "streamReactionAdd") { reaction ->
            messages.handleReaction(reaction, true, users.getMeInternal().id)
            messageReactions.handleReactionAdd(reaction)
            forumPostMessages.handleReactionAdd(reaction)
        }
        dispatchSubscribe(events.messageReactionRemove, "streamReactionRemove") { reaction ->
            messages.handleReaction(reaction, false, users.getMeInternal().id)
            messageReactions.handleReactionRemove(reaction)
            forumPostMessages.handleReactionRemove(reaction)
        }
        dispatchSubscribe(events.messageReactionRemoveAll, "streamMessageRemoveAll") { reaction ->
            messages.handleReactionRemoveAll(reaction.channelId, reaction.messageId)
            messageReactions.handleReactionRemoveAll(reaction.messageId)
            forumPostMessages.handleReactionRemoveAll(reaction)
        }
        dispatchSubscribe(events.messageReactionRemoveEmoji, "streamMessageRemoveEmoji") { reaction ->
            messages.handleReactionRemoveEmoji(reaction.channelId, reaction.messageId, reaction.emoji)
            messageReactions.handleReactionRemoveEmoji(reaction.messageId, reaction.emoji)
            forumPostMessages.handleReactionRemoveEmoji(reaction)
        }

        // Voice, calls and streams
        dispatchSubscribeBatched(events.voiceStateUpdate, "streamVoiceStateUpdate") { voiceState ->
            voiceState.member?.let { member ->
                users.handleGuildMemberAdd(member)
                guilds.handleGuildMember(member, voiceState.guildId)
                channelMembers.handleGuildMemberUpdate(voiceState.guildId, member)
            }
            voiceStates.handleVoiceStateUpdate(voiceState)
            if (::voiceTransport.isInitialized) voiceTransport.storeRtcConnection.handleVoiceStateUpdate(voiceState)
            stageChannelSelfPresence.handleVoiceStateUpdate(voiceState)
            voiceChannelSelected.handleVoiceStateUpdates(voiceState)
            videoStreams.handleVoiceStateUpdates(voiceState)
            applicationStreaming.handleVoiceStateUpdate(voiceState)
        }
        dispatchSubscribe(events.voiceServerUpdate, "streamVoiceServerUpdate") { server ->
            if (::voiceTransport.isInitialized) voiceTransport.storeRtcConnection.handleVoiceServerUpdate(server)
        }
        dispatchSubscribe(events.callCreateOrUpdate, "streamCallCreateOrUpdate") { call ->
            calls.handleCallCreateOrUpdate(call)
            callsIncoming.handleCallCreateOrUpdate(call)
        }
        dispatchSubscribe(events.callDelete, "streamCallDelete") { call ->
            calls.handleCallDelete(call)
            callsIncoming.handleCallDelete(call)
        }
        dispatchSubscribe(events.streamCreate, "streamCreate") { event ->
            if (::voiceTransport.isInitialized) voiceTransport.storeStreamRtcConnection.handleStreamCreate(event)
            applicationStreaming.handleStreamCreate(event)
        }
        dispatchSubscribe(events.streamUpdate, "streamUpdate", applicationStreaming::handleStreamUpdate)
        dispatchSubscribe(events.streamDelete, "streamDelete") { event ->
            if (::voiceTransport.isInitialized) voiceTransport.storeStreamRtcConnection.handleStreamDelete()
            applicationStreaming.handleStreamDelete(event)
        }
        dispatchSubscribe(events.streamServerUpdate, "streamServerUpdate") { event ->
            if (::voiceTransport.isInitialized) voiceTransport.storeStreamRtcConnection.handleStreamServerUpdate(event)
        }

        // Interactions
        dispatchSubscribe(events.interactionCreate, "interactionCreate", applicationInteractions::handleInteractionCreate)
        dispatchSubscribe(events.interactionSuccess, "interactionSuccess", applicationInteractions::handleInteractionSuccess)
        dispatchSubscribe(events.interactionFailure, "interactionFailure", applicationInteractions::handleInteractionFailure)
        dispatchSubscribe(events.interactionModalCreate, "interactionModalCreate",
            applicationInteractions::handleInteractionModalCreate)
        dispatchSubscribe(events.embeddedActivityInboundUpdate, "embeddedActivityInboundUpdate",
            embeddedActivities::handleEmbeddedActivityInboundUpdate)
        dispatchSubscribe(events.embeddedActivityUpdateV2, "embeddedActivityUpdateV2",
            embeddedActivities::handleEmbeddedActivityUpdateV2)

        // User-scoped side channels. `userConnectionUpdate` is not subscribed here: it is handed to
        // StoreUserConnections as its `connectionUpdates` source, which re-fetches on each signal.
        dispatchSubscribe(events.userRequiredActionUpdate, "streamUserRequiredActionUpdate",
            userRequiredActions::handleUserRequiredActionUpdate)
        dispatchSubscribe(events.authSessionChange, "authSessionChange",
            authentication::handleAuthSessionChange)
        // Without this the session gate never reaches routing: agreements, verified email and
        // verified phone all resolve to `MainRoute.LoggedIn` and the user lands in an app the
        // server will refuse.
        userRequiredActions.observeUserRequiredAction()
            .distinctUntilChanged()
            .subscribe(navigation::updateUserRequiredAction)
        dispatchSubscribe(events.userSubscriptionsUpdate, "userSubscriptionsUpdate") {
            subscriptions.handleUserSubscriptionsUpdate()
        }
        dispatchSubscribe(events.userPaymentSourcesUpdate, "userPaymentSourcesUpdate") {
            paymentSources.handleUserPaymentSourcesUpdate()
        }

        // Settings
        dispatchSubscribe(events.userSettingsUpdate, "streamUserSettingsUpdate") { settings ->
            userSettings.handleUserSettingsUpdate(settings)
            userSettingsSystem.handleUserSettingsUpdate(settings)
            presences.handleUserSettingsUpdate(settings)
            settings.guildFolders?.let { folders -> guildFolders = folders }
            updateSortedGuilds()
        }
        dispatchSubscribe(events.userGuildSettingsUpdate, "streamGuildSettingUpdated") { settings ->
            guildSettings.handleGuildSettingUpdated(listOf(settings))
            clientDataState.handleGuildSettingUpdated(settings)
            updateSortedGuilds()
        }

        // Stage instances
        dispatchSubscribe(events.stageInstanceCreate, "stageInstanceCreate") { instance ->
            stageInstances.handleStageInstanceCreate(instance)
            stageChannelSelfPresence.handleStageInstanceCreate()
        }
        dispatchSubscribe(events.stageInstanceUpdate, "stageInstanceUpdate") { instance ->
            stageInstances.handleStageInstanceUpdate(instance)
            stageChannelSelfPresence.handleStageInstanceUpdate()
        }
        dispatchSubscribe(events.stageInstanceDelete, "stageInstanceDelete") { instance ->
            stageInstances.handleStageInstanceDelete(instance)
            stageChannelSelfPresence.handleStageInstanceDelete()
        }
    }

    @StoreThread
    private fun handleGatewayConnectedInternal(connected: Boolean) {
        messages.handleConnected(connected)
        messagesLoader.handleConnected(connected)
        analytics.handleConnected(connected)
        connectivity.handleConnected(connected)
        connectionOpen.handleConnected(connected)
        channels.handleConnected(connected)
        if (connected && ::messageSendCoordinator.isInitialized) messageSendCoordinator.handleConnected()
    }

    @StoreThread
    private fun handleGatewayConnectionReadyInternal(ready: Boolean) {
        if (::voiceTransport.isInitialized) voiceTransport.storeRtcConnection.handleConnectionReady(ready)
        connectivity.handleConnectionReady(ready)
        guildSubscriptions.handleConnectionReady(ready)
        calls.handleConnectionReady(ready)
        guildMemberRequester.handleConnectionReady(ready)
        applicationCommands.handleConnectionReady(ready)
        applicationInteractions.handleConnectionReady(ready)
        spotify.handleConnectionReady(ready)
        mediaFavorites.handleConnectionReady(ready)
    }

    @StoreThread
    private fun handleGuildMemberAddInternal(member: GuildMember) {
        users.handleGuildMemberAdd(member)
        guilds.handleGuildMember(member, preserveProfile = true)
        permissions.handleGuildMemberAdd(member)
        channelsSelected.handlePermissionsChanged(member.guildId)
        channelMembers.handleGuildMemberUpdate(member.guildId, member)
        guildMemberCounts.handleGuildMemberAdd(member)
        stageChannels.handleGuildMemberAdd(member)
        // No-ops unless the member is us: only our own role changes affect the allowed emoji set.
        emojiCustom.handleGuildMemberAdd(member)
        mentions.handleGuildMemberAdd(member)
        voiceChannelSelected.handleGuildMemberAdd(member.user.id)
    }

    @StoreThread
    private fun handleMessageCreateInternal(message: ApiMessage) {
        val domain = DomainMessage(message)
        message.nonce?.let { nonce ->
            if (::messageSendCoordinator.isInitialized) messageSendCoordinator.reconcileFromGateway(nonce)
            reconcileUpload(nonce)
        }
        mentions.handleMessageCreateOrUpdate(message)
        users.handleMessageCreateOrUpdate(domain)
        usersTyping.handleMessageCreate(domain.channelId, domain.author?.id)
        applicationInteractions.handleMessageCreate(message)
        messages.handleMessageCreate(domain)
        messagesMostRecent.handleMessageCreate(domain)
        messageAck.handleMessageCreate(message)
        messageReplies.handleMessageCreate(message)
        maybeNotify(domain)
        clientDataState.handleMessageCreate(message)
        threadMessages.handleMessageCreate(message)
        forumPostMessages.handleMessageCreate(message)
        forumPostReadStates.handleMessageCreate(message)
        message.thread?.let(channels::handleThreadCreateOrUpdate)
        if (domain.author?.id == users.getMeInternal().id) slowMode.onMessageSent(domain.channelId)
    }

    /**
     * THREAD_CREATE / THREAD_UPDATE.
     *
     * Split out from [handleChannelCreateOrUpdateInternal]: routing threads through the channel
     * handler meant none of the thread stores ever saw a thread event.
     */
    @StoreThread
    private fun handleThreadCreateOrUpdateInternal(thread: Channel) {
        users.handleChannelCreated(thread)
        channels.handleChannelOrThreadCreateOrUpdate(thread)
        channelsSelected.handleChannelOrThreadCreateOrUpdate(thread)
        permissions.handleChannelOrThreadCreateOrUpdate(thread)
        voiceChannelSelected.handleChannelOrThreadCreateOrUpdate()
        mentions.handleChannelOrThreadCreateOrUpdate(thread)
        messagesMostRecent.handleThreadCreateOrUpdate(thread)
        messageAck.handleThreadCreateOrUpdate(thread)
        clientDataState.handleChannelCreateOrUpdateOrDelete(thread)
        threadsJoined.handleThreadCreateOrUpdate(thread)
        threadsActive.handleThreadCreateOrUpdate(thread)
        threadsActiveJoined.handleThreadCreateOrUpdateOrDelete(thread)
        threadMembers.handleThreadCreateOrUpdate(thread)
        threadMessages.handleThreadCreateOrUpdate(thread)
        forumPostMessages.handleThreadCreateOrUpdate(thread)
        forumPostReadStates.handleThreadCreateOrUpdate(thread)
    }

    /** THREAD_DELETE. See [handleThreadCreateOrUpdateInternal]. */
    @StoreThread
    private fun handleThreadDeleteInternal(thread: Channel) {
        channels.handleChannelOrThreadDelete(thread)
        permissions.handleChannelOrThreadDelete(thread)
        channelsSelected.handleChannelOrThreadDelete(thread)
        stageInstances.handleChannelDelete(thread)
        clientDataState.handleChannelCreateOrUpdateOrDelete(thread)
        threadsJoined.handleThreadDelete(thread)
        threadsActive.handleThreadDelete(thread)
        threadsActiveJoined.handleThreadCreateOrUpdateOrDelete(thread)
        threadMembers.handleThreadDelete(thread)
        threadMessages.handleThreadDelete(thread)
        forumPostMessages.handleThreadDelete(thread)
        forumPostReadStates.handleThreadDelete(thread)
        mentions.handleChannelOrThreadDelete(thread)
        voiceChannelSelected.handleChannelOrThreadDelete()
    }
    fun handlePreLogout() = dispatcher.schedule { handlePreLogoutInternal() }

    fun handleStreamTargeted(streamKey: String): Unit = dispatcher.schedule {
        applicationStreaming.handleStreamTargeted(streamKey)
        voiceChannelSelected.handleStreamTargeted(streamKey)
    }

    fun handleLocalMessageDelete(message: DomainMessage) = dispatcher.schedule {
        applicationInteractions.handleLocalMessageDelete(message)
    }

    private fun handleMessageSendUpdate(update: MessageSendCoordinator.Update) = dispatcher.schedule {
        when (update) {
            is MessageSendCoordinator.Update.Optimistic -> {
                if (update.requestId.startsWith("edit:")) {
                    messages.handleMessageUpdate(update.message.channelId, update.message.id) { update.message }
                } else {
                    messages.handleLocalMessageCreate(update.message.channelId, update.requestId, update.message)
                    scheduleBackgroundMessageSend(update.message.localAttachments.orEmpty().all { attachment ->
                        runCatching { android.net.Uri.parse(attachment.uriString) }.isSuccess
                    })
                }
            }
            is MessageSendCoordinator.Update.Reconciled -> {
                messages.handleLocalMessageDelete(update.message.channelId, update.requestId)
                messages.handleMessageCreate(update.message)
                messagesMostRecent.handleMessageCreate(update.message)
                messageUploads.onSuccess(update.requestId)
            }
            is MessageSendCoordinator.Update.Failed -> {
                if (update.requestId.startsWith("edit:")) {
                    messages.handleMessageUpdate(update.message.channelId, update.message.id) { update.message }
                    chatInputEditing.start(update.message)
                } else {
                    messages.localMessagesHolder.deleteMessage(update.message.channelId, update.requestId)
                    messages.handleLocalMessageCreate(update.message.channelId, update.requestId, update.message)
                }
                messageUploads.onFailure(update.requestId)
            }
            is MessageSendCoordinator.Update.Cancelled -> {
                messages.localMessagesHolder.getAll().forEach { (channelId, channelMessages) ->
                    if (channelMessages.any { it.nonce == update.requestId }) {
                        messages.handleLocalMessageDelete(channelId, update.requestId)
                    }
                }
                messageUploads.onCancel(update.requestId)
            }
        }
    }

    private fun configureSocialUi() {
        // The friends list and the add-friend screens own their own state and REST calls now
        // (`FriendsListViewModel`, `WidgetFriendsAddById`), so nothing is installed for them here.
        WidgetSettingsBlockedUsers.stateSource = {
            Observable.combineLatest(users.observeAllUsers(), userRelationships.observe()) { allUsers, relationships ->
                BlockedUsersBuilder.build(relationships.mapNotNull { (userId, type) ->
                    allUsers[userId]?.let { user ->
                        FriendRelationshipUi(
                            friendUser(user),
                            when (type) {
                                ModelUserRelationship.TYPE_BLOCKED -> FriendRelationship.BLOCKED
                                ModelUserRelationship.TYPE_FRIEND -> FriendRelationship.FRIEND
                                ModelUserRelationship.TYPE_INVITE_INCOMING -> FriendRelationship.INCOMING
                                else -> FriendRelationship.OUTGOING
                            },
                        )
                    }
                })
            }
        }
        WidgetSettingsBlockedUsers.onUnblock = { removeRelationship(it.id) }

        // The user sheet now owns its own state and REST calls through `WidgetUserSheetViewModel`;
        // only the navigation it cannot perform itself is installed here.
        WidgetUserSheet.onMessage = ::openDmInternal
        WidgetUserMutualGuilds.stateSource = { userId ->
            userProfiles.fetchProfile(userId, withMutualGuilds = true)
            Observable.combineLatest(
                userProfiles.observeUserProfile(userId),
                guilds.observeGuilds(),
                users.observeUser(userId),
                guilds.observeComputed(),
            ) { profile, allGuilds, user, allMembers ->
                if (profile == StoreUserProfile.EMPTY_PROFILE) WidgetUserMutualGuilds.State.Loading else
                    WidgetUserMutualGuilds.State.Loaded(
                        user?.username,
                        profile.mutualGuilds.mapNotNull { reference ->
                            allGuilds[reference.id]?.let { guild ->
                                WidgetUserMutualGuilds.MutualGuild(
                                    guild, user, allMembers[reference.id]?.get(userId), reference.nick,
                                )
                            }
                        },
                    )
            }
        }
        WidgetUserMutualFriends.stateSource = { userId ->
            restApi.getRelationships(userId).subscribeOn(Schedulers.io()).flatMap { relationships ->
                val ids = relationships.map(ModelUserRelationship::id)
                users.fetchUsers(ids)
                Observable.combineLatest(
                    users.observeAllUsers(),
                    presences.observeAllPresences(),
                    users.observeUser(userId),
                ) { allUsers, allPresences, target ->
                    WidgetUserMutualFriends.State.Loaded(
                        target?.username,
                        ids.mapNotNull { id -> allUsers[id]?.let { WidgetUserMutualFriends.Friend(it, allPresences[id]) } },
                    ) as WidgetUserMutualFriends.State
                }
            }.onErrorReturn { WidgetUserMutualFriends.State.Error(it.message) }
        }

        WidgetUserStatusSheet.stateSource = {
            userSettings.observeCustomStatus().map { status ->
                val state = if (status == ModelCustomStatusSetting.CLEAR) {
                    UserStatusPresenceCustomView.ViewState.WithPlaceholder(ModelEmojiUnicode(surrogates = "💬"))
                } else {
                    UserStatusPresenceCustomView.ViewState.WithStatus(
                        status.emojiName?.let { UserStatusPresenceCustomView.Emoji(status.emojiId?.toString(), it, false) },
                        status.text,
                    )
                }
                WidgetUserStatusSheet.State(state)
            }
        }
        WidgetUserStatusSheet.onStatusSelected = { status ->
            restApi.updateUserSettingsStatus(RestAPIParams.UserSettingsStatus(status))
                .subscribeOn(Schedulers.io()).subscribe(::handleUserSettingsUpdate, {})
        }
        WidgetUserStatusSheet.onClearCustomStatus = {
            restApi.updateUserSettingsCustomStatus(RestAPIParams.UserSettingsCustomStatus.clear())
                .subscribeOn(Schedulers.io()).subscribe(::handleUserSettingsUpdate, {})
        }
        WidgetUserSetCustomStatus.initialState = {
            val current = userSettings.getCustomStatus()
            WidgetUserSetCustomStatus.Form(
                text = current.text.orEmpty(),
                expiration = if (current.expiresAt == null) WidgetUserSetCustomStatus.Expiration.NEVER
                else WidgetUserSetCustomStatus.Expiration.TOMORROW,
                emojiUri = current.emojiName,
            )
        }
        WidgetUserSetCustomStatus.onSave = { submission ->
            val value = if (submission.text == null && submission.emojiUri == null) null else ModelCustomStatusSetting(
                submission.text, null, submission.emojiUri, submission.expiresAtUtc,
            )
            val body = value?.let(RestAPIParams.UserSettingsCustomStatus::set)
                ?: RestAPIParams.UserSettingsCustomStatus.clear()
            restApi.updateUserSettingsCustomStatus(body).subscribeOn(Schedulers.io())
                .subscribe(::handleUserSettingsUpdate, {})
        }
        WidgetUserSetCustomStatus.onClear = WidgetUserStatusSheet.onClearCustomStatus
    }

    private fun friendUser(user: com.discord.models.user.User) = FriendUserUi(
        user.id,
        user.username,
        user.discriminator,
        user.avatar?.let { "https://cdn.discordapp.com/avatars/${user.id}/$it.webp?size=96" },
    )

    private fun blockUser(userId: Long) {
        restApi.addRelationship(
            userId, RestAPIParams.UserRelationship(type = ModelUserRelationship.TYPE_BLOCKED),
            relationshipContext("User Profile"),
        ).subscribeOn(Schedulers.io()).subscribe({}, {}, {
            dispatcher.schedule { userRelationships.handleRelationshipAdd(
                ModelUserRelationship(userId, ModelUserRelationship.TYPE_BLOCKED),
            ) }
        })
    }

    private fun removeRelationship(userId: Long) {
        restApi.removeRelationship(userId, relationshipContext("User Profile"))
            .subscribeOn(Schedulers.io()).subscribe({}, {}, {
                dispatcher.schedule { userRelationships.handleRelationshipRemove(ModelUserRelationship(userId)) }
            })
    }

    private fun openDmInternal(userId: Long) = createPrivateChannelInternal(listOf(userId)) {}

    private fun createPrivateChannelInternal(recipientIds: List<Long>, completed: (Result<Channel>) -> Unit) {
        val recipients = recipientIds.distinct()
        if (recipients.isEmpty() || recipients.any { it <= 0L }) {
            completed(Result.failure(IllegalArgumentException("A private channel requires valid recipients")))
            return
        }
        restApi.userCreateChannel(RestAPIParams.CreateChannel(recipients)).subscribeOn(Schedulers.io()).subscribe(
            { channel -> dispatcher.schedule {
                channels.handleChannelOrThreadCreateOrUpdate(channel)
                guildSelected.handleGuildSelected(0)
                lurking.handleGuildSelected(0)
                channelsSelected.handleChannelSelected(0, channel.id)
                navigation.setNavigationPanelAction(StoreNavigation.PanelAction.CLOSE)
                completed(Result.success(channel))
            } },
            { completed(Result.failure(it)) },
        )
    }

    private fun relationshipContext(location: String): String = android.util.Base64.encodeToString(
        "{\"location\":\"$location\"}".toByteArray(), android.util.Base64.NO_WRAP,
    )

    private fun callContext(messageId: Long): String = android.util.Base64.encodeToString(
        "{\"message_id\":$messageId}".toByteArray(), android.util.Base64.NO_WRAP,
    )

    private fun scheduleBackgroundMessageSend(canReplayAttachments: Boolean) {
        if (!::applicationContext.isInitialized) return
        val request = OneTimeWorkRequest.Builder(BackgroundMessageSendWorker::class.java)
            .setInitialDelay(120, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.Data.Builder().putBoolean(
                BackgroundMessageSendWorker.KEY_CAN_REPLAY_ATTACHMENTS, canReplayAttachments,
            ).build())
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "BACKGROUND_MESSAGE_SENDING", ExistingWorkPolicy.REPLACE, request,
        )
    }

    private fun scheduleAckWorker(channelId: Long, messageId: Long) {
        if (!::applicationContext.isInitialized) return
        val request = OneTimeWorkRequest.Builder(MessageAckWorker::class.java)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.Data.Builder()
                .putLong(MessageAckWorker.KEY_CHANNEL_ID, channelId)
                .putLong(MessageAckWorker.KEY_MESSAGE_ID, messageId)
                .putInt(MessageAckWorker.KEY_AUTH_GENERATION, authGeneration)
                .build())
            .addTag("MESSAGE_ACK")
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "MESSAGE_ACK_$channelId", ExistingWorkPolicy.REPLACE, request,
        )
    }

    private fun cancelActiveUploads() {
        preprocessingUploads.values.forEach(Subscription::unsubscribe)
        preprocessingUploads.clear()
        activeUploads.values.forEach { (subscription, payload) ->
            subscription.unsubscribe()
            payload.close()
        }
        val nonces = activeUploads.keys.toList()
        activeUploads.clear()
        if (::applicationContext.isInitialized) {
            failedUploads.values.forEach { cleanupProcessedAttachments(applicationContext, it.attachments) }
        }
        failedUploads.clear()
        if (nonces.isNotEmpty()) dispatcher.schedule {
            nonces.forEach(messageUploads::onCancel)
            messageUploads.clear()
        }
    }

    private fun sendUploads(context: Context, request: WidgetChatInput.UploadRequest): Boolean {
        if (request.attachments.isEmpty()) return false
        val nonce = uploadNonceGenerator.nonce().toString()
        val generation = authGeneration
        val localAttachments = request.attachments.map {
            com.discord.api.message.LocalAttachment(it.id, it.uri.toString(), it.displayName)
        }
        dispatcher.schedule {
            messages.handleLocalMessageCreate(
                request.channelId, nonce,
                DomainMessage(
                    id = nonce.toLong(), channelId = request.channelId,
                    guildId = channels.getChannel(request.channelId)?.guildId?.takeUnless { it == 0L },
                    content = request.content, nonce = nonce, type = -1, hasLocalUploads = true,
                    localAttachments = localAttachments,
                    messageReference = request.pendingReply?.messageReference,
                ),
            )
            messageUploads.onPreprocessing(
                nonce, request.attachments.size,
                request.attachments.singleOrNull()?.displayName, null,
            )
            scheduleBackgroundMessageSend(localAttachments.all { android.net.Uri.parse(it.uriString).scheme == "content" })
        }
        val preprocessing = Schedulers.io().createWorker().schedule {
            if (generation != authGeneration) return@schedule
            val attachments = runCatching {
                UploadAttachmentPreprocessor.compressImages(
                    context,
                    request.attachments.map { UploadAttachment.from(context.contentResolver, it) },
                )
            }.getOrElse {
                dispatcher.schedule {
                    messages.handleLocalMessageDelete(request.channelId, nonce)
                    messages.handleLocalMessageCreate(request.channelId, nonce, DomainMessage(
                        id = nonce.toLong(), channelId = request.channelId, content = request.content,
                        nonce = nonce, type = -3, hasLocalUploads = true, localAttachments = localAttachments,
                    ))
                    messageUploads.onFailure(nonce)
                }
                return@schedule
            }
            if (generation == authGeneration) startUpload(
                context, request.channelId, request.content,
                request.pendingReply?.messageReference, attachments, nonce = nonce,
            )
        }
        preprocessingUploads[nonce] = preprocessing
        return true
    }

    private fun startUpload(
        context: Context,
        channelId: Long,
        content: String,
        reference: MessageReference?,
        attachments: List<UploadAttachment>,
        nonce: String,
    ) {
        val body = RestAPIParams.Message(
            content = content.takeUnless(String::isBlank),
            nonce = nonce,
            messageReference = reference?.let {
                RestAPIParams.Message.MessageReference(it.guildId, channelId, it.messageId)
            },
        )
        val upload = runCatching {
            MessageUploadPayloadBuilder.build(context.contentResolver, Gson().toJson(body), attachments)
        }.getOrElse {
            dispatcher.schedule {
                messages.handleLocalMessageDelete(channelId, nonce)
                messages.handleLocalMessageCreate(channelId, nonce, DomainMessage(
                    id = nonce.toLong(), channelId = channelId, content = content, nonce = nonce,
                    type = -2, hasLocalUploads = true,
                    localAttachments = attachments.map(UploadAttachment::toLocalAttachment),
                ))
                messageUploads.onFailure(nonce)
            }
            return
        }
        val optimistic = DomainMessage(
            id = nonce.toLong(), channelId = channelId,
            guildId = channels.getChannel(channelId)?.guildId?.takeUnless { it == 0L },
            content = content, nonce = nonce, type = -1, hasLocalUploads = true,
            localAttachments = attachments.map(UploadAttachment::toLocalAttachment),
            messageReference = reference,
        )
        dispatcher.schedule {
            messages.handleLocalMessageDelete(channelId, nonce)
            messages.handleLocalMessageCreate(channelId, nonce, optimistic)
            messageUploads.onPreprocessing(nonce, attachments.size, attachments.singleOrNull()?.fileName, attachments.singleOrNull()?.mimeType)
            messageUploads.bindUpload(nonce, upload.uploads)
        }
        preprocessingUploads.remove(nonce)
        upload.aggregateProgress.subscribe { progress -> dispatcher.schedule { messageUploads.onProgress(nonce, progress) } }
        val requestSubscription = restApi
            .sendMessage(channelId, PayloadJSON(body), upload.uploads.map { it.part }.toTypedArray(), null)
            .subscribeOn(Schedulers.io())
            .subscribe(
            { apiMessage ->
                activeUploads.remove(nonce)
                failedUploads.remove(nonce)
                dispatcher.schedule {
                    messages.handleLocalMessageDelete(channelId, nonce)
                    messages.handleMessageCreate(DomainMessage(apiMessage))
                    messagesMostRecent.handleMessageCreate(apiMessage)
                    messageUploads.onSuccess(nonce)
                }
                upload.close()
                cleanupProcessedAttachments(context, attachments)
            },
            { throwable ->
                activeUploads.remove(nonce)
                val error = RestApiError.from(throwable)
                val failedType = if (error.code == 200000) -8 else -2
                if (failedType != -8) {
                    failedUploads[nonce] = FailedUpload(channelId, content, reference, attachments)
                }
                dispatcher.schedule {
                    messages.handleLocalMessageDelete(channelId, nonce)
                    messages.handleLocalMessageCreate(channelId, nonce, optimistic.copy(type = failedType))
                    messageUploads.onFailure(nonce)
                }
                upload.close()
                if (failedType == -8) cleanupProcessedAttachments(context, attachments)
            },
        )
        activeUploads[nonce] = requestSubscription to upload
    }

    private fun cleanupProcessedAttachments(context: Context, attachments: List<UploadAttachment>) {
        attachments.forEach { attachment ->
            val path = attachment.uri.path ?: return@forEach
            if (!attachment.permissionPersisted && attachment.uri.scheme == "file" &&
                path.startsWith(context.cacheDir.absolutePath)) runCatching { java.io.File(path).delete() }
        }
    }

    private fun cancelUpload(nonce: String) {
        preprocessingUploads.remove(nonce)?.unsubscribe()
        activeUploads.remove(nonce)?.let { (subscription, payload) ->
            subscription.unsubscribe()
            payload.close()
        }
        dispatcher.schedule {
            messages.localMessagesHolder.getAll().forEach { (channelId, channelMessages) ->
                if (channelMessages.any { it.nonce == nonce }) messages.handleLocalMessageDelete(channelId, nonce)
            }
            messageUploads.onCancel(nonce)
        }
        failedUploads.remove(nonce)?.let { cleanupProcessedAttachments(applicationContext, it.attachments) }
    }

    private fun reconcileUpload(nonce: String) {
        preprocessingUploads.remove(nonce)?.unsubscribe()
        activeUploads.remove(nonce)?.let { (subscription, payload) ->
            subscription.unsubscribe()
            payload.close()
        }
        failedUploads.remove(nonce)?.let {
            if (::applicationContext.isInitialized) cleanupProcessedAttachments(applicationContext, it.attachments)
        }
        dispatcher.schedule { messageUploads.onSuccess(nonce) }
    }

    private fun cancelMessageSendInternal(nonce: String) {
        messageSendCoordinator.cancel(nonce)
        cancelUpload(nonce)
    }

    private fun retryFailedUpload(message: DomainMessage) {
        val nonce = message.nonce ?: return
        val retained = failedUploads.remove(nonce)
        if (retained != null) {
            dispatcher.schedule { messages.handleLocalMessageDelete(retained.channelId, nonce) }
            startUpload(applicationContext, retained.channelId, retained.content, retained.reference, retained.attachments, nonce = nonce)
            return
        }
        val local = message.localAttachments.orEmpty()
        if (local.isEmpty() || !::applicationContext.isInitialized) return
        Schedulers.io().createWorker().schedule {
            val attachments = runCatching { local.map { it.toUploadAttachment(applicationContext.contentResolver) } }
                .getOrElse { return@schedule }
            dispatcher.schedule { message.nonce?.let { messages.handleLocalMessageDelete(message.channelId, it) } }
            startUpload(
                applicationContext, message.channelId, message.content.orEmpty(), message.messageReference,
                attachments, nonce = nonce,
            )
        }
    }

    private fun selectGuildInternal(guildId: Long) = dispatcher.schedule {
        guildSelected.handleGuildSelected(guildId)
        channelsSelected.handleGuildSelected()
        calls.handleGuildSelect(guildId)
        guildSubscriptions.handleGuildSelect(guildId)
        lurking.handleGuildSelected(guildId)
        nux.handleGuildSelected(guildId)
        inviteSettings.handleGuildSelected(guildId)
    }

    private fun selectChannelInternal(guildId: Long, channelId: Long) = dispatcher.schedule {
        if (guildSelected.getSelectedGuildIdInternal() != guildId) {
            guildSelected.handleGuildSelected(guildId)
            channelsSelected.handleGuildSelected()
            calls.handleGuildSelect(guildId)
            guildSubscriptions.handleGuildSelect(guildId)
            lurking.handleGuildSelected(guildId)
            nux.handleGuildSelected(guildId)
            inviteSettings.handleGuildSelected(guildId)
        }
        dispatcher.schedule {
            channelsSelected.handleChannelSelected(guildId, channelId)
            navigation.setNavigationPanelAction(StoreNavigation.PanelAction.CLOSE)
        }
    }

    @StoreThread
    private fun handleChannelSelected(selected: StoreChannelsSelected.ResolvedSelectedChannel) {
        val channelId = selected.id
        channelConversions.handleChannelSelected(channelId)
        calls.handleChannelSelect(channelId)
        mentions.handleChannelSelected(channelId)
        messages.handleChannelSelected(channelId)
        messagesLoader.handleChannelSelected(selected.maybeChannel)
        messageState.handleChannelSelected()
        messageAck.handleChannelSelected()
        notifications.handleChannelSelected(channelId)
        expressionSuggestions.handleChannelSelected()
        applicationInteractions.handleChannelSelected()
    }

    private fun updateMemberPanelInternal(channel: Channel?, open: Boolean) = dispatcher.schedule {
        if (channel == null || channel.guildId == 0L) return@schedule
        if (open) guildSubscriptions.handlePanelOpened(channel.guildId, channel.id)
        else guildSubscriptions.handlePanelClosed(channel.guildId, channel.id)
    }

    private fun updateMemberRangeInternal(channel: Channel, range: IntRange) = dispatcher.schedule {
        guildSubscriptions.handleViewportChanged(channel.guildId, channel.id, range)
    }

    @Synchronized
    private fun updateGuildFoldersInternal(entries: List<StoreGuildsSorted.Entry>) {
        val folders = entries.map(StoreGuildsSorted.Entry::asModelGuildFolder)
        val previous = guildFolders
        val version = ++guildFolderUpdateVersion
        dispatcher.schedule {
            guildFolders = folders
            guildsSorted.handlePositions(entries)
            userSettings.handleUserSettingsUpdate(ModelUserSettings(guildFolders = folders))
        }
        if (::restApi.isInitialized) restApi.updateUserSettings(RestAPIParams.GuildFoldersUpdate(folders))
            .subscribeOn(Schedulers.io()).subscribe(
            ::handleUserSettingsUpdate,
            {
                dispatcher.schedule {
                    synchronized(this) {
                        if (version != guildFolderUpdateVersion) return@schedule
                        guildFolders = previous
                    }
                    userSettings.handleUserSettingsUpdate(ModelUserSettings(guildFolders = previous))
                    updateSortedGuilds()
                }
            },
        )
    }

    @StoreThread
    private fun handleAuthStateInternal(state: AuthState?) {
        authentication.handleAuthStateInternal(state)
        users.handleAuthToken(state?.token)
        messagesLoader.handleAuthToken(state?.token)
        notifications.handleAuthToken(state?.token)
        experiments.handleAuthToken(state?.token)
        analytics.handleAuthToken(state?.token)
        voiceChannelSelected.handleAuthToken(state?.token)
        voiceStates.handleAuthToken(state?.token)
        val token = state?.token
        if (token != gatewayToken) {
            authGeneration++
            if (::messageSendCoordinator.isInitialized) messageSendCoordinator.cancelAll()
            cancelActiveUploads()
            messagesLoader.reset()
            if (::applicationContext.isInitialized) {
                WorkManager.getInstance(applicationContext).cancelUniqueWork("BACKGROUND_MESSAGE_SENDING")
                WorkManager.getInstance(applicationContext).cancelAllWorkByTag("MESSAGE_ACK")
            }
            // The socket itself is opened and closed by StoreGatewayConnection's ClientState, which
            // already observes the auth token; here we only reset the READY watchdog.
            gatewayToken = token
            gatewayInitTimeout?.cancel()
            gatewayInitTimeout = null
            if (token != null) {
                gatewayInitTimeout = gatewayScheduler.schedule(GATEWAY_INIT_TIMEOUT_MS) {
                    dispatcher.schedule {
                        if (channels.getChannelsByIdInternal().isNotEmpty() || guilds.getGuildsInternal().isNotEmpty()) {
                            channels.handleStoreInitTimeout()
                            navigation.updateAuthState(authentication.getAuthState(), channels.getInitializedForAuthedUser())
                        } else {
                            Log.w("ManticordGateway", "READY timeout with no cached guild/channel state")
                        }
                    }
                }
            }
        }
        navigation.updateAuthState(state, channels.getInitializedForAuthedUser())
        if (state == null) {
            Persister.reset()
            preferences.edit().clear().apply()
        }
    }

    @StoreThread
    private fun handleLoginResultInternal(loginResult: ModelLoginResult) {
        authentication.handleLoginResultInternal(loginResult)
        userSettingsSystem.handleLoginResult(loginResult)
    }

    @StoreThread
    private fun handlePreLogoutInternal() {
        authGeneration++
        gatewayConnection.handlePreLogout()
        if (::messageSendCoordinator.isInitialized) messageSendCoordinator.cancelAll()
        cancelActiveUploads()
        messagesLoader.reset()
        if (::applicationContext.isInitialized) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork("BACKGROUND_MESSAGE_SENDING")
            WorkManager.getInstance(applicationContext).cancelAllWorkByTag("MESSAGE_ACK")
        }
        authentication.handlePreLogout()
        userSettingsSystem.handlePreLogout()
        notifications.handlePreLogout()
        analytics.handlePreLogout()
        userRelationships.handlePreLogout()
        userProfiles.handlePreLogout()
        userNotes.handlePreLogout()
        friendSuggestions.handlePreLogout()
        userAffinities.handlePreLogout()
        messages.handlePreLogout()
        applicationInteractions.handlePreLogout()
        pendingReplies.handlePreLogout()
        pinnedMessages.handlePreLogout()
        chatInputEditing.clear()
        usersTyping.clear()
        guildSelected.handlePreLogout()
        guildSubscriptions.reset()
        tabsNavigation.handlePreLogout()
        userSettings.reset()
        userSettingsSystem.reset()
        users.reset()
        guilds.reset()
        channels.reset()
        permissions.reset()
        channelsSelected.reset()
        channelMembers.reset()
        expandedGuildFolders.reset()
        collapsedChannelCategories.reset()
        guildSettings.reset()
        stageInstances.reset()
        presences.reset()
        messageUploads.clear()
        clientDataState.clear()
        resetSecondaryStoresInternal()
        connectionOpen.handleConnected(false)
        navigation.reset()
    }

    /**
     * Logout reset for every store wired after the original graph.
     *
     * Shared by [handlePreLogoutInternal] and [resetForLogout] so a store cannot be reset on one
     * path and left holding the previous account's data on the other — the exact defect that made
     * `StoreNotifications` dead code.
     */
    @StoreThread
    private fun resetSecondaryStoresInternal() {
        if (::voiceTransport.isInitialized) {
            voiceTransport.storeRtcConnection.handleVoiceChannelSelected(null)
            voiceTransport.storeStreamRtcConnection.handleStreamDelete()
        }
        threadsJoined.reset()
        threadsActive.reset()
        threadsActiveJoined.reset()
        threadMembers.reset()
        threadMessages.reset()
        threadDraft.clearDraftState()
        forumPostMessages.reset()
        forumPostReadStates.reset()
        archivedThreads.reset()
        chat.handlePreLogout()
        messageReplies.handlePreLogout()
        messageReactions.handlePreLogout()
        messageAck.handlePreLogout()
        messageAck.clearForAccountIsolation()
        mentions.handlePreLogout()
        spotify.handlePreLogout()
        embeddedActivities.handlePreLogout()
        applications.handlePreLogout()
        applicationAssets.handlePreLogout()
        subscriptions.handlePreLogout()
        paymentSources.handlePreLogout()
        guildJoinRequests.reset()
        guildTemplates.reset()
        guildMemberVerificationForm.reset()
        auditLog.reset()
        guildRoleSubscriptions.reset()
        guildBoost.reset()
        guildScheduledEvents.reset()
        instantInvites.reset()
        requestedStageChannels.reset()
        stageChannelSelfPresence.reset()
        mediaFavorites.handlePreLogout()
        gifPicker.handlePreLogout()
        emojiCustom.reset()
        emojiGuild.reset()
        stickers.handlePreLogout()
        guildStickers.reset()
        nux.clearNux()
        // `bans`, `guildIntegrations` and `stageChannels` have no `reset()`; their connection-open
        // handlers are the clear, matching the reference.
        bans.handleConnectionOpen()
        guildIntegrations.handleConnectionOpen()
        stageChannels.handleConnectionOpen()
    }

    @StoreThread
    internal fun handleHomeTabSelected(panelAction: StoreNavigation.PanelAction) {
        navigation.handleHomeTabSelected(panelAction)
    }

    fun resetForLogout() = dispatcher.schedule {
        fun resetStore(name: String, action: () -> Unit) {
            runCatching(action).onFailure { Log.e("StoreStream", "Logout reset failed for $name", it) }
        }
        resetStore("userSettingsSystem.preLogout", userSettingsSystem::handlePreLogout)
        resetStore("accessibility", accessibility::reset)
        resetStore("userSettings", userSettings::reset)
        resetStore("userSettingsSystem", userSettingsSystem::reset)
        resetStore("users", users::reset)
        resetStore("userRelationships", userRelationships::handlePreLogout)
        resetStore("userProfiles", userProfiles::handlePreLogout)
        resetStore("userNotes", userNotes::handlePreLogout)
        resetStore("friendSuggestions", friendSuggestions::handlePreLogout)
        resetStore("userAffinities", userAffinities::handlePreLogout)
        resetStore("guilds", guilds::reset)
        resetStore("channels", channels::reset)
        resetStore("permissions", permissions::reset)
        resetStore("guildSelected", guildSelected::reset)
        resetStore("channelsSelected", channelsSelected::reset)
        resetStore("channelMembers", channelMembers::reset)
        resetStore("guildSubscriptions", guildSubscriptions::reset)
        resetStore("expandedGuildFolders", expandedGuildFolders::reset)
        resetStore("collapsedChannelCategories", collapsedChannelCategories::reset)
        resetStore("guildSettings", guildSettings::reset)
        resetStore("stageInstances", stageInstances::reset)
        resetStore("presences", presences::reset)
        resetStore("messages", messages::handlePreLogout)
        resetStore("pendingReplies", pendingReplies::handlePreLogout)
        resetStore("pinnedMessages", pinnedMessages::handlePreLogout)
        resetStore("chatInputEditing", chatInputEditing::clear)
        resetStore("usersTyping", usersTyping::clear)
        resetStore("tabsNavigation", tabsNavigation::handlePreLogout)
        resetStore("clientDataState", clientDataState::clear)
        resetStore("notifications", notifications::handlePreLogout)
        resetStore("analytics", analytics::handlePreLogout)
        resetStore("secondaryStores", ::resetSecondaryStoresInternal)
        resetStore("voiceChannelSelected", voiceChannelSelected::clear)
        resetStore("connectionOpen") { connectionOpen.handleConnected(false) }
        resetStore("navigation", navigation::reset)
        guildFolders = emptyList()
        val persisterReset = runCatching { Persister.reset() }
        val preferencesCleared = preferences.edit().clear().commit()
        val durablePreferencesCleared = sessionDurablePreferences.edit().clear().commit()
        check(preferencesCleared && durablePreferencesCleared) { "Unable to clear logout preferences" }
        persisterReset.getOrThrow()
    }

    @StoreThread
    private fun handleConnectionOpenInternal(rawPayload: ModelPayload) {
        BootLog.record(BootLogEvent.CACHE_HYDRATION_STARTED)
        val payload = when (val result = readyPayloadHydrator.hydrateReadyPayload(rawPayload)) {
            ReadyPayloadHydrator.HydrateResult.Error -> {
                clientDataState.clear()
                gatewayConnection.resetOnError()
                return
            }
            is ReadyPayloadHydrator.HydrateResult.Success -> result.payload
        }
        gatewayInitTimeout?.cancel()
        gatewayInitTimeout = null
        payload.authToken?.let(authentication::setAuthed)
        authentication.handleConnectionOpen(payload)
        users.handleConnectionOpen(payload)
        payload.userSettings?.let {
            userSettings.handleConnectionOpen(it)
            userSettingsSystem.handleConnectionOpen(it)
            it.guildFolders?.let { folders -> guildFolders = folders }
        }
        userRelationships.handleConnectionOpen(payload)
        guilds.handleConnectionOpen(payload)
        guildSettings.handleConnectionOpen(payload)
        lurking.handleConnectionOpen(payload)
        channels.handleConnectionOpen(payload)
        threadsActive.handleConnectionOpen()
        threadsJoined.handleConnectionOpen(payload)
        threadsActiveJoined.handleConnectionOpen()
        permissions.handleConnectionOpen()
        guildSelected.handleConnectionOpen(payload)
        channelsSelected.handleConnectionOpen(payload)
        collapsedChannelCategories.handleConnectionOpen(payload)
        stageInstances.handleConnectionOpen(payload.guilds.orEmpty())
        presences.handleConnectionOpen(payload)
        mentions.handleConnectionOpen(payload)
        messages.handleConnectionOpen(payload)
        messagesMostRecent.handleConnectionOpen(payload)
        messageAck.handleConnectionOpen(payload)
        userNotes.handleConnectionOpen()
        friendSuggestions.handleConnectionOpen(payload)
        userAffinities.handleConnectionOpen()
        clientDataState.handleConnectionOpen(payload)
        guildMemberCounts.handleConnectionOpen(payload)
        gameParty.handleConnectionOpen(payload)
        userRequiredActions.handleConnectionOpen(payload)
        userConnections.handleConnectionOpen(payload)
        spotify.handleConnectionOpen(payload)
        phone.handleConnectionOpen(payload)
        userSurvey.handleConnectionOpen()
        guildMemberRequester.handleConnectionOpen()
        bans.handleConnectionOpen()
        guildIntegrations.handleConnectionOpen()
        guildJoinRequests.handleConnectionOpen(payload)
        guildScheduledEvents.handleConnectionOpen(payload)
        stageChannels.handleConnectionOpen()
        emojiCustom.handleConnectionOpen(payload)
        guildStickers.handleConnectionOpen(payload)
        reviewRequest.handleConnectionOpen(payload)
        changeLog.handleConnectionOpen()
        voiceStates.handleConnectionOpen(payload)
        voiceChannelSelected.handleConnectionOpen(payload)
        applicationStreaming.handleConnectionOpen(payload)
        calls.handleConnectionOpen()
        if (::voiceTransport.isInitialized) {
            voiceTransport.storeRtcConnection.handleConnectionOpen(payload)
            voiceTransport.storeMediaEngine.handleConnectionOpen(payload)
            voiceTransport.storeStreamRtcConnection.handleConnectionOpen(payload)
        }
        analytics.handleConnectionOpen(payload)
        experiments.handleConnectionOpen(payload)
        if (::rtcRegion.isInitialized) rtcRegion.handleConnectionOpen()
        messageReactions.handleConnectionOpen()
        threadMembers.handleConnectionOpen()
        threadMessages.handleConnectionOpen(payload)
        applicationCommands.handleConnectionOpen(payload)
        applicationInteractions.handleConnectionOpen(payload)
        embeddedActivities.handleConnectionOpen(payload)
        forumPostMessages.handleConnectionOpen(payload)
        forumPostReadStates.handleConnectionOpen(payload)
        archivedThreads.handleConnectionOpen()
        connectionOpen.handleConnectionOpen()
        updateSortedGuilds()
        BootLog.record(BootLogEvent.CACHE_HYDRATION_FINISHED)
        navigation.updateAuthState(authentication.getAuthState(), channels.getInitializedForAuthedUser())
    }

    @StoreThread
    private fun handleGuildCreateOrUpdateInternal(guild: Guild) {
        users.handleGuildAddOrSync(guild)
        guilds.handleGuildAdd(guild)
        presences.handleGuildAdd(guild)
        channels.handleGuildAdd(guild)
        permissions.handleGuildAdd(guild)
        channelsSelected.handleGuildSelected()
        stageInstances.handleGuildAdd(guild)
        clientDataState.handleGuildAdd(guild)
        guildMemberCounts.handleGuildCreate(guild)
        voiceStates.handleGuildAdd(guild)
        gameParty.handleGuildCreateOrSync(guild)
        lurking.handleGuildAdd(guild)
        guildScheduledEvents.handleGuildCreate(guild)
        emojiCustom.handleGuildAdd(guild)
        guildStickers.handleGuildCreateOrUpdate(guild)
        embeddedActivities.handleGuildCreate(guild)
        mentions.handleGuildAdd(guild)
        messagesMostRecent.handleGuildAdd(guild)
        // Order matters: MessageAck pruning must see the hydrated active-thread indexes.
        threadsActive.handleGuildCreate()
        threadsJoined.handleGuildCreate(guild)
        threadsActiveJoined.handleGuildCreate(guild)
        messageAck.handleGuildCreate()
        threadMessages.handleGuildCreate(guild)
        updateSortedGuilds()
    }

    @StoreThread
    private fun handleGuildRemoveInternal(guild: Guild) {
        guildSelected.handleGuildRemove(guild)
        guilds.handleGuildRemove(guild)
        lurking.handleGuildRemove(guild)
        clientDataState.handleGuildRemove(guild)
        if (guild.unavailable) {
            updateSortedGuilds()
            return
        }
        presences.handleGuildRemove(guild)
        channels.handleGuildRemove(guild)
        permissions.handleGuildRemove(guild)
        if (guild.unavailable) channelsSelected.handleGuildSelected() else channelsSelected.handleGuildRemove(guild.id)
        stageInstances.handleGuildRemove(guild)
        channelMembers.handleGuildRemove(guild.id)
        guildSubscriptions.handleGuildRemove(guild.id)
        guildMemberCounts.handleGuildDelete(guild.id)
        bans.handleGuildRemove(guild.id)
        guildJoinRequests.handleGuildRemove(guild.id)
        guildScheduledEvents.handleGuildRemove(guild.id)
        emojiCustom.handleGuildRemove(guild)
        guildStickers.handleGuildRemove(guild)
        stageChannels.handleGuildRemove(guild)
        voiceStates.handleGuildRemove(guild)
        voiceChannelSelected.handleGuildRemove()
        threadsJoined.handleGuildDelete(guild.id)
        threadsActive.handleGuildDelete(guild.id)
        threadsActiveJoined.handleGuildDelete(guild.id)
        threadMessages.handleGuildDelete(guild.id)
        updateSortedGuilds()
    }

    @StoreThread
    private fun handleChannelCreateOrUpdateInternal(channel: Channel) {
        if (channel.isThread()) {
            // ArchivedThreadsStore hands a fetched thread back through this entry point.
            handleThreadCreateOrUpdateInternal(channel)
            return
        }
        users.handleChannelCreated(channel)
        channelConversions.handleChannelCreateOrUpdate(channel)
        channels.handleChannelOrThreadCreateOrUpdate(channel)
        channelsSelected.handleChannelOrThreadCreateOrUpdate(channel)
        permissions.handleChannelOrThreadCreateOrUpdate(channel)
        voiceChannelSelected.handleChannelOrThreadCreateOrUpdate()
        mentions.handleChannelOrThreadCreateOrUpdate(channel)
        messagesMostRecent.handleChannelCreateOrUpdate(channel)
        clientDataState.handleChannelCreateOrUpdateOrDelete(channel)
        threadsActive.handleChannelCreateOrUpdate(channel)
        threadsActiveJoined.handleChannelCreateOrUpdate(channel)
    }

    @StoreThread
    private fun handleChannelDeleteInternal(channel: Channel) {
        channels.handleChannelOrThreadDelete(channel)
        permissions.handleChannelOrThreadDelete(channel)
        channelsSelected.handleChannelOrThreadDelete(channel)
        stageInstances.handleChannelDelete(channel)
        clientDataState.handleChannelCreateOrUpdateOrDelete(channel)
        threadMessages.handleChannelDelete(channel)
        stageChannels.handleChannelDelete(channel)
        mentions.handleChannelOrThreadDelete(channel)
        voiceChannelSelected.handleChannelOrThreadDelete()
    }

    @StoreThread
    private fun updateSortedGuilds() {
        val mutedGuildIds = guildSettings.getGuildSettingsInternal().values
            .filter(ModelNotificationSettings::isMuted).mapTo(HashSet(), ModelNotificationSettings::guildId)
        guildsSorted.update(
            guilds.getGuildsInternal(),
            guilds.getUnavailableGuildsInternal(),
            lurking.getLurkingGuildIdsSync(),
            guildFolders,
            mutedGuildIds,
            guilds.getGuildsJoinedAtInternal(),
        )
    }

    fun observeInitialized(): Observable<Boolean> = initialized.distinctUntilChanged()

    // Accessors used by StoreGatewayConnection to assemble its ClientState and outgoing traffic.
    internal fun getAuthenticationInternal() = authentication
    internal fun getClientDataStateInternal() = clientDataState
    internal fun getGuildSubscriptionsInternal() = guildSubscriptions

    private lateinit var restApi: RestAPIInterface

    private fun createRestApi(context: Context): RestAPIInterface {
        val headers = object : RequiredHeadersInterceptor.HeadersProvider {
            override fun getAuthToken() = authentication.getAuthState()?.token
            override fun getFingerprint() = authentication.getFingerprint()
            override fun getLocale() = userSettingsSystem.getLocale()
            override fun getAcceptLanguages() = acceptLanguages()
            override fun getUserAgent() = USER_AGENT
            override fun getSpotifyToken(): String? = null
        }
        val builder = RestAPIBuilder(API_BASE_URL, CookieJars.persistent(context))
        val interceptors = listOf(RequiredHeadersInterceptor(headers), RestAPI.buildAnalyticsInterceptor())
        val api = RestAPI.install(builder, interceptors)
        restApi = api
        return api
    }

    companion object {
        private const val API_BASE_URL = "https://discord.com/api/"
        private const val RTC_LATENCY_URL = "https://latency.discord.media/rtc"
        private const val USER_AGENT = "Discord-Android/126021"
        private const val GATEWAY_INIT_TIMEOUT_MS = 10_000L
        private const val GATEWAY_TAG = "ManticordGateway"
        @Volatile private var instance: StoreStream? = null

        private fun acceptLanguages(): String {
            val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val adjusted = LocaleList.getAdjustedDefault()
                (0 until adjusted.size()).map(adjusted::get)
            } else {
                listOf(Locale.getDefault())
            }
            return locales.mapIndexed { index, locale ->
                locale.toLanguageTag().let { tag ->
                    if (index == 0) tag else "$tag;q=0.${(10 - index).coerceAtLeast(1)}"
                }
            }.joinToString(",")
        }

        /**
         * Files directory the current test instance was built for.
         *
         * Test classes run in one JVM, so a second class calling [initializeForTesting] used to be
         * handed the first class's graph — silently bound to another class's `SharedPreferences` and
         * `Persister` directory, which made every persistence assertion read from the wrong place.
         */
        private var testFilesDir: File? = null

        @JvmStatic
        @Synchronized
        fun initialize(context: Context): StoreStream {
            instance?.let { return it }
            SharedPreferencesProvider.init(context.applicationContext)
            val clock = Clock(System::currentTimeMillis)
            Persister.init(context.applicationContext, clock, Observable.never())
            BootLog.record(BootLogEvent.PERSISTENCE_READY)
            return create(
                StoreThreadScheduler.create(),
                clock,
                SharedPreferencesProvider.get(),
                SharedPreferencesProvider.getPrefsSessionDurable(),
            ).also {
                instance = it
                BootLog.record(BootLogEvent.STORE_GRAPH_READY)
                it.init(context.applicationContext)
            }
        }

        @Synchronized
        @SuppressLint("VisibleForTests")
        internal fun initializeForTesting(
            filesDir: File,
            preferences: SharedPreferences,
            sessionDurablePreferences: SharedPreferences,
            scheduler: Scheduler,
            clock: Clock = Clock { 0 },
        ): StoreStream {
            // Idempotent for the class that owns [filesDir]; a different fixture directory means a
            // different test class, and it gets its own graph rather than the previous one's.
            instance?.takeIf { testFilesDir == filesDir }?.let { return it }
            testFilesDir = filesDir
            Persister.init(filesDir, Observable.never(), clock)
            return create(scheduler, clock, preferences, sessionDurablePreferences).also {
                instance = it
                it.initForTesting()
            }
        }

        private fun create(
            scheduler: Scheduler,
            clock: Clock,
            preferences: SharedPreferences,
            sessionDurablePreferences: SharedPreferences,
        ): StoreStream {
            val deck = ObservationDeck()
            val dispatcher = Dispatcher(scheduler)
            // `ChannelSelector` is the single owner of "open this channel" and is reachable from
            // widgets as a singleton, exactly as in the reference; it only needs the dispatcher and
            // the deck, so it can be published before the store graph finishes constructing.
            ChannelSelector.init(dispatcher, deck)
            return StoreStream(
                dispatcher,
                scheduler,
                clock,
                deck,
                preferences,
                sessionDurablePreferences,
            )
        }

        private fun requireInstance() = checkNotNull(instance) { "StoreStream.initialize() must be called first" }

        @JvmStatic fun getAccessibility() = requireInstance().accessibility
        @JvmStatic fun getAuthentication() = requireInstance().authentication
        @JvmStatic fun getUserSettings() = requireInstance().userSettings
        @JvmStatic fun getUserSettingsSystem() = requireInstance().userSettingsSystem
        @JvmStatic fun getUsers() = requireInstance().users
        @JvmStatic fun getUserRelationships() = requireInstance().userRelationships
        @JvmStatic fun getGuilds() = requireInstance().guilds
        @JvmStatic fun getGuildSelected() = requireInstance().guildSelected
        @JvmStatic fun getGuildsSorted() = requireInstance().guildsSorted
        @JvmStatic fun getExpandedGuildFolders() = requireInstance().expandedGuildFolders
        @JvmStatic fun getCollapsedChannelCategories() = requireInstance().collapsedChannelCategories
        @JvmStatic fun getChannels() = requireInstance().channels
        @JvmStatic fun getChannelsSelected() = requireInstance().channelsSelected
        @JvmStatic fun getChannelMembers() = requireInstance().channelMembers
        @JvmStatic fun getGuildSubscriptions() = requireInstance().guildSubscriptions
        @JvmStatic fun getPermissions() = requireInstance().permissions
        @JvmStatic fun getUserGuildSettings() = requireInstance().guildSettings
        @JvmStatic fun getStageInstances() = requireInstance().stageInstances
        @JvmStatic fun getPresences() = requireInstance().presences
        @JvmStatic fun getReadStates() = requireInstance().readStates
        @JvmStatic fun getMessages() = requireInstance().messages
        @JvmStatic fun getMessagesLoader() = requireInstance().messagesLoader
        @JvmStatic fun getMessagesMostRecent() = requireInstance().messagesMostRecent
        @JvmStatic fun getPendingReplies() = requireInstance().pendingReplies
        @JvmStatic fun getPinnedMessages() = requireInstance().pinnedMessages
        @JvmStatic fun getMessageUploads() = requireInstance().messageUploads
        @JvmStatic fun getMessageState() = requireInstance().messageState
        @JvmStatic fun getChatInputEditing() = requireInstance().chatInputEditing
        @JvmStatic fun getUsersTyping() = requireInstance().usersTyping
        @JvmStatic fun getUserProfiles() = requireInstance().userProfiles
        @JvmStatic fun getGuildProfiles() = requireInstance().guildProfiles
        @JvmStatic fun getGuildWelcomeScreens() = requireInstance().guildWelcomeScreens
        @JvmStatic fun getLurking() = requireInstance().lurking
        @JvmStatic fun getUserNotes() = requireInstance().userNotes
        @JvmStatic fun getFriendSuggestions() = requireInstance().friendSuggestions
        @JvmStatic fun getUserAffinities() = requireInstance().userAffinities
        @JvmStatic fun getGatewayConnection() = requireInstance().gatewayConnection
        @JvmStatic fun getClientDataState() = requireInstance().clientDataState
        @JvmStatic fun getConnectionOpen() = requireInstance().connectionOpen
        @JvmStatic fun getConnectivity() = requireInstance().connectivity
        @JvmStatic fun getApplicationCommandFrecency() = requireInstance().applicationCommandFrecency
        @JvmStatic fun getApplicationCommands() = requireInstance().applicationCommands
        @JvmStatic fun getInteractions() = requireInstance().applicationInteractions
        @JvmStatic fun getApplicationDirectory() = requireInstance().applicationDirectory
        @JvmStatic fun getLocalActionComponentState() = requireInstance().localActionComponentState
        @JvmStatic fun getEmbeddedActivities() = requireInstance().embeddedActivities
        @JvmStatic fun getApplication() = requireInstance().applications
        @JvmStatic fun getApplicationAssets() = requireInstance().applicationAssets
        @JvmStatic fun getApplicationStreamPreviews() = requireInstance().applicationStreamPreviews
        @JvmStatic fun getEntitlements() = requireInstance().entitlements
        @JvmStatic fun getGooglePlayPurchases() = requireInstance().googlePlayPurchases
        @JvmStatic fun getGooglePlaySkuDetails() = requireInstance().googlePlaySkuDetails
        @JvmStatic fun getSubscriptions() = requireInstance().subscriptions
        @JvmStatic fun getPaymentSources() = requireInstance().paymentSources
        @JvmStatic fun getNavigation() = requireInstance().navigation
        @JvmStatic fun getTabsNavigation() = requireInstance().tabsNavigation

        // Threads and forums
        @JvmStatic fun getThreadsJoined() = requireInstance().threadsJoined
        @JvmStatic fun getThreadsActive() = requireInstance().threadsActive
        @JvmStatic fun getThreadsActiveJoined() = requireInstance().threadsActiveJoined
        @JvmStatic fun getThreadMembers() = requireInstance().threadMembers
        @JvmStatic fun getThreadMessages() = requireInstance().threadMessages
        @JvmStatic fun getThreadDraft() = requireInstance().threadDraft
        @JvmStatic fun getForumPostMessages() = requireInstance().forumPostMessages
        @JvmStatic fun getForumPostReadStates() = requireInstance().forumPostReadStates
        @JvmStatic fun getArchivedThreads() = requireInstance().archivedThreads

        // Chat and messages
        @JvmStatic fun getChat() = requireInstance().chat
        @JvmStatic fun getMessageReplies() = requireInstance().messageReplies
        @JvmStatic fun getMessageReactions() = requireInstance().messageReactions
        @JvmStatic fun getMessageAck() = requireInstance().messageAck
        @JvmStatic fun getMentions() = requireInstance().mentions
        @JvmStatic fun getSlowMode() = requireInstance().slowMode

        // Cross-cutting
        @JvmStatic fun getExperiments() = requireInstance().experiments
        @JvmStatic fun getAnalytics() = requireInstance().analytics
        @JvmStatic fun getGuildsNsfw() = requireInstance().guildsNsfw
        @JvmStatic fun getSearch() = requireInstance().search
        @JvmStatic fun getGifPicker() = requireInstance().gifPicker
        @JvmStatic fun getExpressionPickerNavigation() = requireInstance().expressionPickerNavigation
        @JvmStatic fun getExpressionSuggestions() = requireInstance().expressionSuggestions
        @JvmStatic fun getMaskedLinks() = requireInstance().maskedLinks
        @JvmStatic fun getMediaFavorites() = requireInstance().mediaFavorites
        @JvmStatic fun getNotificationUpsells() = requireInstance().notificationUpsells
        @JvmStatic fun getNotifications() = requireInstance().notifications
        @JvmStatic fun getGameParty() = requireInstance().gameParty
        @JvmStatic fun getGuildMemberCounts() = requireInstance().guildMemberCounts
        @JvmStatic fun getUserRequiredActions() = requireInstance().userRequiredActions
        @JvmStatic fun getUserConnections() = requireInstance().userConnections
        @JvmStatic fun getSpotify() = requireInstance().spotify
        @JvmStatic fun getContactSync() = requireInstance().contactSync

        // Guild administration
        @JvmStatic fun getBans() = requireInstance().bans
        @JvmStatic fun getGuildIntegrations() = requireInstance().guildIntegrations
        @JvmStatic fun getGuildJoinRequests() = requireInstance().guildJoinRequests
        @JvmStatic fun getGuildTemplates() = requireInstance().guildTemplates
        @JvmStatic fun getGuildMemberVerificationForm() = requireInstance().guildMemberVerificationForm
        @JvmStatic fun getAuditLog() = requireInstance().auditLog
        @JvmStatic fun getGuildRoleSubscriptions() = requireInstance().guildRoleSubscriptions
        @JvmStatic fun getGuildBoost() = requireInstance().guildBoost
        @JvmStatic fun getGuildScheduledEvents() = requireInstance().guildScheduledEvents
        @JvmStatic fun getInstantInvites() = requireInstance().instantInvites
        @JvmStatic fun getInviteSettings() = requireInstance().inviteSettings
        @JvmStatic fun getStageChannels() = requireInstance().stageChannels
        @JvmStatic fun getRequestedStageChannels() = requireInstance().requestedStageChannels
        @JvmStatic fun getStageChannelSelfPresence() = requireInstance().stageChannelSelfPresence
        @JvmStatic fun getMFA() = requireInstance().mfa
        @JvmStatic fun getPhone() = requireInstance().phone
        @JvmStatic fun getUserSurvey() = requireInstance().userSurvey
        @JvmStatic fun getGuildMemberRequester() = requireInstance().guildMemberRequester
        @JvmStatic fun getChannelConversions() = requireInstance().channelConversions

        // Emoji, stickers and notices
        @JvmStatic fun getEmojis() = requireInstance().emoji
        @JvmStatic fun getCustomEmojis() = requireInstance().emojiCustom
        @JvmStatic fun getGuildEmojis() = requireInstance().emojiGuild
        @JvmStatic fun getStickers() = requireInstance().stickers
        @JvmStatic fun getGuildStickers() = requireInstance().guildStickers
        @JvmStatic fun getNotices() = requireInstance().notices
        @JvmStatic fun getNux() = requireInstance().nux
        @JvmStatic fun getReviewRequest() = requireInstance().reviewRequest
        @JvmStatic fun getChangeLog() = requireInstance().changeLog

        // Voice
        @JvmStatic fun getVoiceStates() = requireInstance().voiceStates
        @JvmStatic fun getVoiceSpeaking() = requireInstance().voiceSpeaking
        @JvmStatic fun getVoiceChannelSelected() = requireInstance().voiceChannelSelected
        @JvmStatic fun getVoiceParticipants() = requireInstance().voiceParticipants
        @JvmStatic fun getCalls() = requireInstance().calls
        @JvmStatic fun getCallsIncoming() = requireInstance().callsIncoming
        @JvmStatic fun getApplicationStreaming() = requireInstance().applicationStreaming
        @JvmStatic fun getVideoStreams() = requireInstance().videoStreams
        @JvmStatic fun getMediaSettings() = requireInstance().mediaSettings
        @JvmStatic fun getMediaEngine() = requireInstance().voiceTransport.storeMediaEngine
        @JvmStatic fun getRtcConnection() = requireInstance().voiceTransport.storeRtcConnection
        @JvmStatic fun getRtcRegion() = requireInstance().rtcRegion
        @JvmStatic fun getStreamRtcConnection() = requireInstance().voiceTransport.storeStreamRtcConnection
        @JvmStatic fun getAudioManagerV2() = requireInstance().voiceTransport.storeAudioManagerV2
        @JvmStatic fun selectGuild(guildId: Long) = requireInstance().selectGuildInternal(guildId)
        @JvmStatic fun selectChannel(guildId: Long, channelId: Long) = requireInstance().selectChannelInternal(guildId, channelId)
        @JvmStatic fun openDm(userId: Long) = requireInstance().openDmInternal(userId)
        @JvmStatic fun createPrivateChannel(recipientIds: List<Long>, completed: (Result<Channel>) -> Unit) =
            requireInstance().createPrivateChannelInternal(recipientIds, completed)
        /**
         * `GET /users/@me/mentions`, the recent-mentions inbox feed.
         *
         * Not defaulted: `getMentions()` with no arguments is the [StoreMentions] accessor above,
         * and a default here would make the zero-argument call ambiguous.
         */
        @JvmStatic fun getMentions(before: Long?) = requireInstance().restApi
            .getMentions(25, roles = true, everyone = true, before = before)
        @JvmStatic fun updateMemberPanel(channel: Channel?, open: Boolean) = requireInstance().updateMemberPanelInternal(channel, open)
        @JvmStatic fun updateMemberRange(channel: Channel, range: IntRange) = requireInstance().updateMemberRangeInternal(channel, range)
        @JvmStatic fun updateGuildFolders(entries: List<StoreGuildsSorted.Entry>) = requireInstance().updateGuildFoldersInternal(entries)
        @JvmStatic fun cancelMessageSend(nonce: String) = requireInstance().cancelMessageSendInternal(nonce)

        /**
         * Ingest a thread fetched over REST rather than received over the gateway.
         *
         * `ChannelSelector.findAndSetThread` needs this: a link to a thread has to be fetched and
         * inserted before it can be selected, and `THREAD_CREATE` will never arrive for it.
         */
        @JvmStatic fun handleThreadCreateOrUpdate(thread: Channel) = requireInstance().let { stream ->
            stream.dispatcher.schedule { stream.handleThreadCreateOrUpdateInternal(thread) }
        }

        @JvmStatic fun handleThreadSearchResult(listing: ThreadListing) = requireInstance().let { stream ->
            stream.dispatcher.schedule {
                listing.threads.forEach(stream::handleThreadCreateOrUpdateInternal)
                stream.threadsJoined.handleSearchFinish(listing.threads, listing.members)
                listing.threads.forEach(stream.threadsActiveJoined::handleThreadCreateOrUpdateOrDelete)
                listing.firstMessages?.let(stream.forumPostMessages::bulkCreateFirstMessage)
            }
        }

        @JvmStatic fun setForumPostFollowed(threadId: Long, guildId: Long, followed: Boolean) =
            requireInstance().let { stream ->
                stream.dispatcher.schedule {
                    stream.threadsJoined.handleLocalFollow(threadId, guildId, followed)
                    stream.channels.getChannelInternal(guildId, threadId)
                        ?.let(stream.threadsActiveJoined::handleThreadCreateOrUpdateOrDelete)
                }
            }
    }
}
