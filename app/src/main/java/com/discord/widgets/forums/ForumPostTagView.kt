package com.discord.widgets.forums

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.discord.R
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.renderReactionEmoji

class ForumPostTagView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val tagEmoji: SimpleDraweeSpanTextView
    private val tagName: TextView

    init {
        inflate(context, R.layout.forum_post_tag_view, this)
        tagEmoji = requireNotNull(findViewById(R.id.tag_emoji))
        tagName = requireNotNull(findViewById(R.id.tag_name))
        val horizontal = dp(8)
        val vertical = dp(2)
        setPadding(horizontal, vertical, horizontal, vertical)
        val value = TypedValue()
        if (context.theme.resolveAttribute(R.attr.theme_forum_post_tag_background, value, true)) {
            background = AppCompatResources.getDrawable(context, value.resourceId)
        }
    }

    fun configureTag(tag: PostTagData) {
        renderReactionEmoji(tagEmoji, tag.messageReactionEmoji, true, dp(12))
        tagName.text = tag.tagName
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
