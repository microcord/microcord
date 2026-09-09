package com.discord.utilities.textprocessing.node

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.spans.VerticalPaddingSpan

/**
 * `#`, `##` and `###` markdown headers.
 *
 * Sized relative to the surrounding message rather than in absolute `sp`: chat text tracks the
 * user's font-size setting, and a fixed header size would stop following it.
 */
class HeaderNode<T : BasicRenderContext>(
    private val level: Int,
) : Node<T>() {

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val context = renderContext.context
        val start = builder.length
        getChildren()?.forEach { it.render(builder, renderContext) }

        val scale = when (level) {
            1 -> HEADER_1_SCALE
            2 -> HEADER_2_SCALE
            else -> HEADER_3_SCALE
        }
        val topPaddingRes = when (level) {
            1 -> R.dimen.markdown_messages_header_1_top_padding
            2 -> R.dimen.markdown_messages_header_2_top_padding
            else -> R.dimen.markdown_messages_header_3_top_padding
        }
        val bottomPaddingRes = when (level) {
            1 -> R.dimen.markdown_messages_header_1_bottom_padding
            2 -> R.dimen.markdown_messages_header_2_bottom_padding
            else -> R.dimen.markdown_messages_header_3_bottom_padding
        }

        val spans = listOf(
            RelativeSizeSpan(scale),
            StyleSpan(Typeface.BOLD),
            ForegroundColorSpan(ColorCompat.getThemedColor(context, R.attr.colorHeaderPrimary)),
            VerticalPaddingSpan(
                context.resources.getDimensionPixelSize(topPaddingRes),
                context.resources.getDimensionPixelSize(bottomPaddingRes),
            ),
        )
        // Apply the block style before nested links/mentions, so their colors win.
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or (1 shl Spanned.SPAN_PRIORITY_SHIFT)) }

        if (builder.length == start || builder[builder.length - 1] != '\n') builder.append('\n')
    }

    private companion object {
        /** `heading-xl`, `heading-lg` and `heading-md` against the `text-sm` body of the reference. */
        const val HEADER_1_SCALE = 24f / 14f
        const val HEADER_2_SCALE = 20f / 14f
        const val HEADER_3_SCALE = 16f / 14f
    }
}
