package com.discord.utilities.textprocessing.node

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat

/**
 * A `-# subtext` line.
 *
 * Supporting text uses the desktop's 12px-to-14px body ratio and follows the user's font scale.
 */
class SubtextNode<T : BasicRenderContext> : Node<T>() {

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val start = builder.length
        getChildren()?.forEach { it.render(builder, renderContext) }

        val spans = listOf(
            RelativeSizeSpan(12f / 14f),
            StyleSpan(Typeface.NORMAL),
            ForegroundColorSpan(ColorCompat.getThemedColor(renderContext.context, R.attr.colorTextMuted)),
        )
        // Apply the block style before nested links/mentions, so their colors win.
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or (1 shl Spanned.SPAN_PRIORITY_SHIFT)) }

        if (builder.length == start || builder[builder.length - 1] != '\n') builder.append('\n')
    }
}
