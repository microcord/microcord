package com.discord.utilities.search.query.node.filter

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode

/**
 * A filter keyword — the localised `from`, `mentions`, `has` or `in` — rendered as a bold chip with
 * its trailing colon.
 *
 * [text] is the *localised* keyword, not the enum name, because it round-trips through the input
 * field and has to parse back to the same node.
 *
 * Deviation: the reference styles the chip with `FontUtils.getThemedFont(context, font_display_bold)`
 * wrapped in `TypefaceSpanCompat`. `TypefaceSpanCompat` now exists but `FontUtils` does not, so a
 * plain bold `StyleSpan` stands in — same weight, theme font not applied.
 */
data class FilterNode(val filterType: FilterType, override val text: CharSequence) : QueryNode() {

    override fun render(builder: SpannableStringBuilder, renderContext: Context) {
        val start = builder.length
        builder.append("$text:")
        // EXCLUSIVE_EXCLUSIVE (the reference's flag 33): text typed straight after the chip must not
        // inherit its weight, otherwise the whole rest of the query renders bold.
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}
