package com.discord.widgets.status

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.message.reaction.MessageReaction
import com.discord.stores.StoreStream
import com.discord.views.ReactionView
import com.discord.widgets.forums.messageCountText
import rx.Observable
import rx.Subscription

class WidgetForumPostStatus : Fragment(R.layout.widget_forum_post_status) {
    data class State(
        val channel: Channel?,
        val followed: Boolean? = null,
        val reaction: MessageReaction? = null,
        val messageId: Long? = null,
        val followLoading: Boolean = false,
    )

    private var subscription: Subscription? = null
    private var state = State(null)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.following_container).setOnClickListener {
            state.channel?.let { channel -> onFollowChanged?.invoke(channel, state.followed != true) }
        }
        view.findViewById<ReactionView>(R.id.reaction).apply {
            setOnClickListener { state.reaction?.let { reaction -> onReaction?.invoke(reaction) } }
            setOnLongClickListener { state.reaction?.let { reaction -> onReactionLongPress?.invoke(reaction) } != null }
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: StoreStream.getChannelsSelected().observeSelectedChannel().map {
            State(it, it?.member?.let { true })
        }).distinctUntilChanged().subscribe { next ->
            view?.post {
                if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) show(next)
            }
        }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun show(next: State) {
        state = next
        val visible = next.channel?.type in Channel.ANNOUNCEMENT_THREAD..Channel.PRIVATE_THREAD &&
            next.channel?.messageCount != null
        requireView().visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val channel = requireNotNull(next.channel)

        requireView().findViewById<TextView>(R.id.message_count).text = messageCountText(channel.messageCount ?: 0)
        val reactionView = requireView().findViewById<ReactionView>(R.id.reaction)
        val reactionVisible = next.reaction != null && next.messageId != null
        reactionView.visibility = if (reactionVisible) View.VISIBLE else View.GONE
        reactionView.isEnabled = onReaction != null || onReactionLongPress != null
        requireView().findViewById<View>(R.id.separator_two).visibility = reactionView.visibility
        if (reactionVisible) reactionView.configure(requireNotNull(next.reaction), requireNotNull(next.messageId), true)

        val loading = requireView().findViewById<ProgressBar>(R.id.follow_loading_indicator)
        val following = requireView().findViewById<TextView>(R.id.following)
        loading.visibility = if (next.followLoading) View.VISIBLE else View.GONE
        following.visibility = if (next.followLoading) View.GONE else View.VISIBLE
        following.setText(if (next.followed == true) R.string.forum_unfollow_button else R.string.forum_follow_button)
        requireView().findViewById<View>(R.id.following_container).isEnabled =
            !next.followLoading && onFollowChanged != null
    }

    companion object {
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onFollowChanged: ((Channel, Boolean) -> Unit)? = null
        @JvmField var onReaction: ((MessageReaction) -> Unit)? = null
        @JvmField var onReactionLongPress: ((MessageReaction) -> Unit)? = null
    }
}
