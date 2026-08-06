package com.discord.utilities.textprocessing.node

import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.spans.BulletSpan
import com.discord.utilities.textprocessing.spans.FixedWidthSpan
import com.discord.utilities.textprocessing.spans.VerticalPaddingSpan

/**
 * A whole `- item` / `1. item` list.
 *
 * Items are held flat rather than nested: an indented sub-list is a sibling of the item that
 * introduced it, carrying its own [ListItemNode.level]. Nesting them would put the outer item's
 * bullet and margin spans across the inner items too, doubling their indent.
 */
class ListNode<T : BasicRenderContext> : Node<T>() {

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        if (builder.isNotEmpty() && builder[builder.length - 1] != '\n') builder.append('\n')
        getChildren()?.forEach { it.render(builder, renderContext) }
    }
}

/**
 * One entry of a [ListNode], already resolved to how it draws: [marker] is the rendered number of
 * an ordered item and null for a bulleted one, and [level] is its zero-based nesting depth.
 */
class ListItemNode<T : BasicRenderContext>(
    val marker: String?,
    val level: Int,
) : Node<T>() {

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val context = renderContext.context
        val resources = context.resources
        val gapWidth = resources.getDimensionPixelSize(R.dimen.markdown_bullet_gap)
        val bulletRadius = resources.getDimensionPixelSize(R.dimen.markdown_bullet_radius)
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.markdown_bullet_vertical_padding)
        val indent = resources.getDimensionPixelSize(R.dimen.markdown_list_indent)

        val start = builder.length
        marker?.let {
            builder.append(it)
            // The marker ends in a space; pinning its width is what makes a number sit the same
            // distance from its text as a bullet does.
            builder.setSpan(
                FixedWidthSpan(gapWidth),
                builder.length - 1,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        getChildren()?.forEach { it.render(builder, renderContext) }

        val spans = mutableListOf<Any>(VerticalPaddingSpan(verticalPadding, 0))
        // A top-level item lines up with ordinary message text; only nesting indents.
        if (level > 0) spans += LeadingMarginSpan.Standard(indent * level)
        if (marker == null) {
            spans += BulletSpan(
                gapWidth = gapWidth,
                color = ColorCompat.getThemedColor(context, R.attr.primary_400),
                bulletRadius = bulletRadius,
                strokeWidth = bulletRadius / 2f,
                style = if (level > 0) Paint.Style.STROKE else Paint.Style.FILL,
            )
        }
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

        if (builder.length == start || builder[builder.length - 1] != '\n') builder.append('\n')
    }
}
