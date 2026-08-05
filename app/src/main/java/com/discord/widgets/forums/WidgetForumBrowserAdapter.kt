package com.discord.widgets.forums

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.utilities.dimen.dpToPixels

class WidgetForumBrowserAdapter(
    private val onPostClick: (Channel) -> Unit,
) : ListAdapter<ForumBrowserItem, RecyclerView.ViewHolder>(DIFF) {

    val itemDecoration = object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            if (position !in currentList.indices) return
            when (getItem(position).type) {
                ForumBrowserItem.TYPE_HEADER -> {
                    outRect.top = dpToPixels(24)
                    outRect.bottom = dpToPixels(8)
                    outRect.left = dpToPixels(16)
                }
                ForumBrowserItem.TYPE_POST -> {
                    outRect.top = if (position == 0) dpToPixels(12) else 0
                    outRect.bottom = dpToPixels(12)
                }
            }
        }
    }

    fun setData(items: List<ForumBrowserItem>) = submitList(items)

    override fun getItemViewType(position: Int) = getItem(position).type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = when (viewType) {
            ForumBrowserItem.TYPE_HEADER -> R.layout.forum_browser_header_item
            ForumBrowserItem.TYPE_POST -> R.layout.forum_browser_post_item
            ForumBrowserItem.TYPE_LOADING -> R.layout.forum_browser_loading_item
            else -> error("Invalid view type: $viewType")
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return when (viewType) {
            ForumBrowserItem.TYPE_HEADER -> HeaderHolder(view)
            ForumBrowserItem.TYPE_POST -> PostHolder(view as ForumBrowserPostView)
            else -> LoadingHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ForumBrowserItem.HeaderItem -> (holder as HeaderHolder).title.setText(item.stringResId)
            is ForumBrowserItem.PostItem -> (holder as PostHolder).bind(item.post)
            ForumBrowserItem.LoadingItem -> Unit
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = requireNotNull(view.findViewById(R.id.header_name))
    }

    private inner class PostHolder(private val postView: ForumBrowserPostView) : RecyclerView.ViewHolder(postView) {
        fun bind(post: PostData) {
            postView.configurePost(post)
            postView.setOnClickListener { onPostClick(post.channel) }
            postView.setOnLongClickListener(null)
        }
    }

    private class LoadingHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ForumBrowserItem>() {
            override fun areItemsTheSame(old: ForumBrowserItem, new: ForumBrowserItem) =
                old.key == new.key && old.type == new.type

            override fun areContentsTheSame(old: ForumBrowserItem, new: ForumBrowserItem) = old == new
        }
    }
}
