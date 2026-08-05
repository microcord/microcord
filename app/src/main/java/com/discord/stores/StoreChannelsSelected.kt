package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isTextOrVoiceChannel
import com.discord.api.channel.isVoiceChannel
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import rx.Observable
import rx.Subscription

/**
 * Where a channel open originated. Purely an analytics label.
 *
 * Reference: `com.discord.stores.SelectedChannelAnalyticsLocation`. It is declared here rather than
 * in its own file only because this wave owns `StoreChannelsSelected.kt`; the fully qualified name
 * matches the reference.
 */
/**
 * The user's channel selection, per guild, and the channel that selection currently resolves to.
 *
 * There are two distinct models here and collapsing them loses behaviour:
 *
 * - [UserChannelSelection] is the *intent* the user expressed for a guild. It survives the channel
 *   being unavailable, and it is what gets persisted.
 * - [ResolvedSelectedChannel] is what that intent resolves to *right now*, given the channels and
 *   permissions the client currently holds. It distinguishes "nothing selected", "the selection
 *   cannot be honoured", and "we do not know yet" — three states that a nullable `Channel` cannot
 *   express, and that the UI must render differently (empty channel view vs. loading shell).
 *
 * Resolution is reactive, not a one-shot computation: [validateSelectedChannel] subscribes to the
 * guild-selection, channel and permission stores, so a channel arriving late or a permission
 * changing asynchronously re-resolves the selection instead of leaving a stale one on screen.
 */
