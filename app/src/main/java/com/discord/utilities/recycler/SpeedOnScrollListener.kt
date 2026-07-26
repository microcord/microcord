package com.discord.utilities.recycler

import androidx.recyclerview.widget.RecyclerView
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import kotlin.math.abs

/**
 * Reports whether a `RecyclerView` is scrolling slowly enough for expensive per-row work.
 *
 * While the user flings a long list, decoding images and running full binds only produces frames
 * nobody sees. Rows subscribe to [thresholdCallback]: `true` means "we are below
 * [maxScrolledPxPerMs], go ahead and load", `false` means "suppress until the fling settles".
 * Reaching [RecyclerView.SCROLL_STATE_IDLE] always re-enables the work.
 */
class SpeedOnScrollListener(
    val maxScrolledPxPerMs: Long = 1L,
    val thresholdCallback: (Boolean) -> Unit = {},
    val orientation: Int = RecyclerView.VERTICAL,
    val clock: Clock = ClockFactory.get(),
) : RecyclerView.OnScrollListener() {

    private var timeStamp: Long = INIT_TIMESTAMP

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        val now = clock.currentTimeMillis()
        val previous = timeStamp
        if (previous == INIT_TIMESTAMP) {
            timeStamp = now
            return
        }
        val scrolledPx = if (orientation == RecyclerView.VERTICAL) dy else dx
        val speed = abs(scrolledPx / (now - previous).toFloat())
        timeStamp = now
        thresholdCallback(speed <= maxScrolledPxPerMs.toFloat())
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
            thresholdCallback(0 < maxScrolledPxPerMs)
        }
    }

    private companion object {
        private const val INIT_TIMESTAMP = -1L
    }
}
