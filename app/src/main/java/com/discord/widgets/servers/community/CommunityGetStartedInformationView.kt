package com.discord.widgets.servers.community

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.discord.R

class CommunityGetStartedInformationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CardView(context, attrs, defStyleAttr) {
    init {
        LayoutInflater.from(context).inflate(R.layout.view_community_get_started_information, this)
        val description = requireNotNull<TextView>(findViewById(R.id.community_list_adapter_item_description))
        val icon = requireNotNull<ImageView>(findViewById(R.id.community_list_adapter_item_icon))
        val title = requireNotNull<TextView>(findViewById(R.id.community_list_adapter_item_text_name))

        if (attrs != null) {
            context.obtainStyledAttributes(
                attrs,
                intArrayOf(R.attr.csi_description_text, R.attr.csi_icon, R.attr.csi_title_text),
                0,
                0,
            ).run {
                try {
                    icon.setImageDrawable(AppCompatResources.getDrawable(context, getResourceId(1, 0)))
                    title.text = getText(2)
                    description.text = renderDescription(requireNotNull(getText(0)))
                } finally {
                    recycle()
                }
            }
        }
    }

    private fun renderDescription(source: CharSequence): CharSequence {
        val output = SpannableStringBuilder()
        var end = 0
        BOLD_MARKUP.findAll(source).forEach { match ->
            output.append(source, end, match.range.first)
            val start = output.length
            output.append(match.groups[1]?.value ?: match.groups[2]!!.value)
            output.setSpan(StyleSpan(Typeface.BOLD), start, output.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            output.setSpan(
                ForegroundColorSpan(themedColor(R.attr.colorTextLink)),
                start,
                output.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            end = match.range.last + 1
        }
        return output.append(source, end, source.length)
    }

    private fun themedColor(attribute: Int): Int = TypedValue().let { value ->
        context.theme.resolveAttribute(attribute, value, true)
        if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private companion object {
        val BOLD_MARKUP = Regex("\\*\\*(?:\\$\\[([^]]+)]\\([^)]+\\)|(.+?))\\*\\*")
    }
}
