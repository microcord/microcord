package com.discord.stores

import android.util.Log
import com.discord.api.channel.Channel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.models.message.Message
import com.discord.stores.StoreChat.InteractionState
import com.discord.utilities.rx.filterNull
import com.discord.utilities.rx.takeSingleUntilTimeout
import java.util.Collections
import java.util.Random
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject

/**
 * Decides *when* to fetch message history, and publishes what came back.
 *
 * It never decides *what* the list looks like — that is [StoreMessages]. What it owns is the
 * scheduling policy, and almost all of the complexity is there:
 *
 * * **One request at a time, per channel.** [tryLoadMessages] is the only entry point; every
 *   trigger (channel selected, scrolled to an edge, reconnected, retry timer, jump) funnels into
 *   it and it decides whether anything should actually be requested.
 * * **Driven by scroll position.** Pagination is not a scroll listener. [StoreChat] publishes an
 *   [InteractionState]; reaching the top requests older messages, and reaching the bottom of a
 *   *detached* channel requests newer ones. Without that subscription, [loadBefore] and
 *   [loadAfter] have no callers at all and infinite scroll cannot happen.
 * * **Backgrounded means idle.** [backgrounded] starts `true`, so nothing is fetched until the UI
 *   says it is visible.
 * * **Jittered backoff.** A failed load retries on a globally doubling delay with up to
 *   [RETRY_JITTER_MS] of jitter, so a server-side outage does not produce a synchronised retry
 *   storm from every client that saw it.
 */
