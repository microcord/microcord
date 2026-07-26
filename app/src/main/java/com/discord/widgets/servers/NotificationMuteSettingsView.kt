package com.discord.widgets.servers

import android.content.Context
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.discord.R
import com.discord.utilities.time.TimeUtils
import com.discord.views.ThemedColor
import com.discord.widgets.settings.SettingsStrings
import java.text.DateFormat

/**
 * The mute/unmute row shared by the server, channel and category notification screens: the action
 * itself, the "you have muted this until …" status, and an optional explanation underneath.
 *
 * Reference: `com.discord.widgets.servers.NotificationMuteSettingsView`. The caller supplies every
 * string because the wording differs per surface; only the "muted until" line is formatted here,
 * since it is the one that needs the end time substituted and styled.
 */
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
            else -> mutedUntilText(viewState.rawMutedUntilStatusResId, viewState.muteEndTime)
        }
        muteItemStatus.visibility = if (viewState.isMuted) View.VISIBLE else View.GONE
        muteItemDescription.text = viewState.muteDescriptionText
        muteItemDescription.visibility =
            if (viewState.muteDescriptionText != null) View.VISIBLE else View.GONE
    }

    /**
     * `"You have muted this server until $[**{endTime}**](endTimeHook)"` — the hook run is the
     * formatted end time, emphasised in the brand colour.
     */
    private fun mutedUntilText(@StringRes templateResId: Int, muteEndTime: String): CharSequence =
        SettingsStrings.render(
            context,
            templateResId,
            "endTime" to TimeUtils.renderUtcDateTime(
                utcDateTime = muteEndTime,
                context = context,
                outputDateFormat = DateFormat.MEDIUM,
                outputTimeFormat = DateFormat.SHORT,
            ),
            hooks = mapOf(
                HOOK_END_TIME to listOf(
                    ForegroundColorSpan(
                        ThemedColor.resolve(context, R.attr.colorControlBrandForeground),
                    ),
                ),
            ),
        )

    private companion object {
        const val HOOK_END_TIME = "endTimeHook"
    }
}
