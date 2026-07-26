package com.discord.utilities.recycler

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.discord.app.AppComponent
import com.discord.utilities.collections.ShallowPartitionMap
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

/**
 * Computes `RecyclerView` diffs for lists of keyed rows and dispatches the resulting updates.
 *
 * Two escape hatches keep large lists responsive:
 *  - a diff whose estimated edit distance exceeds [EXPENSIVE_DIFF_THRESHOLD] is abandoned and the
 *    caller falls back to a full rebind, because `DiffUtil` is O(n*m) in that regime;
 *  - [dispatchDiffUpdatesAsync] moves the computation off the main thread and applies the result
 *    back on it, which requires an [AppComponent] scope so the work is cancelled with its owner.
 */
class DiffCreator<T : List<DiffKeyProvider>, VH : RecyclerView.ViewHolder>(
    private val asyncDiffSubscriptionScope: AppComponent?,
) {

    private val updateRequestsSubject = PublishSubject.create<UpdateRequest<T, VH>>()
    private val mainThreadHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var asyncDiffSubscription: Subscription? = null

    init {
        if (asyncDiffSubscriptionScope != null) {
            subscribeToAsyncUpdateRequests(asyncDiffSubscriptionScope)
        }
    }

    /**
     * @return the computed diff, or null when the diff was judged too expensive to compute and the
     *   caller should rebind everything instead.
     */
    fun calculateDiffResult(oldItems: T, newItems: T): DiffUtil.DiffResult? {
        if (isExpensiveDiff(oldItems, newItems)) return null
        return DiffUtil.calculateDiff(createDiffUtilCallback(oldItems, newItems), true)
    }

    fun dispatchDiffUpdates(
        adapter: RecyclerView.Adapter<VH>,
        setItems: (T) -> Unit,
        oldItems: T,
        newItems: T,
    ) {
        setItemsAndDispatchUpdate(adapter, setItems, newItems, calculateDiffResult(oldItems, newItems))
    }

    @MainThread
    fun dispatchDiffUpdatesAsync(
        adapter: RecyclerView.Adapter<VH>,
        setItems: (T) -> Unit,
        oldItems: T,
        newItems: T,
    ) {
        val scope = checkNotNull(asyncDiffSubscriptionScope) {
            "to use async diffs, provide an asyncDiffSubscriptionScope"
        }
        val subscription = asyncDiffSubscription
        if (subscription == null || subscription.isUnsubscribed) {
            subscribeToAsyncUpdateRequests(scope)
        }
        updateRequestsSubject.onNext(UpdateRequest(adapter, setItems, oldItems, newItems))
    }

    private fun createDiffUtilCallback(oldItems: T, newItems: T) = object : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldItems.size

        override fun getNewListSize(): Int = newItems.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldItems[oldItemPosition].key == newItems[newItemPosition].key

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldItems[oldItemPosition] == newItems[newItemPosition]
    }

    /**
     * Estimates the edit distance by counting keys that only exist on one side. Partitioning the
     * key set keeps this cheap for the very large lists (member lists, message history) where the
     * answer actually matters.
     */
    private fun isExpensiveDiff(oldItems: T, newItems: T): Boolean {
        if (Math.abs(newItems.size - oldItems.size) > EXPENSIVE_DIFF_THRESHOLD) return true

        val remainingOldKeys = ShallowPartitionMap.create<String, Unit>(mapSize = oldItems.size)
        val addedKeys = ArrayList<String>()
        for (item in oldItems) remainingOldKeys[item.key] = Unit
        for (item in newItems) {
            val key = item.key
            if (!remainingOldKeys.containsKey(key)) addedKeys.add(key)
            remainingOldKeys.remove(key)
        }
        return addedKeys.size + remainingOldKeys.size > EXPENSIVE_DIFF_THRESHOLD
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setItemsAndDispatchUpdate(
        adapter: RecyclerView.Adapter<VH>,
        setItems: (T) -> Unit,
        newItems: T,
        diffResult: DiffUtil.DiffResult?,
    ) {
        setItems(newItems)
        if (diffResult != null) {
            diffResult.dispatchUpdatesTo(adapter)
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    private fun subscribeToAsyncUpdateRequests(asyncDiffSubscriptionScope: AppComponent) {
        asyncDiffSubscription = updateRequestsSubject
            .onBackpressureBuffer()
            .observeOn(Schedulers.computation())
            .map { request -> request to calculateDiffResult(request.oldItems, request.newItems) }
            .takeUntil(asyncDiffSubscriptionScope.unsubscribeSignal)
            .subscribe(
                { (request, diffResult) ->
                    mainThreadHandler.post {
                        setItemsAndDispatchUpdate(
                            request.adapter,
                            request.setItems,
                            request.newItems,
                            diffResult,
                        )
                    }
                },
                { throwable -> Log.e(TAG, "Unable to compute async diff.", throwable) },
            )
    }

    data class UpdateRequest<T, VH : RecyclerView.ViewHolder>(
        val adapter: RecyclerView.Adapter<VH>,
        val setItems: (T) -> Unit,
        val oldItems: T,
        val newItems: T,
    )

    private companion object {
        private const val TAG = "DiffCreator"
        private const val EXPENSIVE_DIFF_THRESHOLD = 225
    }
}
