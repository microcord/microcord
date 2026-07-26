package com.discord.utilities.mg_recycler

import android.view.LayoutInflater
import android.view.View
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import rx.Subscription

/**
 * Base view holder for [MGRecyclerAdapter] lists.
 *
 * A row may own a long-lived rx subscription. Because holders are recycled, the subscription from
 * the previous binding is removed (and therefore unsubscribed) *before* the holder is reconfigured
 * and the new one is registered afterwards, so a recycled row never keeps feeding the item it used
 * to display.
 */
open class MGRecyclerViewHolder<T : MGRecyclerAdapter<D>, D>(
    itemView: View,
    protected val adapter: T,
) : RecyclerView.ViewHolder(itemView) {

    constructor(@LayoutRes layout: Int, adapter: T) : this(
        LayoutInflater.from(adapter.recycler.context).inflate(layout, adapter.recycler, false),
        adapter,
    )

    /** The subscription currently owned by this row, if any. */
    protected open val subscription: Subscription? get() = null

    fun onBindViewHolder(position: Int): T {
        subscription?.let { adapter.cellSubscriptions.remove(it) }
        onConfigure(position, adapter.getItem(position))
        subscription?.let { adapter.cellSubscriptions.add(it) }
        return adapter
    }

    protected open fun onConfigure(position: Int, data: D) = Unit
}
