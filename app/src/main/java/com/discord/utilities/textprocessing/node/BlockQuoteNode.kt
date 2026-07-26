package com.discord.utilities.textprocessing.node

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.textprocessing.dpToPixels
import com.discord.utilities.textprocessing.spans.QuoteSpan
import com.discord.utilities.textprocessing.themedColor

/**
 * `> quoted` / `>>> quoted block`.
 *
 * Renders its children between two shrunken newlines so the quote is visually separated from the
 * surrounding text, and draws a single stripe down the whole quote.
 */
class BlockQuoteNode<T : BasicRenderContext> : Node<T>() {

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val context = renderContext.context
        val separatorHeight = dpToPixels(context, SEPARATOR_DP)

        builder.append('\n')
        builder.setSpan(
            AbsoluteSizeSpan(separatorHeight),
            builder.length - 1,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        val start = builder.length
        getChildren()?.forEach { it.render(builder, renderContext) }
        if (builder.length == start) {
            // An empty quote still needs a character to hang the stripe on.
            builder.append(' ')
        }

        builder.setSpan(
            QuoteSpan(
                themedColor(context, R.attr.theme_chat_block_quote_divider),
                stripeWidth(context),
                gapWidth(context),
            ),
            start,
            builder.length,
            QUOTE_SPAN_FLAGS,
        )

        builder.append('\n')
        builder.setSpan(
            AbsoluteSizeSpan(separatorHeight),
            builder.length - 1,
            builder.length,
            QUOTE_SPAN_FLAGS,
        )
    }

    companion object {
        private const val STRIPE_DP = 3
        private const val GAP_DP = 8
        private const val SEPARATOR_DP = 8

        /**
         * `SPAN_EXCLUSIVE_EXCLUSIVE` plus a high priority so the quote stripe wins over the
         * character styles that markdown nested inside the quote applies.
         */
        private const val QUOTE_SPAN_FLAGS =
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or (0xC8 shl Spanned.SPAN_PRIORITY_SHIFT)

        fun stripeWidth(context: Context): Int = dpToPixels(context, STRIPE_DP)

        fun gapWidth(context: Context): Int = dpToPixels(context, GAP_DP)

        /** Left inset a block occupies when it is nested inside a quote. */
        fun totalLeftMargin(context: Context): Int = stripeWidth(context) + gapWidth(context)
    }
}
