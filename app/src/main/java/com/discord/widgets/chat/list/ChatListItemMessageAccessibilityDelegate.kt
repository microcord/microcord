package com.discord.widgets.chat.list

import android.text.SpannableStringBuilder
import android.view.View
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.discord.R
import com.discord.utilities.textprocessing.spans.SpoilerSpan

class ChatListItemMessageAccessibilityDelegate(
    private val textView: TextView,
    private val nameView: TextView?,
    private val tagView: TextView?,
    private val timestampView: TextView?,
) : AccessibilityDelegateCompat() {

    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
        super.onInitializeAccessibilityNodeInfo(host, info)

        val description = SpannableStringBuilder(textView.text)
        description.getSpans(0, description.length, SpoilerSpan::class.java).forEach { span ->
            if (!span.isRevealed) {
                description.replace(
                    description.getSpanStart(span),
                    description.getSpanEnd(span),
                    host.context.getString(R.string.spoiler_hidden_a11y_label),
                )
            }
        }

        val header = listOfNotNull(
            nameView?.text,
            tagView?.text?.takeIf { tagView.visibility == View.VISIBLE },
            timestampView?.text,
        )
        if (header.isNotEmpty()) description.insert(0, header.joinToString(", ") + "\n")

        nameView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        tagView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        timestampView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        textView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        info.text = description
        info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        info.movementGranularities = MOVEMENT_GRANULARITIES_ALL
    }

    private companion object {
        const val MOVEMENT_GRANULARITIES_ALL = 15
    }
}
