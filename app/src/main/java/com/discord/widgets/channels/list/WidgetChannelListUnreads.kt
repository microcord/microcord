package com.discord.widgets.channels.list

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.databinding.WidgetChannelsListUnreadsBinding
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.view.extensions.fadeIn
import com.discord.utilities.view.extensions.fadeOut
import com.discord.utilities.view.extensions.setCompoundDrawableWithIntrinsicBounds
import com.google.android.material.appbar.AppBarLayout

/**
 * The sticky "new messages / new mentions" jump bar that floats over a channel list.
 *
 * It owns no data of its own: [onDatasetChanged] receives the same row list the adapter was
 * given, and every scroll settles into [handleVisibleRangeUpdate], which asks [Model] whether
 * an unread or mentioned row exists *outside* the currently visible range. If one does above
 * the viewport the bar pins to the top and points up; below, it pins to the bottom and points
 * down. Tapping it scrolls three rows past the offending item so the target is not flush with
 * the edge.
 *
 * The bar is inflated from a [ViewStub] on first use, so a list with nothing unread never pays
 * for the view.
 */
class WidgetChannelListUnreads @JvmOverloads constructor(
    private val unreadsStub: ViewStub,
    private val recycler: RecyclerView,
    private val appBarLayout: AppBarLayout? = null,
    private val getItemCount: () -> Int,
    var mentionResId: Int = R.string.new_mentions,
    var unreadsResId: Int = R.string.new_unreads,
    var unreadsEnabled: Boolean = true,
) {

    private val binding: WidgetChannelsListUnreadsBinding by lazy(LazyThreadSafetyMode.NONE) {
        WidgetChannelsListUnreadsBinding.bind(unreadsStub.inflate())
    }

    private var data: List<Any> = emptyList()

    /**
     * Guards the fade-out: before the bar has ever been shown there is nothing to fade, and
     * touching [binding] would inflate the stub for no reason.
     */
    private var unreadsInitialized = false

    init {
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE ||
                    newState == RecyclerView.SCROLL_STATE_SETTLING
                ) {
                    handleVisibleRangeUpdate()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // With reduced motion there is no settling state to wait for, so every scroll
                // frame has to re-evaluate.
                if (isReducedMotionEnabled()) handleVisibleRangeUpdate()
            }
        })
    }

    fun onDatasetChanged(data: List<Any>) {
        this.data = data
        recycler.post { handleVisibleRangeUpdate() }
    }

    fun handleVisibleRangeUpdate() {
        val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return
        val model = Model.get(
            visibleRange = layoutManager.findFirstVisibleItemPosition()..
                layoutManager.findLastVisibleItemPosition(),
            items = data,
            unreadsEnabled = unreadsEnabled,
        )
        // The bar above the viewport wins; only when nothing is unread above do we point down.
        val indicator = model.topIndicator ?: model.bottomIndicator
        val isAbove = model.topIndicator != null

        when {
            indicator == null && unreadsInitialized -> binding.root.fadeOut(FADE_MILLIS)
            indicator != null -> {
                binding.root.fadeIn(FADE_MILLIS)
                binding.root.setOnClickListener { handleClick(indicator, isAbove) }
                unreadsInitialized = true
                onConfigureView(indicator, isAbove)
            }
        }
    }

    private fun handleClick(indicator: Model.Indicator, isAbove: Boolean) {
        val target = if (isAbove) {
            maxOf(indicator.index - SCROLL_OVERSHOOT, 0)
        } else {
            minOf(indicator.index + SCROLL_OVERSHOOT, getItemCount() - 1)
        }
        // Jumping downwards has to collapse the guild banner first or the target lands under it.
        if (!isAbove) appBarLayout?.setExpanded(false)
        if (isReducedMotionEnabled()) {
            recycler.scrollToPosition(target)
        } else {
            recycler.smoothScrollToPosition(target)
        }
    }

    private fun onConfigureView(indicator: Model.Indicator, isAbove: Boolean) {
        val isMention = indicator.type == Model.TYPE_MENTION
        // A mention keeps the brand red and its white-on-red foreground; a plain unread is a
        // raised surface, so both its label and its arrow have to follow the theme.
        val foreground = if (isMention) {
            ContextCompat.getColor(binding.root.context, R.color.white)
        } else {
            ColorCompat.getThemedColor(binding.root, R.attr.colorHeaderSecondary)
        }
        binding.channelsListUnreadsBackground.setBackgroundResource(
            if (isMention) R.drawable.drawable_button_red else R.drawable.drawable_channels_list_unreads,
        )
        binding.channelsListUnreads.setText(getText(indicator))
        binding.channelsListUnreads.setTextColor(foreground)
        binding.channelsListUnreads.setCompoundDrawableWithIntrinsicBounds(
            end = if (isAbove) {
                R.drawable.ic_arrow_upward_white_16dp
            } else {
                R.drawable.ic_arrow_downward_white_16dp
            },
        )
        TextViewCompat.setCompoundDrawableTintList(
            binding.channelsListUnreads,
            ColorStateList.valueOf(foreground),
        )

        when (val params = binding.root.layoutParams) {
            is RelativeLayout.LayoutParams -> {
                params.removeRuleCompat(if (isAbove) RelativeLayout.ALIGN_BOTTOM else RelativeLayout.ALIGN_TOP)
                params.addRule(
                    if (isAbove) RelativeLayout.ALIGN_TOP else RelativeLayout.ALIGN_BOTTOM,
                    recycler.id,
                )
                binding.root.layoutParams = params
            }
            is CoordinatorLayout.LayoutParams -> {
                params.anchorGravity = if (isAbove) android.view.Gravity.TOP else android.view.Gravity.BOTTOM
                params.gravity = if (isAbove) android.view.Gravity.BOTTOM else android.view.Gravity.TOP
                binding.root.layoutParams = params
            }
            else -> error("Unread Layout Params unrecognized: ${params?.javaClass?.name}")
        }

        // The bar duplicates information the rows already expose, so it is skipped by
        // accessibility services rather than announced on every scroll.
        binding.root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun getText(indicator: Model.Indicator): Int =
        if (indicator.type == Model.TYPE_MENTION) mentionResId else unreadsResId

    private fun isReducedMotionEnabled(): Boolean =
        runCatching { StoreStream.getAccessibility().isReducedMotionEnabled() }.getOrDefault(false)

    /**
     * A row that can drive the jump bars. Implemented by the text, voice, thread and private
     * channel items.
     */
    interface UnreadItem {
        val mentionCount: Int
        val isUnread: Boolean

        /** A mentions-only channel still reads as unread, but must not raise a jump bar. */
        val isUnreadImportant: Boolean get() = true
    }

    /**
     * Which indicator, if any, to show at each edge of the list.
     *
     * A `null` indicator means "nothing worth jumping to on that side".
     */
    data class Model(
        val topIndicator: Indicator? = null,
        val bottomIndicator: Indicator? = null,
    ) {
        /** [type] is [TYPE_MENTION] or [TYPE_UNREAD]; [index] is the adapter position to reveal. */
        data class Indicator(val type: Int, val index: Int)

        companion object {
            const val INVALID_INDEX = -1
            const val TYPE_MENTION = 0
            const val TYPE_UNREAD = 1

            /**
             * Scans the rows before and after [visibleRange].
             *
             * A mention always wins over a plain unread, and unread indicators are suppressed
             * entirely when something inside the viewport is already unread — otherwise the bar
             * would nag about state the user can see. Mentions are never suppressed.
             */
            @JvmStatic
            @JvmOverloads
            fun get(
                visibleRange: IntRange,
                items: List<Any>,
                unreadsEnabled: Boolean = true,
            ): Model = try {
                val visibleHasUnread = unreadsEnabled && hasUnread(items, visibleRange)
                Model(
                    topIndicator = findIndicator(
                        items,
                        0 until visibleRange.first,
                        visibleHasUnread,
                        unreadsEnabled,
                    ),
                    bottomIndicator = findIndicator(
                        items,
                        items.lastIndex downTo visibleRange.last + 1,
                        visibleHasUnread,
                        unreadsEnabled,
                    ),
                )
            } catch (ignored: IndexOutOfBoundsException) {
                // The visible range can outrun a list that just shrank; drop the bar for this frame.
                Model()
            }

            /**
             * Walks [progression] in order, so the first mention encountered is the nearest one
             * to the viewport. The first unread is remembered but only returned if no mention
             * turns up at all.
             */
            private fun findIndicator(
                items: List<Any>,
                progression: IntProgression,
                visibleHasUnread: Boolean,
                unreadsEnabled: Boolean,
            ): Indicator? {
                var unreadIndex = INVALID_INDEX
                for (index in progression) {
                    val item = items[index] as? UnreadItem ?: continue
                    if (item.mentionCount > 0) return Indicator(TYPE_MENTION, index)
                    if (unreadsEnabled && !visibleHasUnread &&
                        unreadIndex == INVALID_INDEX && item.isUnread && item.isUnreadImportant
                    ) {
                        unreadIndex = index
                    }
                }
                return if (unreadIndex != INVALID_INDEX) Indicator(TYPE_UNREAD, unreadIndex) else null
            }

            private fun hasUnread(items: List<Any>, range: IntRange): Boolean =
                items.slice(range).any { it is UnreadItem && it.isUnread && it.isUnreadImportant }
        }
    }

    private companion object {
        private const val FADE_MILLIS = 150L

        /** Rows to scroll past the target so it does not land flush against the edge. */
        private const val SCROLL_OVERSHOOT = 3

        /**
         * `RelativeLayout.LayoutParams.removeRule` is API 17+, which is above `minSdk` 21 —
         * but the reference shipped a compat helper, and clearing the rule by setting it to
         * `0` is what that helper does on every level.
         */
        private fun RelativeLayout.LayoutParams.removeRuleCompat(verb: Int) = addRule(verb, 0)
    }
}
