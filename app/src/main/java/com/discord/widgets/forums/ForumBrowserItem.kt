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

/**
 * Drops posts that do not carry the filtered tags, and any section header left with nothing under
 * it. [setting] decides whether carrying one of them is enough or all are required.
 */
internal fun filterForumItemsByTags(
    items: List<ForumBrowserItem>,
    tagIds: Set<String>,
    setting: ForumTagSetting,
): List<ForumBrowserItem> {
    if (tagIds.isEmpty()) return items
    val kept = items.filter { item ->
        if (item !is ForumBrowserItem.PostItem) return@filter true
        val applied = item.post.channel.appliedTags.orEmpty().map(Any::toString).toSet()
        when (setting) {
            ForumTagSetting.MATCH_SOME -> applied.any { it in tagIds }
            ForumTagSetting.MATCH_ALL -> applied.containsAll(tagIds)
        }
    }
    return kept.filterIndexed { index, item ->
        item !is ForumBrowserItem.HeaderItem ||
            kept.drop(index + 1).firstOrNull() is ForumBrowserItem.PostItem
    }
}

internal fun sortForumItems(items: List<ForumBrowserItem>, order: ForumSortOrder): List<ForumBrowserItem> {
    val comparator = compareByDescending<ForumBrowserItem.PostItem> { it.post.isPinned }.then(
        when (order) {
            ForumSortOrder.LATEST_ACTIVITY -> ForumBrowserItem.postItemComparatorByMostRecent
            ForumSortOrder.CREATION_DATE -> compareByDescending { it.post.channel.id }
        },
    )
    val sorted = ArrayList<ForumBrowserItem>(items.size)
    var index = 0
    while (index < items.size) {
        if (items[index] !is ForumBrowserItem.PostItem) {
            sorted += items[index++]
            continue
        }
        val end = (index until items.size).firstOrNull { items[it] !is ForumBrowserItem.PostItem } ?: items.size
        sorted += items.subList(index, end).filterIsInstance<ForumBrowserItem.PostItem>().sortedWith(comparator)
        index = end
    }
    return sorted
}