class StoreChannelsSelected(
    private val channels: StoreChannels,
    private val permissions: StorePermissions,
    private val guildSelected: StoreGuildSelected,
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    /**
     * Invoked on the store thread whenever the resolved channel id changes, with the resolved
     * channel or `null` when nothing is selected.
     *
     * The reference performs these side effects through `StoreStream` directly; the reconstruction
     * keeps `StoreChannelsSelected` free of a `StoreStream` back-reference and hands the transition
     * out instead.
     */
    private val onResolvedChannelChanged: (Channel?) -> Unit = {},
    private val trackChannelOpened:
        (channelId: Long, viewType: ChannelAnalyticsViewType, location: SelectedChannelAnalyticsLocation?) -> Unit =
        { _, _, _ -> },
    private val trackTextInVoiceOpened: (channelId: Long, location: SelectedChannelAnalyticsLocation?) -> Unit =
        { _, _ -> },
    private val selectedChannelIdsCache: Persister<Map<Long, Long>> =
        Persister(CACHE_KEY_SELECTED_CHANNEL_IDS, emptyMap()),
) : StoreV2() {

    /** What the current [UserChannelSelection] resolves to against the channels we actually hold. */
    sealed class ResolvedSelectedChannel {

        /** A real, accessible channel. [peekParent] is set when it is being peeked from a parent. */
        data class Channel(
            val channel: com.discord.api.channel.Channel,
            val peekParent: Long? = null,
            val analyticsLocation: SelectedChannelAnalyticsLocation? = null,
        ) : ResolvedSelectedChannel() {
            val analyticsViewType: ChannelAnalyticsViewType =
                if (peekParent != null) ChannelAnalyticsViewType.PEEK_VIEW else ChannelAnalyticsViewType.FULL_VIEW
        }

        /**
         * The thread-creation composer is open over [parentChannel]. No thread exists yet, so there
         * is no channel id to select — hence the dedicated state rather than a nullable channel.
         */
        data class ThreadDraft(
            val parentChannel: com.discord.api.channel.Channel,
            val starterMessageId: Long? = null,
            val threadStartLocation: String? = null,
        ) : ResolvedSelectedChannel() {
            fun hasStarterMessage(): Boolean = starterMessageId != null
        }

        /** The user is in a guild/DM surface with nothing open. */
        object Unselected : ResolvedSelectedChannel()

        /** A guild is selected but no channel in it is visible to this user. */
        object Unavailable : ResolvedSelectedChannel()

        /** Channels have not been hydrated for the authenticated user yet. */
        object Uninitialized : ResolvedSelectedChannel()

        /**
         * A stable id per state. The negative sentinels let a single `Long` observable carry all
         * five states, which is what the reference exposes through [observeId].
         */
        val id: Long
            get() = when (this) {
                is Channel -> channel.id
                is ThreadDraft -> StoreChannelsSelected.ID_THREAD_DRAFT
                Unselected -> StoreChannelsSelected.ID_UNSELECTED
                Unavailable -> StoreChannelsSelected.ID_UNAVAILABLE
                Uninitialized -> StoreChannelsSelected.ID_UNINITIALIZED
            }

        /** The selected channel, or for a draft the channel the draft will be created under. */
        val channelOrParent: com.discord.api.channel.Channel?
            get() = when (this) {
                is Channel -> channel
                is ThreadDraft -> parentChannel
                else -> null
            }

        /** The selected channel only; a draft has none. */
        val maybeChannel: com.discord.api.channel.Channel?
            get() = (this as? Channel)?.channel
    }

    /** The selection the user expressed for a guild. This is the persisted, resolution-independent form. */
    sealed class UserChannelSelection {

        data class SelectedChannel(
            val channelId: Long,
            val peekParent: Long? = null,
            val analyticsLocation: SelectedChannelAnalyticsLocation? = null,
        ) : UserChannelSelection()

        data class ThreadDraft(
            val parentChannelId: Long,
            val starterMessageId: Long? = null,
            val locationThreadStart: String? = null,
        ) : UserChannelSelection()

        object Unselected : UserChannelSelection()

        val id: Long
            get() = when (this) {
                is SelectedChannel -> channelId
                is ThreadDraft -> parentChannelId
                Unselected -> StoreChannelsSelected.ID_UNSELECTED
            }

        /** Pair this intent with the channel it names, once that channel is known to exist. */
        fun resolveWithChannel(channel: Channel): ResolvedSelectedChannel = when (this) {
            Unselected -> ResolvedSelectedChannel.Unselected
            is SelectedChannel -> ResolvedSelectedChannel.Channel(channel, peekParent, analyticsLocation)
            is ThreadDraft -> ResolvedSelectedChannel.ThreadDraft(channel, starterMessageId, locationThreadStart)
        }
    }

    private val userChannelSelections = HashMap<Long, UserChannelSelection>()
    private var selectedChannel: ResolvedSelectedChannel = ResolvedSelectedChannel.Uninitialized
    private var previouslySelectedChannel: ResolvedSelectedChannel = ResolvedSelectedChannel.Uninitialized
    private var handledReadyPayload = false
    private var initializedForAuthedUser = false
    private var isStoreInitTimedOut = false
    private var validateSelectedChannelSubscription: Subscription? = null

    private var selectedChannelSnapshot: ResolvedSelectedChannel = ResolvedSelectedChannel.Uninitialized
    private var previouslySelectedChannelSnapshot: ResolvedSelectedChannel = ResolvedSelectedChannel.Uninitialized

    // -- Lifecycle ------------------------------------------------------------------------------

    @StoreThread
    override fun init(context: Context) {
        loadFromCache()
        validateSelectedChannel()
    }

    /**
     * Hydrate from disk without the initial validation.
     *
     * `StoreStream` restores caches from a dispatch that runs before the channel store has any
     * content; [init] is the entry point that also kicks off resolution.
     */
    @StoreThread
    internal fun restore() {
        loadFromCache()
        markChanged()
    }

    @StoreThread
    private fun loadFromCache() {
        selectedChannelIdsCache.get().forEach { (guildId, channelId) ->
            userChannelSelections[guildId] = UserChannelSelection.SelectedChannel(channelId)
        }
    }

    @StoreThread
    internal fun reset() {
        validateSelectedChannelSubscription?.unsubscribe()
        validateSelectedChannelSubscription = null
        userChannelSelections.clear()
        selectedChannel = ResolvedSelectedChannel.Uninitialized
        previouslySelectedChannel = ResolvedSelectedChannel.Uninitialized
        handledReadyPayload = false
        initializedForAuthedUser = false
        isStoreInitTimedOut = false
        selectedChannelIdsCache.clear()
        markChanged(InitializedUpdateSource)
    }

    @StoreThread
    override fun snapshotData() {
        selectedChannelSnapshot = selectedChannel
        previouslySelectedChannelSnapshot = previouslySelectedChannel
        selectedChannelIdsCache.set(userChannelSelections.mapValues { it.value.id }, persist = true)
    }

    // -- Reads ----------------------------------------------------------------------------------

    fun getId(): Long = selectedChannelSnapshot.id

    fun getSelectedChannel(): Channel? = selectedChannelSnapshot.maybeChannel

    fun getResolvedSelectedChannel(): ResolvedSelectedChannel = selectedChannelSnapshot

    @StoreThread
    internal fun getSelectedChannelInternal(): ResolvedSelectedChannel = selectedChannel

    fun observeId(): Observable<Long> = observationDeck.connectRx(this, generator = ::getId)

    fun observePreviousId(): Observable<Long> =
        observationDeck.connectRx(this, generator = { previouslySelectedChannelSnapshot.id })

    fun observeSelectedChannel(): Observable<Channel?> =
        observationDeck.connectRx(this, generator = ::getSelectedChannel)

    fun observeResolvedSelectedChannel(): Observable<ResolvedSelectedChannel> =
        observationDeck.connectRx(this, generator = ::getResolvedSelectedChannel)

    /**
     * `true` once a selection has been resolved for a hydrated, authenticated user.
     *
     * Gated on [StoreChannels.observeInitializedForAuthedUser] first so that a logout followed by a
     * different login cannot leak the previous account's `true`.
     */
    fun observeInitializedForAuthedUser(): Observable<Boolean> =
        channels.observeInitializedForAuthedUser().switchMap { isStoreChannelsInitialized ->
            if (isStoreChannelsInitialized) {
                observationDeck.connectRx(InitializedUpdateSource, generator = { initializedForAuthedUser })
            } else {
                Observable.just(false)
            }
        }

    // -- Selection ------------------------------------------------------------------------------

    /**
     * Select [channelId] within [guildId], unless it is already the selection for that guild.
     *
     * The idempotence guard matters: re-selecting the open channel would otherwise re-emit an
     * analytics "channel opened" event on every navigation bounce.
     */
    @StoreThread
    fun trySelectChannel(
        guildId: Long,
        channelId: Long,
        peekParent: Long? = null,
        analyticsLocation: SelectedChannelAnalyticsLocation? = null,
    ) {
        val current = userChannelSelections[guildId] as? UserChannelSelection.SelectedChannel
        if (current == null || current.channelId != channelId) {
            userChannelSelections[guildId] =
                UserChannelSelection.SelectedChannel(channelId, peekParent, analyticsLocation)
            validateSelectedChannel()
        }
    }

    @StoreThread
    internal fun handleChannelSelected(guildId: Long, channelId: Long) =
        trySelectChannel(guildId, channelId)

    /** Open the thread composer under [parentChannelId] without creating a thread yet. */
    @StoreThread
    fun openCreateThread(
        guildId: Long,
        parentChannelId: Long,
        parentMessageId: Long? = null,
        threadStartLocation: String? = null,
    ) {
        userChannelSelections[guildId] =
            UserChannelSelection.ThreadDraft(parentChannelId, parentMessageId, threadStartLocation)
        validateSelectedChannel()
    }

    /** Close the thread composer, falling back to the parent channel it was opened from. */
    @StoreThread
    fun dismissCreateThread() {
        val draft = selectedChannel as? ResolvedSelectedChannel.ThreadDraft ?: return
        val parent = draft.parentChannel
        userChannelSelections[parent.guildId] = UserChannelSelection.SelectedChannel(parent.id)
        validateSelectedChannel()
    }

    // -- Gateway / store handlers ---------------------------------------------------------------

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        val liveGuildIds = payload.guilds.orEmpty().mapTo(HashSet()) { it.id }
        liveGuildIds.add(PRIVATE_CHANNELS_ID)
        val retained = userChannelSelections.filterKeys(liveGuildIds::contains).toMutableMap()
        retained.getOrPut(PRIVATE_CHANNELS_ID) { UserChannelSelection.Unselected }
        userChannelSelections.clear()
        userChannelSelections.putAll(retained)
        handledReadyPayload = true
        validateSelectedChannel()
    }

    @StoreThread
    fun handleGuildSelected() = validateSelectedChannel()

    @StoreThread
    fun handleGuildAdd(guildId: Long) {
        if (guildSelected.getSelectedGuildIdInternal() == guildId) validateSelectedChannel()
    }

    /**
     * Re-validate when the removed guild owned the selection.
     *
     * The stored [UserChannelSelection] is deliberately left in place: `handleConnectionOpen` prunes
     * selections for guilds that are really gone, and a transient `GUILD_DELETE` for an unavailable
     * guild must not lose the user's channel.
     */
    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        if (selectedChannel.maybeChannel?.guildId == guildId) validateSelectedChannel()
    }

    @StoreThread
    fun handleChannelOrThreadCreateOrUpdate(channel: Channel) {
        if (channel.id == selectedChannel.id) validateSelectedChannel()
    }

    @StoreThread
    fun handleChannelOrThreadDelete(channel: Channel) {
        if (channel.id == selectedChannel.id) validateSelectedChannel()
    }

    /**
     * Kept for `StoreStream`, which pushes permission recomputations explicitly.
     *
     * The reference has no such handler because [validateSelectedChannel] already observes
     * [StorePermissions]; this is a no-cost belt-and-braces trigger for the guild in view.
     */
    @StoreThread
    fun handlePermissionsChanged(guildId: Long) {
        if (guildId == guildSelected.getSelectedGuildIdInternal()) validateSelectedChannel()
    }

    /**
     * Give up waiting for hydration.
     *
     * Without this the UI stays on the loading shell forever when `READY` never lands, because
     * [ResolvedSelectedChannel.Uninitialized] never resolves on its own.
     */
    @StoreThread
    fun handleStoreInitTimeout() {
        isStoreInitTimedOut = true
        updateInitializationState()
        if (selectedChannel == ResolvedSelectedChannel.Uninitialized) validateSelectedChannel()
    }

    // -- Resolution -----------------------------------------------------------------------------

    /**
     * Re-resolve the selection and keep re-resolving while channels or permissions move.
     *
     * The previous subscription is cancelled first so that only the newest selection intent is
     * live; otherwise two intents would race to write [selectedChannel].
     */
    @StoreThread
    private fun validateSelectedChannel() {
        validateSelectedChannelSubscription?.unsubscribe()

        // The intent is fixed for this round; everything derived from store state is re-read on
        // every emission, from published snapshots only — this generator runs off the store thread.
        val selectedGuildId = guildSelected.getSelectedGuildIdInternal()
        val channelSelection = userChannelSelections[selectedGuildId]

        observationDeck.connectRx(guildSelected, channels, permissions) {
            resolveSelectedChannel(
                userChannelSelection = channelSelection,
                resolvedChannel = channelSelection?.let { channels.findChannelById(it.id) },
                channels = channels.getChannelsForGuild(selectedGuildId),
                guildId = selectedGuildId,
                permissions = permissions.getPermissionsByChannel(),
                storeChannelsInitializedForAuthedUser = channels.getInitializedForAuthedUser(),
            )
        }
            .computationLatest()
            .distinctUntilChanged()
            .appSubscribe(
                StoreChannelsSelected::class.java,
                subscriptionHandler = { validateSelectedChannelSubscription = it },
            ) { selected -> dispatcher.schedule { onSelectedChannelResolved(selected) } }
    }

    private fun resolveSelectedChannel(
        userChannelSelection: UserChannelSelection?,
        resolvedChannel: Channel?,
        channels: Map<Long, Channel>,
        guildId: Long,
        permissions: Map<Long, Long>,
        storeChannelsInitializedForAuthedUser: Boolean,
    ): ResolvedSelectedChannel = when {
        !storeChannelsInitializedForAuthedUser -> ResolvedSelectedChannel.Uninitialized
        resolvedChannel != null && isValidResolution(resolvedChannel, guildId, permissions) ->
            userChannelSelection?.resolveWithChannel(resolvedChannel) ?: ResolvedSelectedChannel.Unselected
        guildId == PRIVATE_CHANNELS_ID -> ResolvedSelectedChannel.Unselected
        else -> getFirstAvailableChannel(channels, guildId, permissions)
            ?.let { ResolvedSelectedChannel.Channel(it) }
            ?: ResolvedSelectedChannel.Unavailable
    }

    /**
     * A selection is honoured only for a channel the user can actually open. Categories, stage
     * channels and directories are excluded here, which is why they cannot become "selected"
     * by being clicked in the sidebar.
     */
    private fun isValidResolution(channel: Channel, guildId: Long, permissions: Map<Long, Long>): Boolean =
        channel.guildId == guildId &&
            channel.isTextOrVoiceChannel() &&
            PermissionUtils.hasAccess(channel, permissions)

    /** The channel a guild falls back to: the first visible message-bearing channel in sidebar order. */
    private fun getFirstAvailableChannel(
        channels: Map<Long, Channel>,
        guildId: Long,
        permissions: Map<Long, Long>,
    ): Channel? = channels.values
        .filter { it.guildId == guildId && it.isGuildTextyChannel() && PermissionUtils.hasAccess(it, permissions) }
        .minWithOrNull(Channel.getSortByNameAndType())

    @StoreThread
    private fun onSelectedChannelResolved(resolvedSelectedChannel: ResolvedSelectedChannel) {
        if (selectedChannel.id != resolvedSelectedChannel.id) {
            previouslySelectedChannel = selectedChannel
        }
        val changed = selectedChannel != resolvedSelectedChannel
        selectedChannel = resolvedSelectedChannel

        if (resolvedSelectedChannel is ResolvedSelectedChannel.Channel) {
            val channel = resolvedSelectedChannel.channel
            if (shouldPersistResolvedSelection(userChannelSelections[channel.guildId], channel.id)) {
                userChannelSelections[channel.guildId] = UserChannelSelection.SelectedChannel(
                    channel.id,
                    resolvedSelectedChannel.peekParent,
                )
            }
            if (channel.isVoiceChannel()) {
                trackTextInVoiceOpened(channel.id, resolvedSelectedChannel.analyticsLocation)
            }
            trackChannelOpened(
                channel.id,
                resolvedSelectedChannel.analyticsViewType,
                resolvedSelectedChannel.analyticsLocation,
            )
        }

        updateInitializationState()
        markChanged()
        if (changed) onResolvedChannelChanged(resolvedSelectedChannel.maybeChannel)
    }

    /**
     * Flip to initialized once a selection has been resolved for a hydrated, authenticated user.
     *
     * All four conditions matter: without the `Uninitialized` check the app would announce it is
     * ready before the first resolution, and the home shell would flash an empty channel view.
     */
    @StoreThread
    private fun updateInitializationState() {
        val ready = channels.isAuthenticatedInternal() &&
            channels.getInitializedForAuthedUser() &&
            (userChannelSelections.isNotEmpty() || handledReadyPayload || isStoreInitTimedOut) &&
            selectedChannel != ResolvedSelectedChannel.Uninitialized
        if (!initializedForAuthedUser && ready) {
            initializedForAuthedUser = true
            markChanged(InitializedUpdateSource)
        }
    }

    companion object {
        internal fun shouldPersistResolvedSelection(selection: UserChannelSelection?, resolvedChannelId: Long) =
            selection == null || selection is UserChannelSelection.Unselected || selection.id == resolvedChannelId

        const val CACHE_KEY_SELECTED_CHANNEL_IDS = "CACHE_KEY_SELECTED_CHANNEL_IDS"

        /** The pseudo-guild that owns DMs and group DMs. */
        const val PRIVATE_CHANNELS_ID = 0L

        const val ID_THREAD_DRAFT = -3L
        const val ID_UNINITIALIZED = -2L
        const val ID_UNAVAILABLE = -1L
        const val ID_UNSELECTED = 0L

        val InitializedUpdateSource = object : ObservationDeck.UpdateSource {}
    }
}
