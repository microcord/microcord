package com.discord.widgets.status

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.isArchivedThread
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreEmoji
import com.discord.stores.StoreForumPostMessages
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadMessages
import com.discord.stores.StoreThreadsJoined
import com.discord.stores.StoreUser
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.list.actions.MessageActionCoordinator
import com.discord.widgets.chat.list.reactions.WidgetManageReactions
import com.discord.widgets.forums.ForumUtils
import rx.Observable

class WidgetForumPostStatusViewModel(
    storeStateObservable: Observable<StoreState> = observeStoreState(),
    private val restAPI: () -> RestAPIInterface = { RestAPI.reactNativeApiOrNull ?: RestAPI.api },
) : AppViewModel<WidgetForumPostStatusViewModel.ViewState>() {

    sealed class StoreState {
        object Invalid : StoreState()

        data class Loaded(
            val myUserId: Long,
            val channelId: Long,
            val guildId: Long,
            val messageId: Long?,
            val isFollowedThread: Boolean,
            val isArchivedThread: Boolean,
            val messageCount: Int?,
            val hasAddReactionPermission: Boolean,
            val isDefaultReaction: Boolean,
            val mostCommonReaction: MessageReaction?,
        ) : StoreState()
    }

    sealed class ViewState {
        object Invalid : ViewState()

        data class Valid(
            val myUserId: Long,
            val channelId: Long,
            val guildId: Long,
            val isFollowedThread: Boolean,
            val isArchivedThread: Boolean,
            val messageId: Long?,
            val reaction: MessageReaction?,
            val isDefaultReaction: Boolean,
            val messageCount: Int,
            val hasAddReactionPermission: Boolean,
            val followRequestInProgress: Boolean,
        ) : ViewState()
    }

    private var followRequestInProgress = false

    init {
        storeStateObservable
            .computationLatest()
            .distinctUntilChanged()
            .ui(this)
            .appSubscribe(WidgetForumPostStatusViewModel::class.java, onNext = ::handleStoreState)
    }

    internal fun handleStoreState(storeState: StoreState) {
        updateViewState(
            when (storeState) {
                StoreState.Invalid -> ViewState.Invalid
                is StoreState.Loaded -> ViewState.Valid(
                    myUserId = storeState.myUserId,
                    channelId = storeState.channelId,
                    guildId = storeState.guildId,
                    isFollowedThread = storeState.isFollowedThread,
                    isArchivedThread = storeState.isArchivedThread,
                    messageId = storeState.messageId,
                    reaction = storeState.mostCommonReaction,
                    isDefaultReaction = storeState.isDefaultReaction,
                    messageCount = storeState.messageCount ?: 0,
                    hasAddReactionPermission = storeState.hasAddReactionPermission,
                    followRequestInProgress = followRequestInProgress,
                )
            },
        )
    }

    fun onFollowPress(
        context: Context,
        fragmentManager: FragmentManager,
        guildId: Long,
        channelId: Long,
        isArchivedThread: Boolean,
        isFollowedThread: Boolean,
    ) = doPress(context, fragmentManager, guildId, isArchivedThread) {
        toggleFollowPost(context, channelId, guildId, isFollowedThread)
    }

    fun onReactionPress(
        context: Context,
        fragmentManager: FragmentManager,
        myUserId: Long,
        channelId: Long,
        guildId: Long,
        messageId: Long,
        isArchivedThread: Boolean,
        reaction: MessageReaction,
        hasAddReactionPermission: Boolean,
    ) = doPress(context, fragmentManager, guildId, isArchivedThread) {
        if (!hasAddReactionPermission) return@doPress
        val route = MessageActionCoordinator.reactionRoute(reaction.emoji)
        val request = if (reaction.me) {
            restAPI().removeSelfReaction(channelId, messageId, route)
        } else {
            restAPI().addReaction(channelId, messageId, route)
        }
        request.restSubscribeOn().ui(this).appSubscribe(WidgetForumPostStatusViewModel::class.java) { }
    }

    fun onReactionLongPress(
        context: Context,
        fragmentManager: FragmentManager,
        channelId: Long,
        guildId: Long,
        messageId: Long,
        isArchivedThread: Boolean,
        reaction: MessageReaction,
    ) = doPress(context, fragmentManager, guildId, isArchivedThread) {
        WidgetManageReactions.create(channelId, messageId, context, reaction)
    }

    private fun doPress(
        context: Context,
        fragmentManager: FragmentManager,
        guildId: Long,
        isArchivedThread: Boolean,
        action: () -> Unit,
    ) {
        // Membership-gate navigation is not reconstructed; the archived guard is still mandatory.
        if (isArchivedThread) {
            Toast.makeText(context, R.string.thread_archived, Toast.LENGTH_SHORT).show()
        } else {
            action()
        }
    }

    internal fun toggleFollowPost(context: Context?, channelId: Long, guildId: Long, isFollowedThread: Boolean) {
        if (followRequestInProgress) return
        val followed = !isFollowedThread
        followRequestInProgress = true
        StoreStream.setForumPostFollowed(channelId, guildId, followed)
        publishFollowProgress()
        val request = if (isFollowedThread) {
            restAPI().leaveThread(channelId, FOLLOW_LOCATION)
        } else {
            restAPI().joinThread(channelId, FOLLOW_LOCATION, RestAPIParams.EmptyBody())
        }
        request.restSubscribeOn().ui(this).appSubscribe(
            errorClass = WidgetForumPostStatusViewModel::class.java,
            context = context,
            errorHandler = { StoreStream.setForumPostFollowed(channelId, guildId, isFollowedThread) },
            onTerminated = {
                followRequestInProgress = false
                publishFollowProgress()
            },
        ) { }
    }

    private fun publishFollowProgress() {
        val state = viewState as? ViewState.Valid ?: return
        updateViewState(state.copy(followRequestInProgress = followRequestInProgress))
    }

    companion object {
        private const val FOLLOW_LOCATION = "Forum Split View Toolbar"

        @JvmStatic
        fun observeStoreState(
            storeUser: StoreUser = StoreStream.getUsers(),
            storeChannels: StoreChannels = StoreStream.getChannels(),
            storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
            storeThreadsJoined: StoreThreadsJoined = StoreStream.getThreadsJoined(),
            storeForumPostMessages: StoreForumPostMessages = StoreStream.getForumPostMessages(),
            storeThreadMessages: StoreThreadMessages = StoreStream.getThreadMessages(),
            storePermissions: StorePermissions = StoreStream.getPermissions(),
            storeEmoji: StoreEmoji = StoreStream.getEmojis(),
        ): Observable<StoreState> = ForumUtils.observeSelectedPostChannel(
            storeChannels,
            storeChannelsSelected,
        ).switchMap { channel ->
            if (channel == null) return@switchMap Observable.just(StoreState.Invalid)
            Observable.combineLatest(
                storeUser.observeMeId(),
                storeThreadsJoined.observeJoinedThread(channel.id),
                storeForumPostMessages.observeForumPostFirstMessage(channel.parentId, channel.id, true),
                storeThreadMessages.observeThreadCountAndLatestMessage(channel.id),
                storePermissions.observePermissionsForChannel(channel.id),
            ) { myUserId, joinedThread, message, threadState, permissions ->
                val reaction = message?.let { with(ForumUtils) { it.mostCommonReaction(storeEmoji) } }
                    ?: ForumUtils.getPlaceholderMessageReaction(storeEmoji)
                StoreState.Loaded(
                    myUserId = myUserId,
                    channelId = channel.id,
                    guildId = channel.guildId,
                    messageId = message?.id,
                    isFollowedThread = joinedThread != null,
                    isArchivedThread = channel.isArchivedThread(),
                    messageCount = threadState?.count,
                    hasAddReactionPermission = PermissionUtils.can(Permission.ADD_REACTIONS, permissions),
                    isDefaultReaction = reaction?.let {
                        with(ForumUtils) { it.isDefaultPostReaction(storeEmoji) }
                    } == true,
                    mostCommonReaction = reaction,
                )
            }
        }.distinctUntilChanged()
    }
}
