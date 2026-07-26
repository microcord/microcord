package com.discord.utilities.mg_recycler

import android.annotation.SuppressLint
import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.discord.R
import com.discord.utilities.recycler.SelfHealingLinearLayoutManager
import rx.subscriptions.CompositeSubscription

/**
 * Shared base for every list adapter in the app.
 *
 * It owns the recycler it is attached to, a [CompositeSubscription] scoped to the currently bound
 * rows, and the `Companion.configure` wiring that every list screen goes through so they all get
 * the same self-healing layout manager and animation policy.
 */
abstract class MGRecyclerAdapter<D>(
    open var recycler: RecyclerView,
) : RecyclerView.Adapter<MGRecyclerViewHolder<*, D>>() {

    val cellSubscriptions = CompositeSubscription()

    val context: Context get() = recycler.context

    abstract fun getItem(position: Int): D

    override fun onBindViewHolder(holder: MGRecyclerViewHolder<*, D>, position: Int) {
        holder.onBindViewHolder(position)
    }

    /** Unsubscribes every row subscription; called when the owning screen goes away. */
    fun dispose() {
        cellSubscriptions.clear()
    }

    @SuppressLint("StringFormatMatches")
    protected fun invalidViewTypeException(viewType: Int): IllegalArgumentException =
        IllegalArgumentException(context.getString(R.string.android_unknown_view_holder, viewType))

    companion object {
        /**
         * Applies the standard list configuration: a [SelfHealingLinearLayoutManager] preserving
         * any orientation already set, fixed size (the recycler never resizes with its content),
         * and change animations disabled so rebinding a row does not cross-fade.
         */
        @JvmStatic
        fun <D, T : MGRecyclerAdapter<D>> configure(adapter: T): T = try {
            val recycler = adapter.recycler
            val orientation = (recycler.layoutManager as? LinearLayoutManager)?.orientation
                ?: RecyclerView.VERTICAL
            recycler.layoutManager = SelfHealingLinearLayoutManager(
                recycler,
                adapter,
                orientation,
                false,
            ).apply { isSmoothScrollbarEnabled = true }
            recycler.adapter = adapter
            recycler.setHasFixedSize(true)
            (recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            adapter
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
