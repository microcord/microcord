package com.discord.utilities.textprocessing.node

import android.content.Context
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.discord.R
import com.discord.simpleast.core.node.StyleNode
import com.discord.simpleast.core.node.TextNode
import com.discord.utilities.textprocessing.themedColor

/** Trailing ` (edited)` marker appended to the body of an edited message. */
class EditedMessageNode<RC>(context: Context) : StyleNode<RC, CharacterStyle>(
    listOf(
        RelativeSizeSpan(EDITED_TEXT_SCALE),
        ForegroundColorSpan(themedColor(context, R.attr.colorTextMuted)),
    ),
) {
    init {
        addChild(TextNode(getEditedString(context)))
    }

    companion object {
        private const val EDITED_TEXT_SCALE = 0.75f

        fun getEditedString(context: Context): String = " (${context.getString(R.string.message_edited)})"
    }
}
