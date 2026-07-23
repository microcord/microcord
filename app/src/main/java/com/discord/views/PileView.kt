package com.discord.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.discord.R
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

class PileView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    data class Item(
        val getImageUri: (requestedSize: Int) -> String?,
        val getInitials: (() -> String?)?,
    )

    private var maxItems = 3
    private var roundImage = true
    private var roundBackground = true
    private var itemSizePx = dp(16)
    private var overlapPx = 0
    private var itemBackgroundColor = 0
    private var itemPaddingPx = 0
    private var itemTextColor = 0
    private var cutoutThicknessPx = dp(2)
    private var overflowTextSizePx = dp(14)
    @Suppress("unused")
    private var showOverflowItem = false
    private var overflowBackgroundColor = 0
    private val cutoutStyle: CutoutView.Style

    init {
        context.obtainStyledAttributes(attrs, R.styleable.PileView).run {
            maxItems = getInt(R.styleable.PileView_pile_maxItems, maxItems)
            itemSizePx = getDimensionPixelSize(R.styleable.PileView_pile_itemSize, itemSizePx)
            itemBackgroundColor = getColor(R.styleable.PileView_pile_itemBackgroundColor, itemBackgroundColor)
            itemPaddingPx = getDimensionPixelSize(R.styleable.PileView_pile_itemPadding, itemPaddingPx)
            itemTextColor = getColor(R.styleable.PileView_pile_itemTextColor, itemTextColor)
            overlapPx = getDimensionPixelSize(R.styleable.PileView_pile_itemOverlap, overlapPx)
            cutoutThicknessPx = getDimensionPixelSize(
                R.styleable.PileView_pile_itemCutoutThickness,
                cutoutThicknessPx,
            )
            roundImage = getBoolean(R.styleable.PileView_pile_roundImage, roundImage)
            roundBackground = getBoolean(R.styleable.PileView_pile_roundBackground, roundBackground)
            overflowTextSizePx = getDimensionPixelSize(
                R.styleable.PileView_pile_overFlowTextSize,
                overflowTextSizePx,
            )
            showOverflowItem = getBoolean(R.styleable.PileView_pile_showOverflowItem, showOverflowItem)
            overflowBackgroundColor = getColor(
                R.styleable.PileView_pile_overflowBackgroundColor,
                overflowBackgroundColor,
            )
            recycle()
        }
        cutoutStyle = CutoutView.End(
            itemSizePx / 2 + cutoutThicknessPx,
            overlapPx + cutoutThicknessPx,
        )
    }

    fun setItems(items: Collection<Item>) {
        removeAllViews()
        val overflowCount = if (items.size > maxItems) items.size - maxItems + 1 else 0
        val ordinaryCount = if (overflowCount > 0) maxItems - 1 else items.size
        items.take(ordinaryCount).forEachIndexed { index, item ->
            addItem(index, ordinaryCount - 1, overflowCount, item)
        }
        if (overflowCount > 0) addOverflow(overflowCount)
    }

    private fun addItem(index: Int, lastIndex: Int, overflowCount: Int, item: Item) {
        val root = LayoutInflater.from(context).inflate(R.layout.view_pile_item, this, false) as CutoutView
        val background = requireNotNull<SimpleDraweeView>(root.findViewById(R.id.pileItemBg))
        val image = requireNotNull<SimpleDraweeView>(root.findViewById(R.id.pileItemImage))
        val initials = requireNotNull<TextView>(root.findViewById(R.id.pileItemInitials))
        root.id = generateViewId()
        root.layoutParams = LayoutParams(itemSizePx, itemSizePx).apply {
            marginStart = index * itemSizePx - index * overlapPx
        }
        addView(root)
        image.setPadding(itemPaddingPx, itemPaddingPx, itemPaddingPx, itemPaddingPx)
        if (!roundImage) image.hierarchy.roundingParams = null
        if (!roundBackground) background.hierarchy.roundingParams = null
        if (itemBackgroundColor != 0) {
            background.hierarchy.setPlaceholderImage(ColorDrawable(itemBackgroundColor))
            background.visibility = View.VISIBLE
        }
        if (index != lastIndex || overflowCount > 0) root.style = cutoutStyle
        val uri = item.getImageUri(mediaProxySize(itemSizePx))
        initials.visibility = if (uri == null) View.VISIBLE else View.GONE
        initials.text = item.getInitials?.invoke()
        if (itemTextColor != 0) initials.setTextColor(itemTextColor)
        image.visibility = if (uri == null) View.GONE else View.VISIBLE
        if (uri != null) image.setImageURI(uri)
    }

    private fun addOverflow(overflowCount: Int) {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.pile_item_overflow_view, this, false) as CutoutView
        val text = requireNotNull<TextView>(root.findViewById(R.id.pile_item_text))
        root.id = generateViewId()
        root.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, itemSizePx).apply {
            val index = maxItems - 1
            marginStart = index * itemSizePx - index * overlapPx
        }
        text.text = "+$overflowCount"
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, overflowTextSizePx.toFloat())
        val shape = MaterialShapeDrawable(
            ShapeAppearanceModel.builder().setAllCornerSizes(ShapeAppearanceModel.PILL).build(),
        )
        shape.fillColor = ColorStateList.valueOf(
            overflowBackgroundColor.takeIf { it != 0 } ?: itemBackgroundColor,
        )
        text.background = shape
        addView(root)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun mediaProxySize(requested: Int) = MEDIA_PROXY_SIZES.first { it >= requested }

    private companion object {
        val MEDIA_PROXY_SIZES = intArrayOf(
            16, 20, 22, 24, 28, 32, 40, 44, 48, 56, 60, 64, 80, 96, 100, 128,
            160, 240, 256, 300, 320, 480, 512, 600, 640, 1024, 1280, 1536, 2048,
            3072, 4096,
        )
    }
}
