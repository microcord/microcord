package com.discord.stores

import com.discord.models.message.Message
import java.util.Collections
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.Subscription
import java.util.concurrent.TimeUnit

class StoreMessagesLoader(
    private val loader: (LoadRequest, (Result<List<Message>>) -> Unit) -> Unit,
    private val requestSize: Int = MESSAGE_REQUEST_SIZE,
) {
    enum class Direction { INITIAL, BEFORE, AFTER, AROUND }

    data class LoadRequest(
        val channelId: Long,
        val direction: Direction,
        val anchorMessageId: Long? = null,
        val limit: Int = MESSAGE_REQUEST_SIZE,
    )

    data class ChannelChunk(
        val channelId: Long,
        val messages: List<Message>,
        val isInitial: Boolean,
        val isPresent: Boolean,
        val isAppendingTop: Boolean,
        val isJump: Boolean,
    )

    data class ChannelLoadedState(
        val isInitialMessagesLoaded: Boolean = false,
        val isOldestMessagesLoaded: Boolean = false,
        val isLoadingMessages: Boolean = false,
        val isTouchedSinceLastJump: Boolean = false,
        val newestSentByUserMessageId: Long? = null,
        val retryDelayMillis: Long = RETRY_DELAY_DEFAULT,
        val lastError: Throwable? = null,
    )

    private val states = HashMap<Long, ChannelLoadedState>()
    private val statesSubject = BehaviorSubject.create<Map<Long, ChannelLoadedState>>(emptyMap())
    private val chunksSubject = PublishSubject.create<ChannelChunk>()
    private val scrollSubject = PublishSubject.create<Long>()
    private val loadingSubject = BehaviorSubject.create(false)
    private var selectedChannelId = 0L
    private var connected = false
    private var authenticated = false
    private var lastRequest: LoadRequest? = null
    private var retrySubscription: Subscription? = null

    @Synchronized fun handleAuthToken(token: String?) {
        authenticated = token != null
        if (authenticated && connected && selectedChannelId > 0) loadInitial(selectedChannelId)
    }

    @Synchronized
    fun handleConnected(connected: Boolean) {
        this.connected = connected
        if (!connected) {
            retrySubscription?.unsubscribe()
            retrySubscription = null
            states.clear()
            publishStates()
        } else if (authenticated && selectedChannelId > 0) {
            loadInitial(selectedChannelId)
        }
    }

    @Synchronized
    fun handleChannelSelected(channelId: Long) {
        selectedChannelId = channelId
        loadInitial(channelId)
    }

    @Synchronized fun loadInitial(channelId: Long = selectedChannelId) = load(LoadRequest(channelId, Direction.INITIAL, limit = requestSize))
    @Synchronized fun loadBefore(channelId: Long, messageId: Long) {
        if (states[channelId]?.isOldestMessagesLoaded == true) return
        load(LoadRequest(channelId, Direction.BEFORE, messageId, requestSize))
    }
    @Synchronized fun loadAfter(channelId: Long, messageId: Long) = load(LoadRequest(channelId, Direction.AFTER, messageId, requestSize))

    @Synchronized
    fun jumpToMessage(channelId: Long, messageId: Long) {
        require(messageId > 0)
        load(LoadRequest(channelId, Direction.AROUND, messageId, requestSize))
    }

    @Synchronized fun retry() { lastRequest?.let(::load) }
    fun requestNewestMessages() = loadInitial(selectedChannelId)

    fun observeChunks(): Observable<ChannelChunk> = chunksSubject
    fun get(): Observable<ChannelChunk> = observeChunks()
    fun observeScrollTo(): Observable<Long> = scrollSubject
    fun getScrollTo(): Observable<Long> = observeScrollTo()
    fun observeChannelMessagesLoading(): Observable<Boolean> = loadingSubject.distinctUntilChanged()
    fun getMessagesLoadedState(channelId: Long): Observable<ChannelLoadedState> =
        statesSubject.map { it[channelId] ?: ChannelLoadedState() }.distinctUntilChanged()

    @Synchronized fun reset() {
        retrySubscription?.unsubscribe()
        retrySubscription = null
        states.clear()
        selectedChannelId = 0
        lastRequest = null
        loadingSubject.onNext(false)
        publishStates()
    }

    private fun load(request: LoadRequest) {
        if (request.channelId <= 0 || !connected || !authenticated) return
        val current = states[request.channelId] ?: ChannelLoadedState()
        if (current.isLoadingMessages) return
        lastRequest = request
        updateState(request.channelId, current.copy(isLoadingMessages = true, lastError = null))
        loadingSubject.onNext(true)
        loader(request) { result -> complete(request, result) }
    }

    @Synchronized
    private fun complete(request: LoadRequest, result: Result<List<Message>>) {
        val current = states[request.channelId] ?: ChannelLoadedState()
        result.fold(
            onSuccess = { messages ->
                val initial = request.direction == Direction.INITIAL
                val jump = request.direction == Direction.AROUND
                val reachedEdge = messages.size < request.limit
                updateState(request.channelId, current.copy(
                    isInitialMessagesLoaded = current.isInitialMessagesLoaded || initial || jump,
                    isOldestMessagesLoaded = current.isOldestMessagesLoaded ||
                        (reachedEdge && request.direction in setOf(Direction.INITIAL, Direction.BEFORE)),
                    isLoadingMessages = false,
                    isTouchedSinceLastJump = if (jump) false else current.isTouchedSinceLastJump,
                    retryDelayMillis = RETRY_DELAY_DEFAULT,
                    lastError = null,
                ))
                chunksSubject.onNext(ChannelChunk(
                    request.channelId,
                    messages.sortedBy(Message::id),
                    initial,
                    initial || request.direction == Direction.AFTER && reachedEdge,
                    request.direction == Direction.BEFORE,
                    jump,
                ))
                if (jump) scrollSubject.onNext(requireNotNull(request.anchorMessageId))
            },
            onFailure = { error ->
                val retryDelay = (current.retryDelayMillis * 2).coerceAtMost(RETRY_DELAY_MAX)
                updateState(request.channelId, current.copy(
                    isLoadingMessages = false,
                    retryDelayMillis = retryDelay,
                    lastError = error,
                ))
                retrySubscription?.unsubscribe()
                retrySubscription = Observable.timer(current.retryDelayMillis, TimeUnit.MILLISECONDS)
                    .subscribe { synchronized(this) { if (request.channelId == selectedChannelId) load(request) } }
            },
        )
        loadingSubject.onNext(states.values.any(ChannelLoadedState::isLoadingMessages))
    }

    private fun updateState(channelId: Long, state: ChannelLoadedState) {
        states[channelId] = state
        publishStates()
    }

    private fun publishStates() = statesSubject.onNext(Collections.unmodifiableMap(HashMap(states)))

    companion object {
        const val MESSAGE_REQUEST_SIZE = 50
        const val SCROLL_TO_LAST_UNREAD = 0L
        const val SCROLL_TO_LATEST = 1L
        private const val RETRY_DELAY_DEFAULT = 1_500L
        private const val RETRY_DELAY_MAX = 30_000L
        fun isScrollToAction(messageId: Long) = messageId == SCROLL_TO_LAST_UNREAD || messageId == SCROLL_TO_LATEST
    }
}
