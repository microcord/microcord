package com.discord.utilities.textprocessing.node

import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.spans.BulletSpan
import com.discord.utilities.textprocessing.spans.VerticalPaddingSpan

/**
 * `- item` / `* item` markdown list entry.
 *
 * [nestedLevel] is 1 for a top level item and 2 for an indented one; nested items are drawn with
 * a hollow bullet, matching the web client.
 */
class BulletListNode<T : BasicRenderContext>(
    val nestedLevel: Int,
    private val includesNewline: Boolean,
    val marker: String? = null,
) : Node<T>() {

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val context = renderContext.context
        val start = builder.length
        marker?.let { builder.append(it).append(". ") }
        getChildren()?.forEach { it.render(builder, renderContext) }

        val resources = context.resources
        val gapWidth = resources.getDimensionPixelSize(R.dimen.markdown_bullet_gap)
        val leadingMargin = gapWidth * nestedLevel
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.markdown_bullet_vertical_padding)
        val bulletStyle = if (nestedLevel > 1) Paint.Style.STROKE else Paint.Style.FILL

        val spans = mutableListOf<Any>(
            VerticalPaddingSpan(verticalPadding, verticalPadding),
            LeadingMarginSpan.Standard(leadingMargin),
        )
        if (marker == null) {
            spans += BulletSpan(
                gapWidth = gapWidth,
                color = ColorCompat.getThemedColor(context, R.attr.primary_400),
                bulletRadius = MARKDOWN_BULLET_RADIUS,
                style = bulletStyle,
            )
        }
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

        if (includesNewline) {
            builder.append("\n")
        }
    }

    private companion object {
        const val MARKDOWN_BULLET_RADIUS = 4
    }
}
