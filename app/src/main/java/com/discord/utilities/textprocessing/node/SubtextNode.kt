package com.discord.utilities.textprocessing.node

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat

/** A current Discord `-# subtext` line. */
class SubtextNode<T : BasicRenderContext> : Node<T>() {
    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val start = builder.length
        getChildren()?.forEach { it.render(builder, renderContext) }
        builder.setSpan(RelativeSizeSpan(0.85f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            ForegroundColorSpan(ColorCompat.getThemedColor(renderContext.context, R.attr.colorTextMuted)),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}
