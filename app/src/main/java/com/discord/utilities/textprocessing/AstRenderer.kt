package com.discord.utilities.textprocessing

import android.text.SpannableStringBuilder
import com.discord.simpleast.core.node.Node
import com.discord.utilities.textprocessing.spans.VerticalPaddingSpan
import com.facebook.drawee.span.DraweeSpanStringBuilder

/** Renders a parsed AST into a Drawee-aware builder so emoji image spans can attach to it. */
object AstRenderer {

    @JvmStatic
    fun <T> render(ast: Collection<Node<T>>, renderContext: T): DraweeSpanStringBuilder {
        val builder = DraweeSpanStringBuilder()
        ast.forEach { it.render(builder, renderContext) }
        trimTrailingNewline(builder)
        trimEdgePadding(builder)
        return builder
    }

    /**
     * Block spacing (a header's) only separates blocks from each other; at the top or bottom of
     * the message it would push the text off the author line or leave a gap under it.
     */
    private fun trimEdgePadding(builder: SpannableStringBuilder) {
        builder.getSpans(0, builder.length, VerticalPaddingSpan::class.java).forEach { span ->
            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            val top = if (start == 0) 0 else span.paddingTop
            val bottom = if (end == builder.length) 0 else span.paddingBottom
            if (top == span.paddingTop && bottom == span.paddingBottom) return@forEach
            val flags = builder.getSpanFlags(span)
            builder.removeSpan(span)
            builder.setSpan(VerticalPaddingSpan(top, bottom), start, end, flags)
        }
    }

    /**
     * Block nodes always end their content with a newline; the last one would otherwise leave an
     * empty line at the bottom of the message.
     */
    private fun trimTrailingNewline(builder: SpannableStringBuilder) {
        if (builder.isEmpty()) return
        if (builder[builder.length - 1] == '\n') {
            builder.delete(builder.length - 1, builder.length)
        }
    }
}
