package com.discord.widgets.status

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import androidx.lifecycle.Lifecycle
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.message.reaction.MessageReaction
import com.discord.databinding.WidgetForumPostStatusBinding
import com.discord.stores.StoreStream
import com.discord.widgets.forums.messageCountText
import rx.Observable
import rx.Subscription

class WidgetForumPostStatus : AppFragment(R.layout.widget_forum_post_status) {
    data class State(
        val channel: Channel?,
        val followed: Boolean? = null,
        val reaction: MessageReaction? = null,
        val messageId: Long? = null,
        val followLoading: Boolean = false,
        val messageCount: Int? = null,
    )

    private val viewModel: WidgetForumPostStatusViewModel by appViewModels { WidgetForumPostStatusViewModel() }
    private var binding: WidgetForumPostStatusBinding? = null
    private var subscription: Subscription? = null
    private var state = State(null)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = WidgetForumPostStatusBinding.bind(view).also { this.binding = it }
        binding.followingContainer.setOnClickListener {
            val valid = viewModel.viewState as? WidgetForumPostStatusViewModel.ViewState.Valid ?: return@setOnClickListener
            viewModel.onFollowPress(
                requireContext(),
                parentFragmentManager,
                valid.guildId,
                valid.channelId,
                valid.isArchivedThread,
                valid.isFollowedThread,
            )
        }
        binding.reaction.apply {
            setOnClickListener {
                val valid = viewModel.viewState as? WidgetForumPostStatusViewModel.ViewState.Valid
                    ?: return@setOnClickListener
                val reaction = valid.reaction ?: return@setOnClickListener
                val messageId = valid.messageId ?: return@setOnClickListener
                viewModel.onReactionPress(
                    requireContext(), parentFragmentManager, valid.myUserId, valid.channelId,
                    valid.guildId, messageId, valid.isArchivedThread, reaction, valid.hasAddReactionPermission,
                )
            }
            setOnLongClickListener {
                val valid = viewModel.viewState as? WidgetForumPostStatusViewModel.ViewState.Valid
                    ?: return@setOnLongClickListener false
                val reaction = valid.reaction ?: return@setOnLongClickListener false
                val messageId = valid.messageId ?: return@setOnLongClickListener false
                viewModel.onReactionLongPress(
                    requireContext(), parentFragmentManager, valid.channelId, valid.guildId,
                    messageId, valid.isArchivedThread, reaction,
                )
                true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: viewModel.observeViewState().map { viewState ->
            when (viewState) {
                WidgetForumPostStatusViewModel.ViewState.Invalid -> State(null)
                is WidgetForumPostStatusViewModel.ViewState.Valid -> State(
                    channel = StoreStream.getChannels().getChannel(viewState.channelId),
                    followed = viewState.isFollowedThread,
                    reaction = viewState.reaction,
                    messageId = viewState.messageId,
                    followLoading = viewState.followRequestInProgress,
                    messageCount = viewState.messageCount,
                )
            }
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

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun show(next: State) {
        state = next
        val visible = next.channel?.type in Channel.ANNOUNCEMENT_THREAD..Channel.PRIVATE_THREAD &&
            next.messageCount != null
        val binding = binding ?: return
        binding.root.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        binding.messageCount.text = messageCountText(next.messageCount ?: 0)
        val reactionVisible = next.reaction != null && next.messageId != null
        binding.reaction.visibility = if (reactionVisible) View.VISIBLE else View.GONE
        binding.reaction.isEnabled = reactionVisible
        binding.separatorTwo.visibility = binding.reaction.visibility
        if (reactionVisible) binding.reaction.configure(requireNotNull(next.reaction), requireNotNull(next.messageId), true)

        binding.followLoadingIndicator.visibility = if (next.followLoading) View.VISIBLE else View.GONE
        binding.following.visibility = if (next.followLoading) View.GONE else View.VISIBLE
        binding.following.setText(if (next.followed == true) R.string.forum_unfollow_button else R.string.forum_follow_button)
        binding.followingContainer.isEnabled = !next.followLoading
    }

    companion object {
        @JvmField var stateSource: (() -> Observable<State>)? = null
    }
}
