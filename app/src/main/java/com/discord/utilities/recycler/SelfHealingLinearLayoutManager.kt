package com.discord.utilities.recycler

import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * A [LinearLayoutManager] that recovers from adapter/layout desynchronisation instead of crashing.
 *
 * When an adapter reports item counts that disagree with the views RecyclerView is holding, layout
 * throws from deep inside the support library. Rather than take down the process, the adapter is
 * fully reset on the next frame and the inconsistency is logged.
 */
class SelfHealingLinearLayoutManager(
    private val recyclerView: RecyclerView,
    private val adapter: RecyclerView.Adapter<*>,
    orientation: Int = RecyclerView.VERTICAL,
    reverseLayout: Boolean = false,
) : LinearLayoutManager(recyclerView.context, orientation, reverseLayout) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IllegalArgumentException) {
            resetAdapter(e)
        } catch (e: IndexOutOfBoundsException) {
            resetAdapter(e)
        } catch (e: RuntimeException) {
            val message = e.message.orEmpty()
            if (message.contains(UNHIDE_MARKER) || message.contains(HIDE_MARKER)) {
                resetAdapter(e)
            } else {
                throw e
            }
        }
    }

    @Suppress("NotifyDataSetChanged")
    private fun resetAdapter(exception: Throwable) {
        recyclerView.post {
            adapter.notifyDataSetChanged()
            recyclerView.invalidate()
        }
        logWarning(exception)
    }

    private fun logWarning(exception: Throwable) {
        Log.w(
            TAG,
            "Invalid adapter configuration.",
            Exception("Triggered in adapter: ${adapter.javaClass}", exception),
        )
    }

    private companion object {
        private const val TAG = "SelfHealingLayout"
        private const val UNHIDE_MARKER = "trying to unhide"
        private const val HIDE_MARKER = "trying to hide"
    }
}
