package com.discord.widgets.forums

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.discord.R
import com.discord.stores.StoreStream
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.renderReactionEmoji
import com.discord.widgets.channels.list.ChannelListNavigation

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
        val horizontal = dpToPixels(8)
        val vertical = dpToPixels(2)
        setPadding(horizontal, vertical, horizontal, vertical)
        val value = TypedValue()
        if (context.theme.resolveAttribute(R.attr.theme_forum_post_tag_background, value, true)) {
            background = AppCompatResources.getDrawable(context, value.resourceId)
        }
    }

    fun configureTag(tag: PostTagData) {
        tagEmoji.visibility = VISIBLE
        renderReactionEmoji(tagEmoji, tag.messageReactionEmoji, true, dpToPixels(12))
        tagName.text = tag.tagName
        // Developer mode adds "Copy Tag ID" wherever a tag is drawn.
        val tagId = tag.tagId
        if (tagId == null || !StoreStream.getUserSettings().getIsDeveloperMode()) {
            setOnLongClickListener(null)
        } else {
            setOnLongClickListener {
                ChannelListNavigation.copyToClipboard(context, tagId.toString())
                true
            }
        }
    }

    /** The "+N" chip standing in for the tags that did not fit. */
    fun configureOverflow(count: Int) {
        tagEmoji.visibility = GONE
        tagName.text = context.getString(R.string.forum_reactions_overflow).replace("{count}", count.toString())
    }

    /** The trailing chip that opens tag creation in channel settings. */
    fun configureAdd() {
        tagEmoji.visibility = GONE
        tagName.text = "+"
        contentDescription = context.getString(R.string.forum_tag_create)
    }

}