class StoreMessagesLoader @JvmOverloads constructor(
    private val loader: (LoadRequest, (Result<List<Message>>) -> Unit) -> Unit,
    private val requestSize: Int = MESSAGE_REQUEST_SIZE,
    private val scheduler: Scheduler = Schedulers.computation(),
    private val retryJitter: () -> Int = { Random().nextInt(RETRY_JITTER_MS) },
) {
    enum class Direction { INITIAL, BEFORE, AFTER, AROUND }

    /**
     * One history request.
     *
     * [scrollTarget] is not the same as [anchorMessageId]: it is what the list should scroll to
     * once the chunk lands, and it doubles as the jump discriminator — [SCROLL_TO_LAST_UNREAD]
     * and [SCROLL_TO_LATEST] are sentinels meaning "not a jump", see [isScrollToAction].
     */
    data class LoadRequest(
        val channelId: Long,
        val direction: Direction,
        val anchorMessageId: Long? = null,
        val limit: Int = MESSAGE_REQUEST_SIZE,
        val scrollTarget: Long = SCROLL_TO_LAST_UNREAD,
    )

    /**
     * A loaded page, with the four flags [StoreMessagesHolder] needs to merge it.
     *
     * [isPresent] means the chunk reaches the live tail, which is what re-attaches a detached
     * channel; [isAppendingTop] means it extends the window upwards, which is what decides which
     * end gets trimmed.
     */
    data class ChannelChunk(
        val channelId: Long,
        val messages: List<Message>,
        val isInitial: Boolean,
        val isPresent: Boolean,
        val isAppendingTop: Boolean,
        val isJump: Boolean,
    )

    /**
     * Per-channel loading state.
     *
     * [isTouchedSinceLastJump] gates edge-driven pagination — see [StoreChat.InteractionState].
     * [newestSentByUserMessageId] is the newest message in the last chunk authored by the current
     * user, used to decide whether the "jump to present" affordance should appear.
     */
    data class ChannelLoadedState(
        val isInitialMessagesLoaded: Boolean = false,
        val isOldestMessagesLoaded: Boolean = false,
        val isLoadingMessages: Boolean = false,
        val isTouchedSinceLastJump: Boolean = false,
        val newestSentByUserMessageId: Long? = null,
    )

    /**
     * The cross-store lookups the loader needs but does not own.
     *
     * Supplied by `StoreStream` after the graph is built. Absent, the loader still performs
     * explicit loads; only interaction-driven pagination and the full [jumpToMessage] flow are
     * inert, which is exactly the state this project was in before they were wired.
     */
    class Dependencies(
        val observeChannelMessages: (channelId: Long) -> Observable<List<Message>>,
        val observeMessage: (channelId: Long, messageId: Long) -> Observable<Message?>,
        val observeChannel: (channelId: Long) -> Observable<Channel?>,
        val getChannel: (channelId: Long) -> Channel?,
        val observeConnectionOpen: () -> Observable<Boolean>,
        val selectChannel: (Channel) -> Unit,
        val meUserId: () -> Long,
    )

    private data class PendingJump(val channelId: Long, val messageId: Long)

    private val channelLoadedStates = HashMap<Long, ChannelLoadedState>()
    private val channelLoadedStateSubject =
        BehaviorSubject.create<Map<Long, ChannelLoadedState>>(emptyMap()).toSerialized()

    /**
     * Replays the last chunk so a subscriber that attaches after the load still renders it.
     *
     * Not wrapped in a `SerializedSubject` like its non-null siblings: `Subject.toSerialized()`
     * does not resolve for a nullable element type under this Kotlin/RxJava 1 pairing. It is not
     * needed either — every emission below happens inside this class's own monitor.
     */
    private val channelMessageChunksSubject: BehaviorSubject<ChannelChunk?> =
        BehaviorSubject.create(null as ChannelChunk?)

    /** Nullable so [clearScrollTo] can retract a pending scroll instruction. See above. */
    private val scrollToSubject: BehaviorSubject<Long?> = BehaviorSubject.create(null as Long?)
    private val channelMessagesLoadingSubject = BehaviorSubject.create(false).toSerialized()

    private var dependencies: Dependencies? = null

    private var selectedChannelId = 0L
    private var authed = false
    /**
     * Whether the app is in the background; nothing loads while it is.
     *
     * Seeded backgrounded, as the reference does: `Backgrounded` starts that way too and the first
     * resumed activity clears it before anything is on screen to load into.
     */
    private var backgrounded = true

    private var detachedChannels: Set<Long> = emptySet()
    private var interactionState: InteractionState? = null

    private var loadingMessagesRetryDelayMillis = RETRY_DELAY_DEFAULT
    private var loadingMessagesSubscription: Subscription? = null
    private var delayLoadingMessagesSubscription: Subscription? = null
    private var jumpChannelSubscription: Subscription? = null
    private var pendingJump: PendingJump? = null
    private var lastRequest: LoadRequest? = null
    private var awaitingFreshSessionReady = false
    private var bottomPageAnchorChannelId: Long? = null
    private var bottomPageAnchorMessageId: Long? = null

    /**
     * Subscribes to the scroll position and the detached-channel set.
     *
     * This is the wiring whose absence made [loadBefore] and [loadAfter] unreachable.
     */
    fun init(chat: StoreChat, messages: StoreMessages, dependencies: Dependencies) {
        this.dependencies = dependencies
        chat.observeInteractionState().subscribe({ handleChatInteraction(it) }, { })
        messages.getAllDetached().subscribe({ handleChatDetached(it) }, { })
    }

    // region external triggers

    @Synchronized
    fun handleAuthToken(token: String?) {
        authed = token != null
        if (!authed) {
            jumpChannelSubscription?.unsubscribe()
            jumpChannelSubscription = null
            pendingJump = null
        }
        tryLoadMessages(resetRetry = true)
    }

    @Synchronized
    fun handleConnected(@Suppress("UNUSED_PARAMETER") connected: Boolean) {
        tryLoadMessages(resetRetry = true, resetDelay = true)
    }

    /** A fresh IDENTIFY cannot replay missed messages; READY will identify stale channels. */
    @Synchronized
    fun handleFreshSession() {
        awaitingFreshSessionReady = true
    }

    @Synchronized
    fun handleFreshSessionReady(
        serverLastMessageIds: Map<Long, Long>,
        localLastMessageId: (Long) -> Long?,
    ): Set<Long> {
        if (!awaitingFreshSessionReady) return emptySet()
        awaitingFreshSessionReady = false
        val staleChannels = channelLoadedStates.keys.filterTo(HashSet()) { channelId ->
            serverLastMessageIds[channelId]?.let { it != (localLastMessageId(channelId) ?: 0L) } == true
        }
        staleChannels.forEach { channelId ->
            channelLoadedStates[channelId] = requireNotNull(channelLoadedStates[channelId]).let {
                it.copy(isInitialMessagesLoaded = false, isOldestMessagesLoaded = false, isLoadingMessages = false)
            }
        }
        channelLoadedStateSubject.onNext(Collections.unmodifiableMap(HashMap(channelLoadedStates)))
        tryLoadMessages(resetRetry = true, resetDelay = true)
        return staleChannels
    }

    /**
     * Nothing loads while backgrounded, so coming forward is also where a channel that went stale
     * behind the user's back gets refetched — the reference refetches the selected channel on the
     * same signal.
     */
    @Synchronized
    fun handleBackgrounded(backgrounded: Boolean) {
        this.backgrounded = backgrounded
        tryLoadMessages(resetRetry = true)
    }

    /**
     * Switches channels.
     *
     * The *previous* channel's touch flag is cleared first: re-opening a channel should start
     * from its saved position without immediately paginating.
     */
    @Synchronized
    @StoreThread
    fun handleChannelSelected(channel: Channel?) =
        handleChannelSelected(channel?.id?.takeUnless { channel.isThreadOnlyChannel() } ?: 0L)

    @Synchronized
    @StoreThread
    fun handleChannelSelected(channelId: Long) {
        channelLoadedStateUpdate(selectedChannelId) { it.copy(isTouchedSinceLastJump = false) }
        selectedChannelId = channelId
        val jump = takePendingJump(channelId)
        if (jump != null) {
            log("Selected jump channel [$channelId], loading target [${jump.messageId}]")
            loadJumpTarget(jump.channelId, jump.messageId)
        } else {
            tryLoadMessages(force = true, resetRetry = true)
        }
    }

    /**
     * Joining a voice channel loads its text history without selecting it.
     *
     * The chat overlay renders that channel while the user is looking at a different one, so the
     * load is targeted rather than going through [selectedChannelId].
     */
    @Synchronized
    @StoreThread
    fun handleVoiceChannelJoined(voiceChannelId: Long) =
        tryLoadMessages(force = true, resetRetry = true, targetChannelId = voiceChannelId)

    @Synchronized
    private fun handleChatInteraction(interactionState: InteractionState) {
        channelLoadedStateUpdate(interactionState.channelId) {
            it.copy(isTouchedSinceLastJump = interactionState.isTouchedSinceLastJump)
        }
        if (!interactionState.isAtBottom) {
            bottomPageAnchorChannelId = null
            bottomPageAnchorMessageId = null
        }
        this.interactionState = interactionState
        tryLoadMessages(resetRetry = true)
    }

    @Synchronized
    private fun handleChatDetached(detachedChannels: Set<Long>) {
        this.detachedChannels = detachedChannels
        log("Loaded detached channel state: $detachedChannels")
        tryLoadMessages(resetRetry = true)
    }

    // endregion

    // region explicit loads

    @Synchronized
    @JvmOverloads
    fun loadInitial(channelId: Long = selectedChannelId) =
        tryLoadMessages(force = true, targetChannelId = channelId, targetMessageId = SCROLL_TO_LAST_UNREAD)

    @Synchronized
    fun loadBefore(channelId: Long, messageId: Long) {
        if (channelLoadedStates[channelId]?.isOldestMessagesLoaded == true) return
        requestMessages(channelId, messageId = null, before = messageId, after = null)
    }

    @Synchronized
    fun loadAfter(channelId: Long, messageId: Long) =
        requestMessages(channelId, messageId = null, before = null, after = messageId)

    /**
     * Scrolls to [messageId], loading the surrounding window and switching channels if needed.
     *
     * A cross-channel jump stays pending until [handleChannelSelected] receives the target channel.
     * This ordering matters: starting the around-target request from a parallel selection observer
     * lets the ordinary initial load race it and cancel whichever request subscribed first.
     *
     * With no [Dependencies] installed this degrades to the second half of the first arm: fetch
     * the window and scroll.
     */
    @Synchronized
    fun jumpToMessage(channelId: Long, messageId: Long) {
        if (channelId <= 0L || messageId <= 0L) return
        log("Jump requested for channel [$channelId], message [$messageId]")
        val deps = dependencies
        if (deps == null) {
            handleTargetChannelSelected(channelId, messageId, null)
            return
        }

        val jump = PendingJump(channelId, messageId)
        pendingJump = jump
        jumpChannelSubscription?.unsubscribe()
        if (selectedChannelId == channelId) {
            pendingJump = null
            loadJumpTarget(channelId, messageId)
            return
        }
        deps.getChannel(channelId)?.let { channel ->
            log("Resolved jump channel [$channelId] from local store")
            handleTargetChannelResolved(deps, channelId, channel)
            return
        }

        jumpChannelSubscription = deps.observeConnectionOpen()
            .take(1)
            .switchMap { isConnected ->
                val timeout =
                    if (isConnected) CHANNEL_RESOLVE_TIMEOUT_MS else CHANNEL_RESOLVE_TIMEOUT_MS * 3
                deps.observeChannel(channelId).filterNull().takeSingleUntilTimeout(timeout)
            }
            .subscribe(
                { channel -> handleTargetChannelResolved(deps, channelId, channel) },
                {
                    synchronized(this) {
                        if (pendingJump == jump) pendingJump = null
                    }
                    Log.w(LOG_TAG, "[MessageLoader] Dropped jump for channel [$channelId], message [$messageId]")
                },
            )
    }

    private fun loadJumpTarget(channelId: Long, messageId: Long) {
        val deps = dependencies
        if (deps == null) {
            handleTargetChannelSelected(channelId, messageId, null)
            return
        }
        deps.observeMessage(channelId, messageId).take(1).subscribe(
            { message -> handleTargetChannelSelected(channelId, messageId, message) },
            { handleTargetChannelSelected(channelId, messageId, null) },
        )
    }

    @Synchronized
    private fun takePendingJump(channelId: Long): PendingJump? =
        pendingJump?.takeIf { it.channelId == channelId }?.also { pendingJump = null }

    /**
     * Jumps to the newest message.
     *
     * When the channel is attached and already loaded, the newest message is on screen and this
     * is a pure scroll — refetching would be a wasted round trip. Only a detached or unloaded
     * channel actually reloads.
     */
    @Synchronized
    fun requestNewestMessages() {
        val state = channelLoadedStates[selectedChannelId]
        if (selectedChannelId !in detachedChannels && state?.isInitialMessagesLoaded == true) {
            scrollToSubject.onNext(SCROLL_TO_LATEST)
            return
        }
        channelLoadedStateUpdate(selectedChannelId) { it.copy(isInitialMessagesLoaded = false) }
        tryLoadMessages(force = true, targetMessageId = SCROLL_TO_LATEST)
    }

    @Synchronized
    fun retry() {
        lastRequest?.let { request ->
            requestMessages(
                request.channelId,
                messageId = request.scrollTarget.takeIf { request.direction == Direction.AROUND },
                before = request.anchorMessageId.takeIf { request.direction == Direction.BEFORE },
                after = request.anchorMessageId.takeIf { request.direction == Direction.AFTER },
            )
        }
    }

    /** Retracts a pending scroll instruction so it is not replayed to the next subscriber. */
    @Synchronized
    fun clearScrollTo() {
        scrollToSubject.onNext(null)
    }

    // endregion

    // region reads

    fun observeChunks(): Observable<ChannelChunk> = channelMessageChunksSubject.filterNull()
    fun get(): Observable<ChannelChunk> = observeChunks()

    @Synchronized
    fun bottomPageAnchor(channelId: Long): Long? =
        bottomPageAnchorMessageId.takeIf { bottomPageAnchorChannelId == channelId }

    fun observeScrollTo(): Observable<Long> = scrollToSubject.filterNull()
    fun getScrollTo(): Observable<Long> = observeScrollTo()
    fun observeChannelMessagesLoading(): Observable<Boolean> = channelMessagesLoadingSubject.distinctUntilChanged()

    fun getMessagesLoadedState(channelId: Long): Observable<ChannelLoadedState> =
        channelLoadedStateSubject.map { it[channelId] ?: ChannelLoadedState() }.distinctUntilChanged()

    @Synchronized
    fun reset() {
        channelLoadedStatesReset()
        delayLoadingMessagesSubscription?.unsubscribe()
        delayLoadingMessagesSubscription = null
        selectedChannelId = 0
        interactionState = null
        detachedChannels = emptySet()
        lastRequest = null
        awaitingFreshSessionReady = false
        channelMessagesLoadingSubject.onNext(false)
    }

    // endregion

    /**
     * The single scheduling core. Every trigger routes here.
     *
     * Reading order matches the reference:
     *
     * 1. a pending debounce is either cancelled ([resetDelay]) or, unless [force], swallows the
     *    call — this is what stops a burst of scroll events becoming a burst of requests;
     * 2. a non-zero [delay] re-enters after a timer instead of loading now;
     * 3. a channel already loading is skipped unless [force];
     * 4. nothing loads at all while backgrounded, unauthenticated, or with no channel;
     * 5. finally, one of three things happens — an explicit [targetMessageId] fetch, a first load,
     *    or an edge-driven page.
     */
    @Suppress("LongParameterList")
    @Synchronized
    private fun tryLoadMessages(
        delay: Long = 0L,
        force: Boolean = false,
        resetRetry: Boolean = false,
        resetDelay: Boolean = false,
        targetChannelId: Long? = null,
        targetMessageId: Long? = null,
    ) {
        val channelId = targetChannelId ?: selectedChannelId

        if (delayLoadingMessagesSubscription != null && resetDelay) {
            delayLoadingMessagesSubscription?.unsubscribe()
            delayLoadingMessagesSubscription = null
        }
        if (delayLoadingMessagesSubscription != null && !force) return

        if (delay > 0L) {
            delayLoadingMessagesSubscription = Observable.timer(delay, TimeUnit.MILLISECONDS, scheduler)
                .subscribe({ tryLoadMessages(resetDelay = true) }, { })
            return
        }

        if (resetRetry) loadingMessagesRetryDelayMillis = RETRY_DELAY_DEFAULT

        val state = channelLoadedStates[channelId]
        if (state != null && state.isLoadingMessages && !force) return
        loadingMessagesSubscription?.unsubscribe()
        if (channelId <= 0L || backgrounded || !authed) return

        when {
            targetMessageId != null -> requestMessages(channelId, targetMessageId, before = null, after = null)

            state == null || !state.isInitialMessagesLoaded ->
                requestMessages(channelId, SCROLL_TO_LAST_UNREAD, before = null, after = null)

            else -> {
                channelMessagesLoadingSubject.onNext(false)
                loadAdjacentPage(channelId, state)
            }
        }
    }

    /**
     * Requests the page the current scroll position implies, if any.
     *
     * The anchor has to come from the store rather than from this class: the loader does not keep
     * the message list, and the edges of that list are exactly what "load before/after" needs.
     */
    private fun loadAdjacentPage(channelId: Long, state: ChannelLoadedState) {
        val interaction = interactionState ?: return
        if (interaction.channelId != channelId) return
        val shouldRequestOlder = interaction.isAtTop && !state.isOldestMessagesLoaded
        val shouldRequestNewer = interaction.isAtBottom && channelId in detachedChannels
        if (!shouldRequestOlder && !shouldRequestNewer) return

        val observeChannelMessages = dependencies?.observeChannelMessages ?: return
        channelLoadedStateUpdate(channelId) { it.copy(isLoadingMessages = true) }
        observeChannelMessages(channelId)
            .takeSingleUntilTimeout(emitError = false)
            .subscribe({ messages ->
                synchronized(this) {
                    when {
                        messages.isEmpty() ->
                            channelLoadedStateUpdate(channelId) { it.copy(isLoadingMessages = false) }
                        shouldRequestOlder ->
                            requestMessages(channelId, null, before = messages.first().id, after = null)
                        shouldRequestNewer ->
                            requestMessages(channelId, null, before = null, after = messages.last().id)
                        else -> Unit
                    }
                }
            }, { handleLoadMessagesError(channelId) })
    }

    /** Issues one request through the injected [loader] and marks the channel as loading. */
    private fun requestMessages(channelId: Long, messageId: Long?, before: Long?, after: Long?) {
        val direction = when {
            before != null -> Direction.BEFORE
            after != null -> Direction.AFTER
            messageId != null && !isScrollToAction(messageId) -> Direction.AROUND
            else -> Direction.INITIAL
        }
        val request = LoadRequest(
            channelId = channelId,
            direction = direction,
            anchorMessageId = before ?: after ?: messageId?.takeIf { !isScrollToAction(it) },
            limit = requestSize,
            scrollTarget = messageId ?: SCROLL_TO_LAST_UNREAD,
        )
        lastRequest = request
        channelLoadedStateUpdate(channelId) { it.copy(isLoadingMessages = true) }
        channelMessagesLoadingSubject.onNext(true)
        loader(request) { result ->
            result.fold(
                onSuccess = { messages -> handleLoadedMessages(messages, request, before, after) },
                onFailure = { handleLoadMessagesError(channelId) },
            )
        }
    }

    /**
     * Publishes a loaded page and decides where the list should scroll.
     *
     * JADX could not decompile the reference method (296 instructions skipped); the derivations
     * below come from the Vineflower decompile of the DEX:
     *
     * * `isAllLoaded` — a short page means the end of history in that direction;
     * * `isJump` — the request carried a real message id rather than a scroll sentinel;
     * * `isInitial` — neither a jump nor a page in either direction;
     * * `isPresent` — an initial load, or a downward page that reached the end.
     *
     * The scroll rules follow: an explicit "jump to latest" scrolls there only if the page was
     * non-empty, an ordinary initial load scrolls to the last unread marker, a jump scrolls to
     * the target — or, if the target was not in the page, gives up and marks the channel read.
     */
    private fun handleLoadedMessages(
        messages: List<Message>,
        request: LoadRequest,
        before: Long?,
        after: Long?,
    ) = synchronized(this) {
        val channelId = request.channelId
        val messageId = request.scrollTarget
        val isAllLoaded = messages.size < requestSize
        val isAppendingBottom = after != null
        val isAppendingTop = before != null
        val isJump = !isScrollToAction(messageId)
        val isInitial = !isJump && !isAppendingBottom && !isAppendingTop
        val isPresent = isInitial || (isAppendingBottom && isAllLoaded)

        val meUserId = dependencies?.meUserId?.invoke()
        channelLoadedStateUpdate(channelId) { state ->
            state.copy(
                isInitialMessagesLoaded = isInitial || state.isInitialMessagesLoaded,
                isOldestMessagesLoaded =
                    if (isInitial || isAppendingTop) isAllLoaded else state.isOldestMessagesLoaded,
                isLoadingMessages = false,
                // The page arrives newest-first, so the first authored match is the newest one.
                newestSentByUserMessageId = messages.firstOrNull { it.author?.id == meUserId }?.id,
            )
        }
        channelMessagesLoadingSubject.onNext(false)
        if (isAppendingBottom && !isJump) {
            bottomPageAnchorChannelId = channelId
            bottomPageAnchorMessageId = after
        }
        channelMessageChunksSubject.onNext(
            ChannelChunk(channelId, messages, isInitial, isPresent, isAppendingTop, isJump),
        )

        val shouldScrollToNewest = isInitial && messages.isNotEmpty() && messageId == SCROLL_TO_LATEST
        when {
            shouldScrollToNewest -> scrollToSubject.onNext(SCROLL_TO_LATEST)
            isInitial -> scrollToSubject.onNext(SCROLL_TO_LAST_UNREAD)
        }

        if (isJump) {
            val target = messages.firstOrNull { it.id == messageId }
            if (target != null) scrollToSubject.onNext(target.id) else onJumpTargetMissing(channelId)
        }
        if (!isJump && !shouldScrollToNewest) clearScrollTo()

        log("Loaded ${messages.size} messages for channel [$channelId], all loaded: $isAllLoaded.")
        tryLoadMessages(delay = RETRY_DELAY_DEFAULT)
    }

    /**
     * Backoff on failure: double the *global* delay, plus jitter, capped at [RETRY_DELAY_MAX].
     *
     * The delay is global rather than per channel because it is a statement about the server, not
     * about one channel, and every channel would otherwise re-learn the same outage separately.
     */
    private fun handleLoadMessagesError(channelId: Long) = synchronized(this) {
        channelLoadedStateUpdate(channelId) { it.copy(isLoadingMessages = false) }
        if (loadingMessagesRetryDelayMillis < RETRY_DELAY_MAX) {
            loadingMessagesRetryDelayMillis = loadingMessagesRetryDelayMillis * 2 + retryJitter()
        }
        log("Failed to load messages for channel [$channelId], retrying in ${loadingMessagesRetryDelayMillis}ms")
        tryLoadMessages(delay = loadingMessagesRetryDelayMillis)
    }

    private fun handleTargetChannelSelected(channelId: Long, messageId: Long, message: Message?) {
        synchronized(this) {
            if (message != null) {
                log("Jump target [$messageId] is already loaded in channel [$channelId]")
                scrollToSubject.onNext(message.id)
            } else {
                log("Fetching jump target [$messageId] in channel [$channelId]")
                channelLoadedStateUpdate(channelId) { it.copy(isOldestMessagesLoaded = false) }
                tryLoadMessages(force = true, targetChannelId = channelId, targetMessageId = messageId)
            }
        }
    }

    private fun handleTargetChannelResolved(deps: Dependencies, channelId: Long, channel: Channel) {
        val alreadySelected = synchronized(this) {
            channelLoadedStateUpdate(channelId) { it.copy(isInitialMessagesLoaded = true) }
            channel.id == selectedChannelId
        }
        if (alreadySelected) {
            takePendingJump(channelId)?.let { loadJumpTarget(it.channelId, it.messageId) }
        } else {
            deps.selectChannel(channel)
        }
    }

    /** A jump whose target is not in the fetched window: give up and clear the unread marker. */
    private fun onJumpTargetMissing(channelId: Long) =
        log("Jump target missing from loaded window for channel [$channelId]")

    private fun channelLoadedStatesReset() {
        channelLoadedStates.clear()
        channelLoadedStateSubject.onNext(emptyMap())
        loadingMessagesSubscription?.unsubscribe()
        loadingMessagesRetryDelayMillis = RETRY_DELAY_DEFAULT
        log("Disconnected, resetting all message loaded states.")
    }

    private fun channelLoadedStateUpdate(channelId: Long, update: (ChannelLoadedState) -> ChannelLoadedState) {
        val current = channelLoadedStates[channelId] ?: ChannelLoadedState()
        channelLoadedStates[channelId] = update(current)
        channelLoadedStateSubject.onNext(Collections.unmodifiableMap(HashMap(channelLoadedStates)))
    }

    private fun log(message: String) = Log.i(LOG_TAG, "[MessageLoader] $message")

    companion object {
        const val MESSAGE_REQUEST_SIZE = 50

        /** Scroll to the unread divider rather than to a specific message. */
        const val SCROLL_TO_LAST_UNREAD = 0L

        /** Scroll to the newest message. */
        const val SCROLL_TO_LATEST = 1L

        private const val RETRY_DELAY_DEFAULT = 1_500L
        private const val RETRY_DELAY_MAX = 30_000L
        private const val RETRY_JITTER_MS = 2_000
        private const val CHANNEL_RESOLVE_TIMEOUT_MS = 1_000L
        private const val LOG_TAG = "StoreMessagesLoader"

        /** Whether [targetMessageId] is a scroll sentinel rather than a real message id. */
        @JvmStatic
        fun isScrollToAction(targetMessageId: Long) =
            targetMessageId == SCROLL_TO_LAST_UNREAD || targetMessageId == SCROLL_TO_LATEST
    }
}
