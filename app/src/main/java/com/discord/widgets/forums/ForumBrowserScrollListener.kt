package com.discord.widgets.forums

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ForumBrowserScrollListener : RecyclerView.OnScrollListener() {
    private var onScroll: (() -> Unit)? = null
    private var onScrollEnd: (() -> Unit)? = null
    private var onScrollStateChanged: ((Int) -> Unit)? = null

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        super.onScrollStateChanged(recyclerView, newState)
        onScrollStateChanged?.invoke(newState)
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        super.onScrolled(recyclerView, dx, dy)
        val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
        onScroll?.invoke()
        if (layout.findLastVisibleItemPosition() >= layout.itemCount - LOAD_MORE_THRESHOLD) onScrollEnd?.invoke()
    }

    fun setOnScrollCallback(callback: () -> Unit) { onScroll = callback }
    fun setOnScrollEndCallback(callback: () -> Unit) { onScrollEnd = callback }
    fun setOnScrollStateChangedCallback(callback: (Int) -> Unit) { onScrollStateChanged = callback }

    private companion object { const val LOAD_MORE_THRESHOLD = 5 }
}
