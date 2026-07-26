package com.discord.utilities.search.query.node.content

import android.content.Context
import android.text.SpannableStringBuilder
import com.discord.utilities.search.query.node.QueryNode

/** Free text: everything the filter rules did not claim. */
data class ContentNode(val content: CharSequence) : QueryNode() {

    /** Joins two adjacent runs of free text back into one, separated by a single space. */
    constructor(first: ContentNode, second: ContentNode) : this("${first.content} ${second.content}")

    override val text: String get() = content.toString()

    override fun render(builder: SpannableStringBuilder, renderContext: Context) {
        builder.append(content).append(' ')
    }
}
