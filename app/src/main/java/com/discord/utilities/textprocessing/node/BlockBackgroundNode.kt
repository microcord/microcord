package com.discord.utilities.textprocessing.node

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.textprocessing.dpToPixels
import com.discord.utilities.textprocessing.spans.BlockBackgroundSpan
import com.discord.utilities.textprocessing.spans.VerticalPaddingSpan
import com.discord.utilities.textprocessing.themedColor

/**
 * Card-like background used by fenced code blocks.
 *
 * The block is always laid out on its own lines and, when hidden behind an unrevealed spoiler,
 * uses the spoiler background instead of the code background.
 */
class BlockBackgroundNode<RC : BasicRenderContext>(
    private val inQuote: Boolean,
    vararg children: Node<RC>,
) : Node.Parent<RC>(*children), Spoilerable {

    override var isRevealed: Boolean = true

    override fun render(builder: SpannableStringBuilder, renderContext: RC) {
        ensureEndsWithNewline(builder)
        val start = builder.length
        super.render(builder, renderContext)
        ensureEndsWithNewline(builder)

        val context = renderContext.context
        val backgroundColor = when {
            isRevealed -> themedColor(context, R.attr.theme_chat_code)
            else -> (renderContext as? SpoilerNode.RenderContext)?.spoilerColorRes
                ?: themedColor(context, R.attr.theme_chat_spoiler_bg)
        }

        builder.setSpan(
            BlockBackgroundSpan(
                fillColor = backgroundColor,
                borderColor = themedColor(context, R.attr.theme_chat_codeblock_border),
                borderWidth = dpToPixels(context, BORDER_WIDTH_DP),
                cornerRadius = dpToPixels(context, CORNER_RADIUS_DP),
                leftMargin = if (inQuote) BlockQuoteNode.totalLeftMargin(context) else 0,
            ),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            LeadingMarginSpan.Standard(TEXT_INSET_PX),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val verticalPadding = dpToPixels(context, VERTICAL_PADDING_DP)
        builder.setSpan(
            VerticalPaddingSpan(verticalPadding, verticalPadding),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun ensureEndsWithNewline(builder: SpannableStringBuilder) {
        if (builder.isNotEmpty() && builder[builder.length - 1] != '\n') {
            builder.append('\n')
        }
    }

    private companion object {
        const val BORDER_WIDTH_DP = 1
        const val CORNER_RADIUS_DP = 4
        const val VERTICAL_PADDING_DP = 5

        /** Horizontal inset of the code text inside the card, in raw pixels as in the reference. */
        const val TEXT_INSET_PX = 15
    }
}
