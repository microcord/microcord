package com.discord.widgets.servers

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.discord.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NotificationMuteSettingsView(context: Context, attrs: AttributeSet) :
    LinearLayout(context, attrs) {
    data class ViewState(
        val isMuted: Boolean,
        val muteEndTime: String?,
        val rawMuteText: CharSequence,
        val rawUnmuteText: CharSequence?,
        val rawMutedStatusText: CharSequence,
        @param:StringRes val rawMutedUntilStatusResId: Int,
        val muteDescriptionText: CharSequence? = null,
    )

    private val muteItem: TextView
    private val muteItemStatus: TextView
    private val muteItemDescription: TextView

    init {
        val content = LayoutInflater.from(context)
            .inflate(R.layout.notification_mute_settings_view, this, false)
        addView(content)
        muteItem = requireNotNull(content.findViewById(R.id.notification_mute_settings_mute_item))
        muteItemStatus = requireNotNull(
            content.findViewById(R.id.notification_mute_settings_mute_item_status),
        )
        muteItemDescription = requireNotNull(
            content.findViewById(R.id.notification_mute_settings_mute_item_description),
        )
    }

    fun updateView(viewState: ViewState, onMute: () -> Unit, onUnmute: () -> Unit) {
        muteItem.text = if (viewState.isMuted) viewState.rawUnmuteText else viewState.rawMuteText
        muteItem.setOnClickListener { if (viewState.isMuted) onUnmute() else onMute() }

        muteItemStatus.text = when {
            !viewState.isMuted -> ""
            viewState.muteEndTime == null -> viewState.rawMutedStatusText
            else -> mutedUntilText(
                context.getString(viewState.rawMutedUntilStatusResId),
                renderUtcDateTime(viewState.muteEndTime),
            )
        }
        muteItemStatus.visibility = if (viewState.isMuted) View.VISIBLE else View.GONE
        muteItemDescription.text = viewState.muteDescriptionText
        muteItemDescription.visibility =
            if (viewState.muteDescriptionText != null) View.VISIBLE else View.GONE
    }

    private fun renderUtcDateTime(value: String): String {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(parser.parse(value) ?: java.util.Date())
    }

    private fun mutedUntilText(template: String, endTime: String): CharSequence {
        val text = template
            .replace("{endTime}", endTime)
            .replace("$[**", "")
            .replace("**](endTimeHook)", "")
        val start = text.indexOf(endTime)
        if (start < 0) return text

        val color = TypedValue().let { value ->
            context.theme.resolveAttribute(R.attr.colorTextLink, value, true)
            if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
        }
        return SpannableStringBuilder(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), start, start + endTime.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(color),
                start,
                start + endTime.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}
