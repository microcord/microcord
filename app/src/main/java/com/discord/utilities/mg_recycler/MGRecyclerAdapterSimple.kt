package com.discord.utilities.mg_recycler

import android.annotation.SuppressLint
import android.util.Log
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.discord.utilities.recycler.DiffCreator
import com.discord.utilities.rx.ui
import rx.Observable
import rx.Subscription
import rx.schedulers.Schedulers

/**
 * The adapter every simple, fully-diffable list screen extends.
 *
 * [setData] replaces the whole row list and derives the minimal set of `RecyclerView` updates:
 *  - the first non-empty assignment cannot be diffed against anything, so it rebinds everything;
 *  - with [deferredDiffs] (the default) the diff is computed on a background scheduler and applied
 *    on the main thread, which keeps big lists from stalling a frame;
 *  - otherwise the diff is computed inline.
 * A superseded background diff is unsubscribed before a newer one starts so updates cannot be
 * applied out of order.
 */
abstract class MGRecyclerAdapterSimple<D : MGRecyclerDataPayload> @JvmOverloads constructor(
    recycler: RecyclerView,
    private val deferredDiffs: Boolean = true,
) : MGRecyclerAdapter<D>(recycler) {

    private val diffCreator =
        DiffCreator<List<D>, MGRecyclerViewHolder<MGRecyclerAdapterSimple<D>, D>>(null)

    private var diffingSubscription: Subscription? = null
    private var onUpdated: ((List<D>, List<D>) -> Unit)? = null

    protected var internalData: List<D> = emptyList()
        private set

    override fun getItemCount(): Int = internalData.size

    override fun getItemViewType(position: Int): Int = getItem(position).type

    override fun getItem(position: Int): D = internalData[position]

    open fun setData(data: List<D>) {
        unsubscribeFromUpdates()
        val oldData = internalData
        when {
            oldData.isEmpty() -> dispatchUpdates(null, oldData, data)
            deferredDiffs -> {
                diffingSubscription = Observable
                    .fromCallable { diffCreator.calculateDiffResult(oldData, data) }
                    .subscribeOn(Schedulers.computation())
                    .ui()
                    .subscribe(
                        { diffResult -> dispatchUpdates(diffResult, oldData, data) },
                        ::handleError,
                    )
            }
            else -> try {
                dispatchUpdates(diffCreator.calculateDiffResult(oldData, data), oldData, data)
            } catch (t: Throwable) {
                handleError(t)
            }
        }
    }

    fun setOnUpdated(onUpdated: (List<D>, List<D>) -> Unit) {
        this.onUpdated = onUpdated
    }

    fun unsubscribeFromUpdates() {
        diffingSubscription?.unsubscribe()
        diffingSubscription = null
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun dispatchUpdates(
        diffResult: DiffUtil.DiffResult?,
        oldData: List<D>,
        newData: List<D>,
    ) {
        internalData = newData
        if (diffResult != null) {
            diffResult.dispatchUpdatesTo(this)
        } else {
            notifyDataSetChanged()
        }
        onUpdated?.invoke(oldData, newData)
    }

    private fun handleError(throwable: Throwable) {
        Log.e(TAG, "Unable to configure recycler. adapterClass=${javaClass.simpleName}", throwable)
    }

    private companion object {
        private const val TAG = "MGRecyclerAdapterSimple"
    }
}
