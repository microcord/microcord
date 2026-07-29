package com.discord.widgets.guildscheduledevent

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.views.ScreenTitleView
import com.google.android.material.button.MaterialButton

class WidgetEndGuildScheduledEventBottomSheet : AppBottomSheet() {
    private val guildId: Long get() = requireArguments().getLong(EXTRA_GUILD_ID)
    private val eventId: Long get() = requireArguments().getLong(EXTRA_EVENT_ID)
    private val requestKey: String get() = requireNotNull(requireArguments().getString(EXTRA_REQUEST_KEY))

    override fun getContentViewResId(): Int = R.layout.widget_end_stage_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<ScreenTitleView>(R.id.title).apply {
            setTitle(getString(R.string.guild_event_end_prompt_title))
            setSubtitle(null)
        }
        view.findViewById<MaterialButton>(R.id.cancel).apply {
            setText(R.string.guild_event_end_prompt_cancel)
            setOnClickListener { finish() }
        }
        view.findViewById<MaterialButton>(R.id.confirm).apply {
            setText(R.string.guild_event_end_prompt_confirm)
            setOnClickListener {
                GuildScheduledEventAPI.endEvent(guildId, eventId)
                    .restSubscribeOn()
                    .appSubscribe(WidgetEndGuildScheduledEventBottomSheet::class.java) { }
                finish()
            }
        }
    }

    private fun finish() {
        parentFragmentManager.setFragmentResult(requestKey, Bundle.EMPTY)
        dismiss()
    }

    companion object {
        private const val EXTRA_REQUEST_KEY = "INTENT_EXTRA_REQUEST_KEY"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_EVENT_ID = "com.discord.intent.extra.EXTRA_GUILD_SCHEDULED_EVENT_ID"

        fun registerForResult(fragment: Fragment, requestKey: String, onActionTaken: () -> Unit) {
            fragment.parentFragmentManager.setFragmentResultListener(requestKey, fragment) { key, _ ->
                if (key == requestKey) onActionTaken()
            }
        }

        fun show(fragmentManager: FragmentManager, requestKey: String, guildId: Long, eventId: Long) {
            WidgetEndGuildScheduledEventBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_REQUEST_KEY, requestKey)
                    putLong(EXTRA_GUILD_ID, guildId)
                    putLong(EXTRA_EVENT_ID, eventId)
                }
            }.show(fragmentManager, WidgetEndGuildScheduledEventBottomSheet::class.java.simpleName)
        }
    }
}
