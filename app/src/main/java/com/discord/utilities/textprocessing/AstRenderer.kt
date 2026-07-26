package com.discord.utilities.textprocessing

import android.text.SpannableStringBuilder
import com.discord.simpleast.core.node.Node
import com.facebook.drawee.span.DraweeSpanStringBuilder

/** Renders a parsed AST into a Drawee-aware builder so emoji image spans can attach to it. */
object AstRenderer {

    @JvmStatic
    fun <T> render(ast: Collection<Node<T>>, renderContext: T): DraweeSpanStringBuilder {
        val builder = DraweeSpanStringBuilder()
        ast.forEach { it.render(builder, renderContext) }
        trimTrailingNewline(builder)
        return builder
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
