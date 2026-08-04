package com.discord.widgets.chat.managereactions

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.utilities.user.NicknameUtils
import com.discord.R
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.facebook.drawee.view.SimpleDraweeView
import com.discord.utilities.user.UserUtils.getDisplayName

class ManageReactionsResultsAdapter(recycler: RecyclerView) :
    MGRecyclerAdapterSimple<MGRecyclerDataPayload>(recycler, deferredDiffs = false) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<ManageReactionsResultsAdapter, MGRecyclerDataPayload> = when (viewType) {
        VIEW_TYPE_USER -> ReactionUserViewHolder(this)
        VIEW_TYPE_LOADING -> LoadingViewHolder(this)
        VIEW_TYPE_ERROR -> ErrorViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    class ErrorItem(
        val channelId: Long,
        val messageId: Long,
        val emoji: MessageReactionEmoji,
    ) : MGRecyclerDataPayload {
        override val type = VIEW_TYPE_ERROR
        override val key = type.toString()
    }

    class LoadingItem : MGRecyclerDataPayload {
        override val type = VIEW_TYPE_LOADING
        override val key = type.toString()
    }

    data class ReactionUserItem(
        val user: User,
        val channelId: Long,
        val messageId: Long,
        val emoji: MessageReactionEmoji,
        val canDelete: Boolean,
        val guildMember: GuildMember?,
    ) : MGRecyclerDataPayload {
        override val key = user.id.toString()
        override val type = VIEW_TYPE_USER
    }

    private class ErrorViewHolder(adapter: ManageReactionsResultsAdapter) :
        MGRecyclerViewHolder<ManageReactionsResultsAdapter, MGRecyclerDataPayload>(
            R.layout.widget_manage_reactions_result_error,
            adapter,
        ) {
        private val retry = itemView.findViewById<ImageView>(R.id.manage_reactions_result_error_img)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            val item = data as ErrorItem
            retry.setOnClickListener {
                StoreStream.getMessageReactions().forceRetryFetchReactions(
                    item.channelId,
                    item.messageId,
                    item.emoji,
                )
            }
        }
    }

    private class LoadingViewHolder(adapter: ManageReactionsResultsAdapter) :
        MGRecyclerViewHolder<ManageReactionsResultsAdapter, MGRecyclerDataPayload>(
            R.layout.widget_manage_reactions_result_loading,
            adapter,
        )

    private class ReactionUserViewHolder(adapter: ManageReactionsResultsAdapter) :
        MGRecyclerViewHolder<ManageReactionsResultsAdapter, MGRecyclerDataPayload>(
            R.layout.widget_manage_reactions_result_user,
            adapter,
        ) {
        private val remove = itemView.findViewById<ImageView>(R.id.manage_reactions_result_remove_reaction)
        private val avatar = itemView.findViewById<SimpleDraweeView>(R.id.manage_reactions_result_user_avatar)
        private val name = itemView.findViewById<TextView>(R.id.manage_reactions_result_user_name)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            val item = data as ReactionUserItem
            name.text = item.user.getDisplayName(
                item.guildMember?.nick
                    ?: NicknameUtils.getNickname(null, item.channelId, item.user.id),
            )
            IconUtils.setIcon(avatar, item.user, R.dimen.avatar_size_standard, guildMember = item.guildMember)
            remove.visibility = if (item.canDelete) View.VISIBLE else View.GONE
            remove.setOnClickListener {
                StoreStream.getMessageReactions().deleteEmoji(
                    item.channelId,
                    item.messageId,
                    item.emoji,
                    item.user.id,
                )
            }
        }
    }

    private companion object {
        const val VIEW_TYPE_USER = 0
        const val VIEW_TYPE_LOADING = 1
        const val VIEW_TYPE_ERROR = 2
    }
}
