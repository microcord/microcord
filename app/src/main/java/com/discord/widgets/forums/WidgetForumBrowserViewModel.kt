package com.discord.widgets.forums

import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.api.user.NsfwAllowance
import com.discord.app.AppViewModel
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.EmojiSet
import com.discord.models.guild.UserGuildMember
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.stores.ArchivedThreadsStore
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadMessages
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.tabs.NavigationTab
import rx.Observable
import rx.subjects.PublishSubject

class WidgetForumBrowserViewModel(
    storeStateObservable: Observable<StoreState> = observeStoreState(),
) : AppViewModel<WidgetForumBrowserViewModel.ViewState>() {
    private val events = PublishSubject.create<Event>()
    private val initiallyFetchedChannels = HashSet<Long>()
    private var canFetchMoreOlderPosts = false
    private var isFetchingMoreOlderPosts = false
    private var lastSelectedForumChannelId = 0L

    init {
        storeStateObservable.distinctUntilChanged().computationLatest().ui(this)
            .appSubscribe(javaClass, onNext = ::handleStoreState)
    }

    fun observeEvents(): Observable<Event> = events

    fun enqueueForumPostFirstMessageFetch(parentChannelId: Long, postId: Long) {
        StoreStream.getForumPostMessages().enqueueForumPostFirstMessageFetch(parentChannelId, postId)
    }

    fun maybeFetchForumPostFirstMessages(parentChannelId: Long, initial: Boolean = false) {
        if (initial && !initiallyFetchedChannels.add(parentChannelId)) return
        StoreStream.getForumPostMessages().flushForumPostFirstMessageQueue(parentChannelId)
    }

    fun maybeFetchOlderPosts(parentChannelId: Long) {
        if (!canFetchMoreOlderPosts || isFetchingMoreOlderPosts) return
        isFetchingMoreOlderPosts = true
        StoreStream.getArchivedThreads().fetchGuildForumThreadListing(parentChannelId) {
            isFetchingMoreOlderPosts = false
        }
    }

    fun requestForumUnreads(parentChannelId: Long, guildId: Long) {
        StoreStream.getForumPostReadStates().requestForumUnreads(guildId, parentChannelId)
    }

    private fun handleStoreState(state: StoreState) {
        val loaded = state as? StoreState.Loaded ?: return
        val nsfw = NsfwPanelState(
            isNsfwUnconsented = loaded.channel.nsfw && !ForumUtils.isGuildNsfwGateAgreed(loaded.channel.guildId),
            isChannelNsfw = loaded.channel.nsfw,
            nsfwAllowed = loaded.meUser.nsfwAllowance,
            guildId = loaded.channel.guildId,
        )
        val archived = loaded.archivedThreadsState
        val activeEmpty = loaded.activeThreads.isEmpty()
        when {
            shouldShowLoading(activeEmpty, archived) -> updateViewState(ViewState.Loading(nsfw))
            activeEmpty && archived is ArchivedThreadsStore.ThreadListingState.Listing &&
                archived.threads.isEmpty() && !archived.isLoadingMore -> publishLoaded(loaded, nsfw, emptyList())
            else -> publishLoaded(loaded, nsfw, buildItems(loaded))
        }
    }

    private fun publishLoaded(state: StoreState.Loaded, nsfw: NsfwPanelState, items: List<ForumBrowserItem>) {
        if (items.isNotEmpty() && lastSelectedForumChannelId != state.channel.id) {
            lastSelectedForumChannelId = state.channel.id
            events.onNext(Event.ScrollToTop)
        }
        val listing = state.archivedThreadsState
        canFetchMoreOlderPosts = listing is ArchivedThreadsStore.ThreadListingState.Listing &&
            listing.hasMore && !listing.isLoadingMore
        updateViewState(
            ViewState.Loaded(
                nsfwPanelState = nsfw,
                listItems = items,
                canCreateForumChannelPosts = state.canCreateForumChannelPosts,
                channelTopic = state.channel.topic.orEmpty(),
                channelName = state.channel.name.orEmpty(),
                channelId = state.channel.id,
                guildId = state.channel.guildId,
            ),
        )
    }

    internal fun buildItems(state: StoreState.Loaded): List<ForumBrowserItem> {
        val seen = HashSet<Long>()
        fun post(channel: Channel): ForumBrowserItem.PostItem? {
            if (!seen.add(channel.id)) return null
            StoreStream.getGuildMemberRequester().queueRequest(channel.guildId, channel.ownerId)
            return ForumBrowserItem.PostItem(createPostData(state, channel))
        }

        val active = state.activeThreads.values
        val pinned = active.filter { it.flags?.and(Channel.FLAG_PINNED) == Channel.FLAG_PINNED }
            .mapNotNull(::post).sortedWith(ForumBrowserItem.postItemComparatorByMostRecent)
        val ordinary = active.filterNot { it.flags?.and(Channel.FLAG_PINNED) == Channel.FLAG_PINNED }
            .mapNotNull(::post).sortedWith(ForumBrowserItem.postItemComparatorByMostRecent)
        val items = ArrayList<ForumBrowserItem>(pinned.size + ordinary.size + 2)
        items += pinned
        items += ordinary
        val listing = state.archivedThreadsState as? ArchivedThreadsStore.ThreadListingState.Listing
        if (!listing?.threads.isNullOrEmpty()) {
            items += ForumBrowserItem.HeaderItem(R.string.forum_section_archived)
            items += listing!!.threads.mapNotNull(::post).sortedWith(ForumBrowserItem.postItemComparatorByMostRecent)
        }
        if (listing?.isLoadingMore == true) items += ForumBrowserItem.LoadingItem
        StoreStream.getGuildMemberRequester().performQueuedRequests()
        return items
    }

    private fun createPostData(state: StoreState.Loaded, channel: Channel): PostData {
        val message = state.forumPostFirstMessages[channel.id]
        val reaction = ForumUtils.run { message?.mostCommonReaction(StoreStream.getEmojis()) }
        val tag = resolvePostTag(state.channel, channel, state.guildEmojis)
        val typingUsers = state.typingUsers[channel.id].orEmpty().mapNotNull { userId ->
            val user = state.users[userId] ?: return@mapNotNull null
            val member = state.guildMembers[userId] ?: return@mapNotNull null
            UserGuildMember(user, member)
        }.take(MAX_TYPING_USERS)
        return PostData(
            channel = channel,
            owner = state.users[channel.ownerId],
            myUserId = state.meUser.id,
            guildMembers = state.guildMembers,
            guildRoles = state.guildRoles,
            channelNames = state.channelNames,
            firstMessage = message,
            isFirstMessageBlocked = message?.author?.id in state.myBlockedUserIds,
            mostRecentMessageTimestamp = state.mostRecentMessageIds[channel.id]?.let(SnowflakeUtils::toTimestamp),
            messageCount = state.forumPostMessageCounts[channel.id]?.count,
            tag = tag,
            mostCommonReaction = reaction,
            isDefaultReaction = ForumUtils.run {
                reaction?.isDefaultPostReaction(StoreStream.getEmojis()) == true
            },
            readState = state.forumPostReadStates[channel.id] ?: ForumPostReadState.createDefault(channel.id),
            unreadCount = state.forumPostUnreadCounts[channel.id],
            typingUsers = typingUsers,
        )
    }

    sealed class Event { object ScrollToTop : Event() }

    data class NsfwPanelState(
        val isNsfwUnconsented: Boolean,
        val isChannelNsfw: Boolean,
        val nsfwAllowed: NsfwAllowance,
        val guildId: Long,
    )

    sealed class ViewState(open val nsfwPanelState: NsfwPanelState) {
        data class Loading(override val nsfwPanelState: NsfwPanelState) : ViewState(nsfwPanelState)
        data class Loaded(
            override val nsfwPanelState: NsfwPanelState,
            val listItems: List<ForumBrowserItem>,
            val canCreateForumChannelPosts: Boolean,
            val channelTopic: String,
            val channelName: String,
            val channelId: Long,
            val guildId: Long,
        ) : ViewState(nsfwPanelState)
    }

    sealed class StoreState {
        object Invalid : StoreState()
        data class Loaded(
            val meUser: MeUser,
            val users: Map<Long, User>,
            val archivedThreadsState: ArchivedThreadsStore.ThreadListingState,
            val activeThreads: Map<Long, Channel>,
            val guildMembers: Map<Long, GuildMember>,
            val guildRoles: Map<Long, GuildRole>,
            val channelNames: Map<Long, String>,
            val channel: Channel,
            val forumPostFirstMessages: Map<Long, Message>,
            val forumPostMessageCounts: Map<Long, StoreThreadMessages.ThreadState>,
            val myBlockedUserIds: Set<Long>,
            val canCreateForumChannelPosts: Boolean,
            val mostRecentMessageIds: Map<Long, Long>,
            val guildEmojis: Map<String, Emoji>,
            val forumPostReadStates: Map<Long, ForumPostReadState>,
            val forumPostUnreadCounts: Map<Long, Int>,
            val typingUsers: Map<Long, Set<Long>>,
        ) : StoreState()
    }

    private data class BaseState(
        val me: MeUser,
        val users: Map<Long, User>,
        val archived: ArchivedThreadsStore.ThreadListingState,
        val names: Map<Long, String>,
    )
    private data class GuildState(
        val members: Map<Long, GuildMember>,
        val roles: Map<Long, GuildRole>,
        val firstMessages: Map<Long, Message>,
        val counts: Map<Long, StoreThreadMessages.ThreadState>,
    )
    private data class MessageState(
        val blocked: Set<Long>,
        val canCreate: Boolean,
        val recentIds: Map<Long, Long>,
        val emojis: EmojiSet,
    )
    private data class ReadState(
        val readStates: Map<Long, ForumPostReadState>,
        val unreadCounts: Map<Long, Int>,
        val typing: Map<Long, Set<Long>>,
    )

    companion object {
        private const val MAX_TYPING_USERS = 3

        fun observeStoreState(): Observable<StoreState> = StoreStream.getChannelsSelected()
            .observeSelectedChannel()
            .switchMap { channel ->
                if (channel == null || channel.type != Channel.GUILD_FORUM) {
                    return@switchMap Observable.just<StoreState>(StoreState.Invalid)
                }
                Observable.combineLatest(
                    StoreStream.getTabsNavigation().observeSelectedTab(),
                    StoreStream.getThreadsActive().observeActiveThreadsForChannel(channel.guildId, channel.id),
                ) { tab, active -> tab to active }
                    .switchMap { (tab, active) ->
                        if (tab != NavigationTab.HOME) Observable.just<StoreState>(StoreState.Invalid)
                        else observeLoaded(channel, active)
                    }
            }

        private fun observeLoaded(channel: Channel, active: Map<Long, Channel>): Observable<StoreState> {
            val base = Observable.combineLatest(
                StoreStream.getUsers().observeMe(),
                StoreStream.getUsers().observeAllUsers(),
                ForumUtils.maybeLoadAndObserveThreadListing(channel.id, active.isNotEmpty()),
                StoreStream.getChannels().observeNames(),
            ) { me, users, archived, names ->
                BaseState(me, users, archived, names)
            }
            val guild = Observable.combineLatest(
                StoreStream.getGuilds().observeGuildMembers(channel.guildId),
                StoreStream.getGuilds().observeRoles(channel.guildId),
                StoreStream.getForumPostMessages().observeForumPostFirstMessages(),
                StoreStream.getThreadMessages().observeThreadCountAndLatestMessage(),
            ) { members, roles, firstMessages, counts ->
                GuildState(members, roles, firstMessages, counts)
            }
            val messages = Observable.combineLatest(
                StoreStream.getUserRelationships().observeForType(2).map { it.keys },
                StoreStream.getPermissions().observePermissionsForChannel(channel.id)
                    .map { PermissionUtils.can(Permission.SEND_MESSAGES, it) },
                StoreStream.getMessagesMostRecent().observeRecentMessageIds(),
                StoreStream.getEmojis().getEmojiSet(channel.guildId, channel.id),
            ) { blocked, canCreate, recentIds, emojis ->
                MessageState(blocked, canCreate, recentIds, emojis)
            }
            val reads = Observable.combineLatest(
                ForumPostReadStateManager.observeForumPostReadStates(channel.guildId, channel.id),
                StoreStream.getForumPostReadStates().observeThreadUnreadCounts(),
                StoreStream.getUsersTyping().observeTypingUsersForChannels(active.keys),
            ) { readStates, unreadCounts, typing ->
                ReadState(readStates, unreadCounts, typing)
            }
            return Observable.combineLatest(base, guild, messages, reads) { b, g, m, r ->
                StoreState.Loaded(
                    b.me, b.users, b.archived, active, g.members, g.roles, b.names, channel,
                    g.firstMessages, g.counts, m.blocked, m.canCreate, m.recentIds,
                    m.emojis.emojiIndex, r.readStates, r.unreadCounts,
                    r.typing.filterKeys(active::containsKey),
                ) as StoreState
            }
        }

    }
}

internal fun shouldShowLoading(
    activeThreadsEmpty: Boolean,
    archived: ArchivedThreadsStore.ThreadListingState,
): Boolean = activeThreadsEmpty && (
    archived is ArchivedThreadsStore.ThreadListingState.Uninitialized ||
        archived is ArchivedThreadsStore.ThreadListingState.Listing &&
        archived.threads.isEmpty() && archived.isLoadingMore
    )
