package com.discord.widgets.chat.input.sticker

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppComponent
import com.discord.i18n.i18nFormat
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.rx.ui
import com.discord.utilities.stickers.StickerUtils
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.sticker.StickerView
import com.discord.widgets.stickers.StickerSendability
import com.discord.widgets.stickers.setStickerCountText
import rx.Subscription
import rx.subjects.BehaviorSubject

/**
 * The sticker grid: section headers spanning the full width, sticker tiles one column each.
 *
 * The column count is derived from the recycler's own width so the tiles keep their intended size
 * on any screen, and it is the same number the span-size lookup gives headers.
 *
 * Two deliberate gaps against the reference, both because the type they need lives in the
 * unreconstructed `widgets/chat/input/expression` package:
 *  - it does not extend `WidgetExpressionPickerAdapter`, so there is **no sticky header** pinned to
 *    the top of the grid while scrolling through a section;
 *  - there is no `ExpressionPickerItemDecoration`, so section spacing comes from the item layouts
 *    alone.
 * Neither affects which stickers are shown or what tapping one does.
 */
class WidgetStickerAdapter(
    recycler: RecyclerView,
    private val onStickerItemSelected: (StickerItem) -> Unit,
    private val onStickerHeaderItemClicked: ((StoreHeaderItem) -> Unit)? = null,
    private val recyclerScrollingWithinThresholdSubject: BehaviorSubject<Boolean>? = null,
    internal val appComponent: AppComponent,
    internal val showStickerPackDescriptions: Boolean = false,
) : MGRecyclerAdapterSimple<MGRecyclerDataPayload>(recycler) {

    val numColumns: Int = calculateNumOfColumns(
        recycler,
        recycler.context.resources.getDimension(R.dimen.chat_input_sticker_size),
        DEFAULT_NUM_COLUMNS,
    )

    val gridLayoutManager: GridLayoutManager = GridLayoutManager(recycler.context, numColumns).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                when (getItemViewType(position)) {
                    ITEM_TYPE_OWNED_HEADER, ITEM_TYPE_STORE_HEADER -> numColumns
                    else -> 1
                }
        }
    }

    /** Set by the host so a scroll can move the category rail's selection. */
    var onScrollPositionListener: ((Int) -> Unit)? = null

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val first = gridLayoutManager.findFirstVisibleItemPosition()
            if (first != RecyclerView.NO_POSITION) onScrollPositionListener?.invoke(first)
        }
    }

    init {
        recycler.layoutManager = gridLayoutManager
        recycler.adapter = this
        recycler.addOnScrollListener(scrollListener)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, MGRecyclerDataPayload> = when (viewType) {
        ITEM_TYPE_OWNED_HEADER -> OwnedHeaderViewHolder(this)
        ITEM_TYPE_STICKER ->
            StickerViewHolder(this, recyclerScrollingWithinThresholdSubject, onStickerItemSelected)
        ITEM_TYPE_STORE_HEADER -> StoreHeaderViewHolder(this, onStickerHeaderItemClicked)
        else -> throw invalidViewTypeException(viewType)
    }

    /**
     * Jumps without animating and without letting the resulting scroll move the category selection
     * — otherwise tapping a tab would fight the scroll listener all the way to the target.
     */
    fun scrollToPosition(position: Int) {
        recycler.removeOnScrollListener(scrollListener)
        gridLayoutManager.scrollToPositionWithOffset(position, 0)
        recycler.addOnScrollListener(scrollListener)
    }

    companion object {
        const val DEFAULT_NUM_COLUMNS = 4
        const val ITEM_TYPE_OWNED_HEADER = 0
        const val ITEM_TYPE_STICKER = 1
        const val ITEM_TYPE_STORE_HEADER = 2

        /**
         * How many [itemSizePx]-wide tiles fit across [recyclerView], falling back to [fallback]
         * before the recycler has been measured.
         */
        fun calculateNumOfColumns(
            recyclerView: RecyclerView,
            itemSizePx: Float,
            fallback: Int,
        ): Int {
            if (itemSizePx <= 0f) return fallback
            val margins = (recyclerView.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.let { it.marginStart + it.marginEnd } ?: 0
            val available = recyclerView.context.resources.displayMetrics.widthPixels - margins
            return (available / itemSizePx).toInt().takeIf { it > 0 } ?: fallback
        }
    }
}

/**
 * A sticker tile.
 *
 * Loading a sticker means downloading and decoding an APNG or Lottie file, so during a fast fling
 * the tile stays on its placeholder and only starts that work once
 * [recyclerScrollingWithinThresholdSubject] says the list has slowed down.
 */
class StickerViewHolder(
    adapter: WidgetStickerAdapter,
    private val recyclerScrollingWithinThresholdSubject: BehaviorSubject<Boolean>?,
    private val onStickerItemSelected: (StickerItem) -> Unit,
) : MGRecyclerViewHolder<WidgetStickerAdapter, MGRecyclerDataPayload>(
    R.layout.sticker_picker_sticker_item,
    adapter,
) {

    private val stickerView: StickerView = itemView.findViewById(R.id.sticker_picker_sticker)
    private var scrollingSpeedSubscription: Subscription? = null

    override val subscription: Subscription? get() = stickerView.getSubscription()

    override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
        super.onConfigure(position, data)
        val item = data as? StickerItem ?: return
        if (recyclerScrollingWithinThresholdSubject == null) {
            configureSticker(item)
            return
        }
        scrollingSpeedSubscription?.unsubscribe()
        scrollingSpeedSubscription = recyclerScrollingWithinThresholdSubject
            .distinctUntilChanged()
            .ui(adapter.appComponent)
            .subscribe { withinThreshold ->
                if (withinThreshold) {
                    configureSticker(item)
                    scrollingSpeedSubscription?.unsubscribe()
                    scrollingSpeedSubscription = null
                }
            }
    }

    private fun configureSticker(item: StickerItem) {
        stickerView.setSticker(item.sticker, PICKER_ANIMATION_SETTING)
        // A sticker the user cannot send is dimmed rather than hidden: tapping it still explains why.
        stickerView.alpha = if (item.sendability == StickerSendability.SENDABLE) 1f else 0.25f
        stickerView.setOnClickListener {
            onStickerItemSelected(item)
            stickerView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /** Called when the recycler gives this holder up, so a half-finished download is abandoned. */
    fun cancelLoading() {
        scrollingSpeedSubscription?.unsubscribe()
        scrollingSpeedSubscription = null
    }

    companion object {
        const val PICKER_ANIMATION_SETTING = StickerUtils.ALWAYS_ANIMATE
    }
}

/** "FREQUENTLY USED" / a pack name / a guild name above its stickers. */
class OwnedHeaderViewHolder(
    adapter: WidgetStickerAdapter,
) : MGRecyclerViewHolder<WidgetStickerAdapter, MGRecyclerDataPayload>(
    R.layout.expression_picker_header_item,
    adapter,
) {
    // The layout's root *is* the TextView.
    private val headerText: TextView = itemView as TextView

    override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
        super.onConfigure(position, data)
        val headerType = (data as? HeaderItem)?.headerType ?: return
        headerText.text = when (headerType) {
            is HeaderType.PackItem -> headerType.pack.name
            is HeaderType.GuildItem -> headerType.guild.name
            HeaderType.Recent -> itemView.context.getString(R.string.sticker_picker_categories_recent)
        }
        headerText.contentDescription = itemView.context.i18nFormat(
            R.string.sticker_category_a11y_label,
            "categoryName" to headerText.text,
        )
    }
}

/** The pack-store header: name, sticker count, and the animated / Nitro badges. */
class StoreHeaderViewHolder(
    adapter: WidgetStickerAdapter,
    private val onStickerHeaderItemClicked: ((StoreHeaderItem) -> Unit)?,
) : MGRecyclerViewHolder<WidgetStickerAdapter, MGRecyclerDataPayload>(
    R.layout.sticker_store_header_item,
    adapter,
) {
    private val title: TextView = itemView.findViewById(R.id.sticker_store_header_item_title)
    private val subtitle: TextView = itemView.findViewById(R.id.sticker_store_header_item_subtitle)
    private val description: TextView =
        itemView.findViewById(R.id.sticker_store_header_item_description)
    private val animatedBadge: View = itemView.findViewById(R.id.sticker_store_header_item_animated)
    private val premiumBadge: View = itemView.findViewById(R.id.sticker_store_header_item_premium)
    private val container: View = itemView.findViewById(R.id.sticker_store_header_item_container)

    override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
        super.onConfigure(position, data)
        val item = data as? StoreHeaderItem ?: return
        title.text = item.pack.name
        subtitle.setStickerCountText(item.pack.stickers.size)
        animatedBadge.visibility = if (item.pack.isAnimatedPack()) View.VISIBLE else View.GONE
        premiumBadge.visibility = if (item.pack.isPremiumPack()) View.VISIBLE else View.GONE
        if (adapter.showStickerPackDescriptions) {
            description.setTextAndVisibilityBy(item.pack.description)
        } else {
            description.visibility = View.GONE
        }
        // Only a pack with something to explain gets the details popup.
        val hasDetails =
            item.pack.isAnimatedPack() || item.pack.isPremiumPack() || item.pack.isLimitedPack()
        if (hasDetails) {
            container.setOnClickListener { onStickerHeaderItemClicked?.invoke(item) }
        } else {
            container.setOnClickListener(null)
        }
    }
}
