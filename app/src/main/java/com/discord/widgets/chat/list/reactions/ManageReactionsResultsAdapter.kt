package com.discord.widgets.chat.list.reactions

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.stores.StoreMessageReactions
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.facebook.drawee.view.SimpleDraweeView

class ManageReactionsResultsAdapter(
    recycler: RecyclerView,
    private val store: StoreMessageReactions,
) : MGRecyclerAdapterSimple<ResultItem>(recycler, deferredDiffs = false) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, ResultItem> = when (viewType) {
        ResultItem.TYPE_USER -> UserViewHolder(this)
        ResultItem.TYPE_LOADING -> LoadingViewHolder(this)
        ResultItem.TYPE_ERROR -> ErrorViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    private class UserViewHolder(adapter: ManageReactionsResultsAdapter) :
        MGRecyclerViewHolder<ManageReactionsResultsAdapter, ResultItem>(
            R.layout.widget_manage_reactions_result_user,
            adapter,
        ) {
        private val remove = itemView.findViewById<ImageView>(R.id.manage_reactions_result_remove_reaction)
        private val avatar = itemView.findViewById<SimpleDraweeView>(R.id.manage_reactions_result_user_avatar)
        private val name = itemView.findViewById<TextView>(R.id.manage_reactions_result_user_name)

        override fun onConfigure(position: Int, data: ResultItem) {
            val item = data as ResultItem.ReactionUser
            name.text = item.guildMember?.nick ?: item.user.username
            IconUtils.setIcon(avatar, item.user, R.dimen.avatar_size_standard, guildMember = item.guildMember)
            remove.visibility = if (item.canDelete) View.VISIBLE else View.GONE
            remove.setOnClickListener(if (item.canDelete) View.OnClickListener {
                adapter.store.deleteEmoji(item.channelId, item.messageId, item.emoji, item.user.id)
            } else null)
        }
    }

    private class LoadingViewHolder(adapter: ManageReactionsResultsAdapter) :
        MGRecyclerViewHolder<ManageReactionsResultsAdapter, ResultItem>(
            R.layout.widget_manage_reactions_result_loading,
            adapter,
        )

    private class ErrorViewHolder(adapter: ManageReactionsResultsAdapter) :
        MGRecyclerViewHolder<ManageReactionsResultsAdapter, ResultItem>(
            R.layout.widget_manage_reactions_result_error,
            adapter,
        ) {
        override fun onConfigure(position: Int, data: ResultItem) {
            val item = data as ResultItem.Error
            itemView.findViewById<View>(R.id.manage_reactions_result_error_img).setOnClickListener {
                adapter.store.forceRetryFetchReactions(item.channelId, item.messageId, item.emoji)
            }
        }
    }
}
