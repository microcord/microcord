package com.discord.stores

import com.discord.api.activity.Activity
import com.discord.api.auth.AuthSessionChange
import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelPinsUpdate
import com.discord.api.channel.ChannelRecipient
import com.discord.api.commands.ApplicationCommandAutocompleteResult
import com.discord.api.commands.GuildApplicationCommandIndexUpdate
import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.api.embeddedactivities.EmbeddedActivityInstance
import com.discord.api.emoji.GuildEmojisUpdate
import com.discord.api.forum.ForumUnreads
import com.discord.api.friendsuggestions.FriendSuggestion
import com.discord.api.friendsuggestions.FriendSuggestionDelete
import com.discord.api.guild.Guild
import com.discord.api.guild.PassiveUpdateV2
import com.discord.api.guildjoinrequest.GuildJoinRequestCreateOrUpdate
import com.discord.api.guildjoinrequest.GuildJoinRequestDelete
import com.discord.api.guildmember.GuildMember
import com.discord.api.guildmember.GuildMemberRemove
import com.discord.api.guildmember.GuildMembersChunk
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventUserUpdate
import com.discord.api.interaction.InteractionModalCreate
import com.discord.api.interaction.InteractionStateUpdate
import com.discord.api.message.Message
import com.discord.api.message.PollVoteAddMany
import com.discord.api.message.PollVoteEvent
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.api.message.reaction.MessageReactionAddMany
import com.discord.api.presence.ClientStatus
import com.discord.api.presence.Presence
import com.discord.api.requiredaction.UserRequiredActionUpdate
import com.discord.api.role.GuildRoleCreate
import com.discord.api.role.GuildRoleDelete
import com.discord.api.role.GuildRoleUpdate
import com.discord.api.stageinstance.StageInstance
import com.discord.api.sticker.GuildStickersUpdate
import com.discord.api.soundboard.SoundboardSound
import com.discord.api.soundboard.SoundboardSoundDelete
import com.discord.api.soundboard.SoundboardSounds
import com.discord.api.thread.ThreadMemberListUpdate
import com.discord.api.thread.ThreadMemberUpdate
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.api.user.TypingUser
import com.discord.api.user.User
import com.discord.api.voice.server.VoiceServer
import com.discord.api.voice.state.VoiceState
import com.discord.api.voice.state.VoiceStateUpdateBatch
import com.discord.gateway.GatewayEventHandler
import com.discord.gateway.GatewaySocket
import com.discord.gateway.io.HeartbeatQos
import com.discord.gateway.IdentifyData
import com.discord.gateway.io.ForumUnreadsRequest
import com.discord.gateway.io.ForumUnreadsRequestThread
import com.discord.gateway.io.GuildSubscriptions
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.domain.ModelBan
import com.discord.models.domain.ModelCall
import com.discord.models.domain.ModelChannelUnreadUpdate
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.domain.ModelGuildMemberListUpdate
import com.discord.models.domain.ModelMessageDelete
import com.discord.api.notification.AccountNotificationSettings
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadySupplemental
import com.discord.models.domain.ReadyGuild
import com.discord.models.domain.BackgroundSync
import com.discord.gateway.io.DeletedEntityIds
import com.discord.gateway.io.DeletedEntityIdsRequest
import com.discord.models.domain.ModelReadState
import com.discord.models.domain.ModelSession
import com.discord.models.domain.ModelUserNote
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.domain.StreamCreateOrUpdate
import com.discord.models.domain.StreamDelete
import com.discord.models.domain.StreamServerUpdate
import com.discord.models.domain.UserSettingsProtoUpdate
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.stores.utilities.BatchManager
import com.discord.stores.utilities.Batched
import com.discord.utilities.lifecycle.Backgrounded
import com.discord.utilities.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.subjects.SerializedSubject

