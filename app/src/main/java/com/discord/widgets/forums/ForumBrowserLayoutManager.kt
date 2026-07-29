package com.discord.widgets.forums

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ForumBrowserLayoutManager(recyclerView: RecyclerView) :
    LinearLayoutManager(recyclerView.context, VERTICAL, false) {
    private var onLayoutCompleted: (() -> Unit)? = null

    override fun onLayoutCompleted(state: RecyclerView.State?) {
        super.onLayoutCompleted(state)
        onLayoutCompleted?.invoke()
    }

    fun setOnLayoutCompletedCallback(callback: () -> Unit) {
        onLayoutCompleted = callback
    }
}
