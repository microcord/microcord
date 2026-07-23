package com.discord.utilities.spans

import android.text.TextPaint
import android.view.View

class ClickableSpan(
    private val color: Int?,
    private val underline: Boolean,
    val onLongPress: ((View) -> Unit)? = null,
    private val onClickListener: (View) -> Unit,
) : android.text.style.ClickableSpan() {
    override fun onClick(widget: View) = onClickListener(widget)

    override fun updateDrawState(ds: TextPaint) {
        ds.isUnderlineText = underline
        color?.let { ds.color = it }
    }
}