/**
 * Owns the gateway socket and fans every dispatch out onto a typed subject.
 *
 * This is the only place that talks to [GatewaySocket]. Stores never see the socket; they subscribe
 * to the subject for the event they care about (wired in `StoreStream.initGatewaySocketListeners`)
 * and call the send API here to talk back.
 *
 * Subjects are `BehaviorSubject`-backed so a late subscriber still sees the most recent event of
 * that type. The two exceptions ([guildBanAdd], [guildBanRemove], [guildIntegrationsUpdate]) are
 * `PublishSubject`-backed because replaying them would re-apply a one-shot moderation action.
 */
class StoreGatewayConnection(
    private val stream: StoreStream,
    private val clock: Clock,
    private val executor: ExecutorService = Executors.newFixedThreadPool(1),
    private val scheduler: Scheduler = Schedulers.from(executor),
) : GatewayEventHandler {

    private val coroutineDispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val batches = BatchManager(coroutineDispatcher, clock)

    private var socket: GatewaySocket? = null
    private var token: String? = null
    private var tokenSubscription: Subscription? = null
    private var backgroundedSubscription: Subscription? = null
    private var guildSubscriptionsSubscription: Subscription? = null

    // Connection state
    private val connected = behavior(false)
    private val connectionReady = behavior(false)
    private val freshSession = behavior<Unit>()

    // Session
    val ready = behavior<ModelPayload>()
    val readySupplemental = behavior<ModelReadySupplemental>()
    val initialGuild = behavior<ReadyGuild>()

    // Current user
    val userUpdate = behavior<User>()
    val userSettingsProtoUpdate = behavior<UserSettingsProtoUpdate>()
    val userGuildSettingsUpdate = behavior<ModelNotificationSettings>()
    val notificationSettingsUpdate = behavior<AccountNotificationSettings>()
    val userNoteUpdate = behavior<ModelUserNote.Update>()
    val userRequiredActionUpdate = behavior<UserRequiredActionUpdate>()
    val authSessionChange = behavior<AuthSessionChange>()
    val userConnectionUpdate = behavior<Unit>()
    val userSubscriptionsUpdate = behavior<Unit>()
    val userPaymentSourcesUpdate = behavior<Unit>()
    val sessionsReplace = behavior<List<ModelSession>>()

    // Relationships
    val relationshipAdd = behavior<ModelUserRelationship>()
    val relationshipUpdate = behavior<ModelUserRelationship>()
    val relationshipRemove = behavior<ModelUserRelationship>()
    val friendSuggestionCreate = behavior<FriendSuggestion>()
    val friendSuggestionDelete = behavior<FriendSuggestionDelete>()

    // Guilds
    val guildCreate = behavior<Any>()
    val guildUpdate = behavior<Guild>()
    val guildDeleted = behavior<Guild>()
    val backgroundSync = behavior<BackgroundSync>()
    val deletedEntityIds = behavior<DeletedEntityIds>()
    val guildEmojisUpdate = behavior<GuildEmojisUpdate>()
    val guildStickersUpdate = behavior<GuildStickersUpdate>()
    val soundboardSounds = publish<SoundboardSounds>()
    val guildSoundboardSoundsUpdate = publish<SoundboardSounds>()
    val guildSoundboardSoundCreateOrUpdate = publish<SoundboardSound>()
    val guildSoundboardSoundDelete = publish<SoundboardSoundDelete>()
    val guildApplicationCommandIndexUpdate = behavior<GuildApplicationCommandIndexUpdate>()
    val applicationCommandAutocompleteResult = behavior<ApplicationCommandAutocompleteResult>()

    /** One-shot moderation events: replaying them to a late subscriber would re-apply the action. */
    val guildBanAdd = publish<ModelBan>()
    val guildBanRemove = publish<ModelBan>()
    val guildIntegrationsUpdate = publish<ModelGuildIntegration.Update>()

    // Guild membership
    val guildMembersAdd = behavior<GuildMember>()
    val guildMemberRemove = behavior<GuildMemberRemove>()
    val guildMembersChunk = behavior<GuildMembersChunk>()
    val guildMemberListUpdate = behavior<ModelGuildMemberListUpdate>()
    val guildJoinRequestCreateOrUpdate = behavior<GuildJoinRequestCreateOrUpdate>()
    val guildJoinRequestDelete = behavior<GuildJoinRequestDelete>()

    // Guild roles
    val guildRoleCreate = behavior<GuildRoleCreate>()
    val guildRoleUpdate = behavior<GuildRoleUpdate>()
    val guildRoleDelete = behavior<GuildRoleDelete>()

    // Guild scheduled events
    val guildScheduledEventCreate = behavior<GuildScheduledEvent>()
    val guildScheduledEventUpdate = behavior<GuildScheduledEvent>()
    val guildScheduledEventDelete = behavior<GuildScheduledEvent>()
    val guildScheduledEventUserAdd = behavior<GuildScheduledEventUserUpdate>()
    val guildScheduledEventUserRemove = behavior<GuildScheduledEventUserUpdate>()

    // Channels and threads
    val channelCreateOrUpdate = behavior<Channel>()
    val channelDeleted = behavior<Channel>()
    val channelPinsUpdate = behavior<ChannelPinsUpdate>()
    val channelRecipientAdd = behavior<ChannelRecipient>()
    val channelRecipientRemove = behavior<ChannelRecipient>()
    val channelUnreadUpdate = behavior<ModelChannelUnreadUpdate>()
    val passiveUpdateV2 = behavior<PassiveUpdateV2>()
    val threadCreateOrUpdate = behavior<Channel>()
    val threadDelete = behavior<Channel>()
    val threadListSync = behavior<ModelThreadListSync>()
    val threadMemberUpdate = behavior<ThreadMemberUpdate>()
    val threadMembersUpdate = behavior<ThreadMembersUpdate>()
    val threadMemberListUpdate = behavior<ThreadMemberListUpdate>()
    val forumUnreads = behavior<ForumUnreads>()

    // Messages
    val messageCreate = behavior<Message>()
    val messageUpdate = behavior<Message>()
    val messageDelete = behavior<ModelMessageDelete>()
    val messageAck = behavior<ModelReadState>()
    val messageReactionAdd = behavior<MessageReactionUpdate>()
    val messageReactionAddMany = behavior<MessageReactionAddMany>()
    val pollVoteAddMany = behavior<PollVoteAddMany>()
    val messageReactionRemove = behavior<MessageReactionUpdate>()
    val messageReactionRemoveAll = behavior<MessageReactionUpdate>()
    val messageReactionRemoveEmoji = behavior<MessageReactionUpdate>()
    val messagePollVoteAdd = behavior<PollVoteEvent>()
    val messagePollVoteRemove = behavior<PollVoteEvent>()

    // Presence and typing
    /**
     * Coalesced for the same reason as [voiceStateUpdate], and more so: presence is the highest
     * volume event on the wire, and every one of them rewrites a map the whole UI reads from.
     */
    val presenceUpdate: Batched<Presence> = batches.createBatched(EVENT_PRESENCE_UPDATE)
    val presenceReplace = behavior<List<Presence>>()
    val typingStart = behavior<TypingUser>()

    /**
     * Coalesced: a large guild emits hundreds of voice states per second, and applying them one at
     * a time would flood the store thread.
     */
    val voiceStateUpdate: Batched<VoiceState> = batches.createBatched(EVENT_VOICE_STATE_UPDATE)
    val voiceServerUpdate = behavior<VoiceServer>()

    // Calls and streams
    val callCreateOrUpdate = behavior<ModelCall>()
    val callDelete = behavior<ModelCall>()
    val streamCreate = behavior<StreamCreateOrUpdate>()
    val streamUpdate = behavior<StreamCreateOrUpdate>()
    val streamDelete = behavior<StreamDelete>()
    val streamServerUpdate = behavior<StreamServerUpdate>()

    // Stage instances, interactions and embedded activities
    val stageInstanceCreate = behavior<StageInstance>()
    val stageInstanceUpdate = behavior<StageInstance>()
    val stageInstanceDelete = behavior<StageInstance>()
    val interactionCreate = behavior<InteractionStateUpdate>()
    val interactionSuccess = behavior<InteractionStateUpdate>()
    val interactionFailure = behavior<InteractionStateUpdate>()
    val interactionModalCreate = behavior<InteractionModalCreate>()
    val embeddedActivityInboundUpdate = behavior<EmbeddedActivityInboundUpdate>()
    val embeddedActivityUpdateV2 = behavior<EmbeddedActivityInstance>()

    fun getCoroutineDispatcher(): ExecutorCoroutineDispatcher = coroutineDispatcher

    fun getConnected(): Observable<Boolean> = connected.distinctUntilChanged()

    fun getConnectionReady(): Observable<Boolean> = connectionReady.distinctUntilChanged()
    fun getFreshSession(): Observable<Unit> = freshSession

    /**
     * Adopts [socket] and ties its lifetime to whether we hold a token.
     *
     * The socket is built by [StoreStream] because it needs REST for gateway discovery; everything
     * after construction belongs to this store.
     *
     * Fast Connect may already own the socket while the graph restores. Once foregrounded,
     * Android backgrounding follows the reference and leaves the socket up.
     */
    fun init(socket: GatewaySocket, awaitingRestoredToken: Boolean = false) {
        this.socket = socket
        var waitingForToken = awaitingRestoredToken
        tokenSubscription?.unsubscribe()
        tokenSubscription = stream.getAuthenticationInternal().observeAuthState()
            .map { it?.token }
            .distinctUntilChanged()
            .observeOn(scheduler)
            .subscribe({ token ->
                if (waitingForToken && token == null) return@subscribe
                waitingForToken = false
                handleTokenUpdate(token)
            }, {})

        backgroundedSubscription?.unsubscribe()
        backgroundedSubscription = Backgrounded.observe()
            .observeOn(scheduler)
            .subscribe({ backgrounded ->
                if (!backgrounded) {
                    this.socket?.resetBackoff()
                    if (token != null) this.socket?.connect()
                }
            }, {})

        guildSubscriptionsSubscription?.unsubscribe()
        guildSubscriptionsSubscription = stream.getGuildSubscriptionsInternal().observeSubscriptions()
            .subscribe({ payload ->
                socket.updateGuildSubscriptions(payload.guildId, payload.toGatewayPayload())
            }, {})
    }

    /**
     * Read at IDENTIFY time rather than captured when the token changes: the cache moves with every
     * message, and folding it into the socket's lifecycle input churned it on each one.
     */
    fun getIdentifyData(): IdentifyData? = token?.let {
        IdentifyData(
            it,
            stream.getClientDataStateInternal().getClientDataState()
                .toIdentifyData(stream.getUserSettingsProtoInternal().getDataVersion()),
        )
    }

    private fun handleTokenUpdate(token: String?) {
        val previous = this.token
        this.token = token
        when {
            token == null -> socket?.close()
            // `connect` only acts on a disconnected socket, so replacing a token in place would
            // leave the live connection identified as the account that just went away.
            previous != null -> socket?.reconnectWithNewSession("Token changed")
            else -> socket?.connect()
        }
    }

    // region Outgoing

    private fun requestIfSessionEstablished(request: (GatewaySocket) -> Unit): Boolean {
        val socket = this.socket ?: return false
        val established = socket.isSessionEstablished()
        if (established) request(socket)
        return established
    }

    fun presenceUpdate(
        status: ClientStatus?,
        since: Long? = clock.currentTimeMillis(),
        activities: List<Activity>? = null,
        afk: Boolean? = null,
    ): Boolean = requestIfSessionEstablished {
        it.presenceUpdate(status, since, activities.orEmpty(), afk)
    }

    fun qosActiveStateChanged(qos: HeartbeatQos) {
        socket?.handleActiveStateChange(qos)
    }

    fun updateTimeSpentSessionId(initializationTimestamp: Long, sessionId: String, clientLaunchId: String) {
        socket?.handleUpdateTimeSpentSessionId(initializationTimestamp, sessionId, clientLaunchId)
    }

    fun requestSoundboardSounds(guildIds: List<Long>): Boolean = requestIfSessionEstablished {
        it.requestSoundboardSounds(guildIds)
    }

    fun requestDeletedEntityIds(request: DeletedEntityIdsRequest): Boolean = requestIfSessionEstablished {
        it.requestDeletedEntityIds(request)
    }

    fun voiceStateUpdate(
        guildId: Long?,
        channelId: Long?,
        selfMute: Boolean,
        selfDeaf: Boolean,
        selfVideo: Boolean,
        preferredRegion: String? = null,
        shouldIncludePreferredRegion: Boolean = true,
    ): Boolean = requestIfSessionEstablished {
        it.voiceStateUpdate(guildId, channelId, selfMute, selfDeaf, selfVideo, preferredRegion, shouldIncludePreferredRegion)
    }

    @JvmOverloads
    fun requestGuildMembers(
        guildId: Long,
        query: String? = null,
        userIds: List<Long>? = null,
        limit: Int? = DEFAULT_MEMBER_REQUEST_LIMIT,
    ): Boolean = requestIfSessionEstablished {
        it.requestGuildMembers(listOf(guildId), query, userIds, limit)
    }

    fun updateGuildSubscriptions(guildId: Long, guildSubscriptions: GuildSubscriptions): Boolean =
        requestIfSessionEstablished { it.updateGuildSubscriptions(guildId, guildSubscriptions) }

    fun requestForumUnreads(guildId: Long, channelId: Long, threadMessagePairs: List<Pair<Long, Long>>): Boolean =
        requestIfSessionEstablished {
            it.requestForumUnreads(
                ForumUnreadsRequest(
                    guildId,
                    channelId,
                    threadMessagePairs.map { (threadId, ackMessageId) ->
                        ForumUnreadsRequestThread(threadId, ackMessageId)
                    },
                ),
            )
        }

    @StoreThread
    fun streamCreate(streamKey: String, preferredRegion: String?) {
        val applicationStream = ModelApplicationStream.decodeStreamKey(streamKey)
        requestIfSessionEstablished {
            it.streamCreate(
                applicationStream.type,
                applicationStream.channelId,
                (applicationStream as? ModelApplicationStream.GuildStream)?.guildId,
                preferredRegion,
            )
        }
    }

    @StoreThread
    fun streamWatch(streamKey: String) {
        requestIfSessionEstablished { it.streamWatch(streamKey) }
    }

    fun streamDelete(streamKey: String) {
        requestIfSessionEstablished { it.streamDelete(streamKey) }
    }

    private fun streamPing(streamKey: String) = requestIfSessionEstablished { it.streamPing(streamKey) }

    private fun voiceServerPing() = requestIfSessionEstablished(GatewaySocket::voiceServerPing)

    /**
     * Called when the RTC connection reports it is live. Pinging tells the backend which media
     * server we actually landed on; a stream pings by key, plain voice pings the voice server.
     */
    fun handleRtcConnectionLive(streamKey: String?) {
        if (streamKey != null) streamPing(streamKey) else voiceServerPing()
    }

    /** Leaves voice before the session is torn down so peers see us drop immediately. */
    fun handlePreLogout() {
        voiceStateUpdate(null, null, selfMute = false, selfDeaf = false, selfVideo = false, shouldIncludePreferredRegion = false)
    }

    fun resetOnError() {
        socket?.resetOnError()
    }

    fun simulateReconnectForTesting() {
        socket?.simulateReconnectForTesting()
    }

    fun disconnectForTesting() {
        socket?.close()
    }

    fun reconnectForTesting() {
        socket?.reconnectWithNewSession("")
    }

    fun disconnectForResumeTesting() {
        socket?.disconnectForResumeTesting()
    }

    fun resumeForTesting() {
        socket?.connect()
    }

    fun reconnectWithNewSession(reason: String) {
        socket?.reconnectWithNewSession(reason)
    }

    fun dispose() {
        tokenSubscription?.unsubscribe()
        tokenSubscription = null
        backgroundedSubscription?.unsubscribe()
        backgroundedSubscription = null
        guildSubscriptionsSubscription?.unsubscribe()
        guildSubscriptionsSubscription = null
        batches.dispose()
    }

    // endregion

    // region Incoming

    override fun handleConnected(connected: Boolean) {
        this.connected.onNext(connected)
    }

    override fun handleConnectionReady(connectionReady: Boolean) {
        this.connectionReady.onNext(connectionReady)
    }

    override fun handleFreshSession() {
        freshSession.onNext(Unit)
    }

    override fun handleDisconnect(authenticationFailed: Boolean) {
        if (authenticationFailed) stream.handleGatewayAuthenticationFailure()
    }

    /**
     * Fans one dispatch out to its subject.
     *
     * Every batch except the one owning [type] is flushed first, so a coalesced burst is always
     * applied before the event that followed it on the wire.
     */
    override fun handleDispatch(type: String?, data: Any) {
        batches.flushBatches(type)
        when (type) {
            "READY" -> ready.emit(data)
            "READY_SUPPLEMENTAL" -> readySupplemental.emit(data)
            "INITIAL_GUILD" -> initialGuild.emit(data)

            // Current user
            "USER_UPDATE" -> userUpdate.emit(data)
            "USER_SETTINGS_PROTO_UPDATE" -> userSettingsProtoUpdate.emit(data)
            "USER_GUILD_SETTINGS_UPDATE" -> userGuildSettingsUpdate.emit(data)
            "NOTIFICATION_SETTINGS_UPDATE" -> notificationSettingsUpdate.emit(data)
            "USER_NOTE_UPDATE" -> userNoteUpdate.emit(data)
            "USER_REQUIRED_ACTION_UPDATE" -> userRequiredActionUpdate.emit(data)
            "AUTH_SESSION_CHANGE" -> authSessionChange.emit(data)
            "USER_CONNECTIONS_UPDATE" -> userConnectionUpdate.emit(Unit)
            "USER_SUBSCRIPTIONS_UPDATE" -> userSubscriptionsUpdate.emit(Unit)
            "USER_PAYMENT_SOURCES_UPDATE" -> userPaymentSourcesUpdate.emit(Unit)
            "SESSIONS_REPLACE" -> sessionsReplace.emit(data)

            // Relationships
            "RELATIONSHIP_ADD" -> relationshipAdd.emit(data)
            "RELATIONSHIP_UPDATE" -> relationshipUpdate.emit(data)
            "RELATIONSHIP_REMOVE" -> relationshipRemove.emit(data)
            "FRIEND_SUGGESTION_CREATE" -> friendSuggestionCreate.emit(data)
            "FRIEND_SUGGESTION_DELETE" -> friendSuggestionDelete.emit(data)

            // Guilds
            "GUILD_CREATE" -> guildCreate.emit(data)
            "BACKGROUND_SYNC" -> backgroundSync.emit(data)
            "DELETED_ENTITY_IDS" -> deletedEntityIds.emit(data)
            "GUILD_UPDATE" -> guildUpdate.emit(data)
            "GUILD_DELETE" -> guildDeleted.emit(data)
            "GUILD_BAN_ADD" -> guildBanAdd.emit(data)
            "GUILD_BAN_REMOVE" -> guildBanRemove.emit(data)
            "GUILD_EMOJIS_UPDATE" -> guildEmojisUpdate.emit(data)
            "GUILD_STICKERS_UPDATE" -> guildStickersUpdate.emit(data)
            "SOUNDBOARD_SOUNDS" -> soundboardSounds.emit(data)
            "GUILD_SOUNDBOARD_SOUNDS_UPDATE" -> guildSoundboardSoundsUpdate.emit(data)
            "GUILD_SOUNDBOARD_SOUND_CREATE", "GUILD_SOUNDBOARD_SOUND_UPDATE" ->
                guildSoundboardSoundCreateOrUpdate.emit(data)
            "GUILD_SOUNDBOARD_SOUND_DELETE" -> guildSoundboardSoundDelete.emit(data)
            "GUILD_INTEGRATIONS_UPDATE" -> guildIntegrationsUpdate.emit(data)
            "GUILD_APPLICATION_COMMAND_INDEX_UPDATE" -> guildApplicationCommandIndexUpdate.emit(data)
            "APPLICATION_COMMAND_AUTOCOMPLETE_RESPONSE" -> applicationCommandAutocompleteResult.emit(data)

            // Guild membership. GUILD_MEMBER_UPDATE shares the add subject: the payload is a whole
            // member row, so consumers upsert either way.
            "GUILD_MEMBER_ADD", "GUILD_MEMBER_UPDATE" -> guildMembersAdd.emit(data)
            "GUILD_MEMBER_REMOVE" -> guildMemberRemove.emit(data)
            "GUILD_MEMBERS_CHUNK" -> guildMembersChunk.emit(data)
            "GUILD_MEMBER_LIST_UPDATE" -> guildMemberListUpdate.emit(data)
            "GUILD_JOIN_REQUEST_CREATE", "GUILD_JOIN_REQUEST_UPDATE" -> guildJoinRequestCreateOrUpdate.emit(data)
            "GUILD_JOIN_REQUEST_DELETE" -> guildJoinRequestDelete.emit(data)

            // Guild roles
            "GUILD_ROLE_CREATE" -> guildRoleCreate.emit(data)
            "GUILD_ROLE_UPDATE" -> guildRoleUpdate.emit(data)
            "GUILD_ROLE_DELETE" -> guildRoleDelete.emit(data)

            // Guild scheduled events
            "GUILD_SCHEDULED_EVENT_CREATE" -> guildScheduledEventCreate.emit(data)
            "GUILD_SCHEDULED_EVENT_UPDATE" -> guildScheduledEventUpdate.emit(data)
            "GUILD_SCHEDULED_EVENT_DELETE" -> guildScheduledEventDelete.emit(data)
            "GUILD_SCHEDULED_EVENT_USER_ADD" -> guildScheduledEventUserAdd.emit(data)
            "GUILD_SCHEDULED_EVENT_USER_REMOVE" -> guildScheduledEventUserRemove.emit(data)

            // Channels and threads
            "CHANNEL_CREATE", "CHANNEL_UPDATE" -> channelCreateOrUpdate.emit(data)
            "CHANNEL_DELETE" -> channelDeleted.emit(data)
            "CHANNEL_PINS_UPDATE" -> channelPinsUpdate.emit(data)
            "CHANNEL_RECIPIENT_ADD" -> channelRecipientAdd.emit(data)
            "CHANNEL_RECIPIENT_REMOVE" -> channelRecipientRemove.emit(data)
            "CHANNEL_UNREAD_UPDATE" -> channelUnreadUpdate.emit(data)
            "PASSIVE_UPDATE_V2" -> passiveUpdateV2.emit(data)
            "THREAD_CREATE", "THREAD_UPDATE" -> threadCreateOrUpdate.emit(data)
            "THREAD_DELETE" -> threadDelete.emit(data)
            "THREAD_LIST_SYNC" -> threadListSync.emit(data)
            "THREAD_MEMBER_UPDATE" -> threadMemberUpdate.emit(data)
            "THREAD_MEMBERS_UPDATE" -> threadMembersUpdate.emit(data)
            "THREAD_MEMBER_LIST_UPDATE" -> threadMemberListUpdate.emit(data)
            "FORUM_UNREADS" -> forumUnreads.emit(data)

            // Messages
            "MESSAGE_CREATE" -> messageCreate.emit(data)
            "MESSAGE_UPDATE" -> messageUpdate.emit(data)
            "MESSAGE_DELETE", "MESSAGE_DELETE_BULK" -> messageDelete.emit(data)
            "MESSAGE_ACK" -> messageAck.emit(data)
            "MESSAGE_REACTION_ADD" -> messageReactionAdd.emit(data)
            "MESSAGE_REACTION_ADD_MANY" -> messageReactionAddMany.emit(data)
            "MESSAGE_POLL_VOTE_ADD_MANY" -> pollVoteAddMany.emit(data)
            "MESSAGE_REACTION_REMOVE" -> messageReactionRemove.emit(data)
            "MESSAGE_REACTION_REMOVE_ALL" -> messageReactionRemoveAll.emit(data)
            "MESSAGE_REACTION_REMOVE_EMOJI" -> messageReactionRemoveEmoji.emit(data)
            "MESSAGE_POLL_VOTE_ADD" -> messagePollVoteAdd.emit(data)
            "MESSAGE_POLL_VOTE_REMOVE" -> messagePollVoteRemove.emit(data)

            // Presence and typing
            EVENT_PRESENCE_UPDATE -> presenceUpdate.onNextAny(data)
            "PRESENCES_REPLACE" -> presenceReplace.emit(data)
            "TYPING_START" -> typingStart.emit(data)

            // Voice, calls and streams
            EVENT_VOICE_STATE_UPDATE -> voiceStateUpdate.onNextAny(data)
            // Replayed one by one so the batch is indistinguishable downstream.
            "VOICE_STATE_UPDATE_BATCH" ->
                (data as VoiceStateUpdateBatch).voiceStates.forEach(voiceStateUpdate::onNext)
            "VOICE_SERVER_UPDATE" -> voiceServerUpdate.emit(data)
            "CALL_CREATE", "CALL_UPDATE" -> callCreateOrUpdate.emit(data)
            "CALL_DELETE" -> callDelete.emit(data)
            "STREAM_CREATE" -> streamCreate.emit(data)
            "STREAM_UPDATE" -> streamUpdate.emit(data)
            "STREAM_DELETE" -> streamDelete.emit(data)
            "STREAM_SERVER_UPDATE" -> streamServerUpdate.emit(data)

            // Stage instances, interactions and embedded activities
            "STAGE_INSTANCE_CREATE" -> stageInstanceCreate.emit(data)
            "STAGE_INSTANCE_UPDATE" -> stageInstanceUpdate.emit(data)
            "STAGE_INSTANCE_DELETE" -> stageInstanceDelete.emit(data)
            "INTERACTION_CREATE" -> interactionCreate.emit(data)
            "INTERACTION_SUCCESS" -> interactionSuccess.emit(data)
            "INTERACTION_FAILURE" -> interactionFailure.emit(data)
            "INTERACTION_MODAL_CREATE" -> interactionModalCreate.emit(data)
            "EMBEDDED_ACTIVITY_UPDATE" -> embeddedActivityInboundUpdate.emit(data)
            "EMBEDDED_ACTIVITY_UPDATE_V2" -> embeddedActivityUpdateV2.emit(data)
        }
    }

    // endregion

    private companion object {
        const val EVENT_VOICE_STATE_UPDATE = "VOICE_STATE_UPDATE"
        const val EVENT_PRESENCE_UPDATE = "PRESENCE_UPDATE"
        const val DEFAULT_MEMBER_REQUEST_LIMIT = 100

        fun <T> behavior(): SerializedSubject<T, T> = SerializedSubject(BehaviorSubject.create())
        fun <T> behavior(initialValue: T): SerializedSubject<T, T> =
            SerializedSubject(BehaviorSubject.create(initialValue))

        fun <T> publish(): SerializedSubject<T, T> = SerializedSubject(PublishSubject.create())

        /**
         * The dispatch table only knows payloads as [Any]; the parser already produced the type this
         * subject declares, so the cast is safe and keeps the table free of per-event casts.
         */
        @Suppress("UNCHECKED_CAST")
        fun <T> SerializedSubject<T, T>.emit(data: Any) = (this as SerializedSubject<Any, Any>).onNext(data)
    }
}
