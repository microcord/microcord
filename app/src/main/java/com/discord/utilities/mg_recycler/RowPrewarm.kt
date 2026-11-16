package com.discord.utilities.mg_recycler

import android.app.Activity
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * List rows inflated ahead of time, while the main thread idles behind the boot screen, and handed
 * to the first holders that need them.
 *
 * The chat's first frame and the DM list's first update otherwise inflate every visible row inside
 * one frame, on top of binding and measuring them. A row is only handed out to a list whose context
 * is the one it was inflated with, so it carries the same theme and inflater factories; anything
 * else inflates as usual.
 */
object RowPrewarm {

    private val rows = HashMap<Int, ArrayDeque<View>>()

    /** A prepared row for [layout] made with [parent]'s context, or a freshly inflated one. */
    fun inflate(@LayoutRes layout: Int, parent: ViewGroup): View {
        val ready = rows[layout]?.let { queue ->
            queue.firstOrNull { it.context === parent.context }?.also(queue::remove)
        }
        return ready ?: LayoutInflater.from(parent.context).inflate(layout, parent, false)
    }

    /**
     * Inflates [counts] rows, one per idle moment of the main thread, with [activity]'s inflater.
     * Stops when done or once the activity is finishing; [clear] drops what was not used.
     */
    fun prewarm(activity: Activity, counts: Map<Int, Int>) {
        // Rows need RecyclerView layout params, exactly as inflating into the real list gives them.
        val parent = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
        val plan = ArrayDeque(counts.flatMap { (layout, count) -> List(count) { layout } })
        Looper.myQueue().addIdleHandler {
            if (activity.isFinishing || plan.isEmpty()) return@addIdleHandler false
            val layout = plan.removeFirst()
            val row = runCatching { activity.layoutInflater.inflate(layout, parent, false) }.getOrNull()
            if (row != null) rows.getOrPut(layout, ::ArrayDeque).addLast(row)
            plan.isNotEmpty()
        }
    }

    /** Drops unused rows, which hold their activity. */
    fun clear(activity: Activity) {
        rows.values.forEach { queue -> queue.removeAll { it.context === activity } }
    }
}
