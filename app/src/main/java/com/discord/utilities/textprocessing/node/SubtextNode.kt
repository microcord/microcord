package com.discord.utilities.textprocessing.node

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat

/**
 * A `-# subtext` line.
 *
 * The reference draws it at body size in the normal weight against the body's medium, so the
 * muted colour and the lighter stroke are what set it apart rather than a smaller size.
 */
class SubtextNode<T : BasicRenderContext> : Node<T>() {

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val start = builder.length
        getChildren()?.forEach { it.render(builder, renderContext) }

        val spans = listOf(
            StyleSpan(Typeface.NORMAL),
            ForegroundColorSpan(ColorCompat.getThemedColor(renderContext.context, R.attr.colorTextMuted)),
        )
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

        if (builder.length == start || builder[builder.length - 1] != '\n') builder.append('\n')
    }
}
