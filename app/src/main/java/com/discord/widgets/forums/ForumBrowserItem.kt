package com.discord.widgets.forums

import androidx.annotation.StringRes
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

sealed class ForumBrowserItem(
    final override val key: String,
    final override val type: Int,
) : MGRecyclerDataPayload {
    data class HeaderItem(@StringRes val stringResId: Int) : ForumBrowserItem(stringResId.toString(), TYPE_HEADER)
    data class PostItem(val post: PostData) : ForumBrowserItem(post.channel.id.toString(), TYPE_POST)
    object LoadingItem : ForumBrowserItem("loading-indicator", TYPE_LOADING)

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_POST = 1
        const val TYPE_LOADING = 2

        val postItemComparatorByMostRecent = compareByDescending<PostItem> {
            it.post.mostRecentMessageTimestamp ?: Long.MIN_VALUE
        }
    }
}
