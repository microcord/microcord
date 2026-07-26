package com.discord.utilities.textprocessing.node

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.textprocessing.spans.VerticalPaddingSpan

/**
 * `#`, `##` and `###` markdown headers. Four or more markers still render as an H3, matching the
 * reference, which clamps everything past the third level.
 */
class HeaderNode<T : BasicRenderContext>(
    private val numHeaderIndicators: Int,
) : Node<T>() {

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val context = renderContext.context
        val start = builder.length
        getChildren()?.forEach { it.render(builder, renderContext) }

        val topPaddingRes = when (numHeaderIndicators) {
            1 -> R.dimen.markdown_messages_header_1_top_padding
            2 -> R.dimen.markdown_messages_header_2_top_padding
            else -> R.dimen.markdown_messages_header_3_top_padding
        }
        val bottomPaddingRes = when (numHeaderIndicators) {
            1 -> R.dimen.markdown_messages_header_1_bottom_padding
            2 -> R.dimen.markdown_messages_header_2_bottom_padding
            else -> R.dimen.markdown_messages_header_3_bottom_padding
        }
        val textAppearance = when (numHeaderIndicators) {
            1 -> R.style.Markdown_Messages_Header1
            2 -> R.style.Markdown_Messages_Header2
            else -> R.style.Markdown_Messages_Header3
        }

        val spans = listOf(
            TextAppearanceSpan(context, textAppearance),
            VerticalPaddingSpan(
                context.resources.getDimensionPixelSize(topPaddingRes),
                context.resources.getDimensionPixelSize(bottomPaddingRes),
            ),
        )
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
}
