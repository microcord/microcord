package com.discord.widgets.channels.threads

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadDraft
import com.google.android.material.radiobutton.MaterialRadioButton
import rx.Subscription

class WidgetThreadDraftArchiveSheet : AppBottomSheet() {
    private var subscription: Subscription? = null
    private var currentState = StoreThreadDraft.ThreadDraftState()

    override fun getContentViewResId() = R.layout.widget_thread_archive_actions_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindOption(view, R.id.option_one_hour, R.id.option_one_hour_radio, 60)
        bindOption(view, R.id.option_twenty_four_hours, R.id.option_twenty_four_hours_radio, 1_440)
        bindOption(view, R.id.option_three_days, R.id.option_three_days_radio, 4_320)
        bindOption(view, R.id.option_seven_days, R.id.option_seven_days_radio, 10_080)
    }

    override fun onStart() {
        super.onStart()
        subscription = StoreStream.getThreadDraft().observeDraftState().subscribe(::render)
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        dismiss()
    }

    private fun bindOption(view: View, rowId: Int, radioId: Int, minutes: Int) {
        view.findViewById<View>(rowId).setOnClickListener {
            StoreStream.getThreadDraft().setDraftState(currentState.copy(autoArchiveDuration = minutes))
            dismiss()
        }
        view.findViewById<MaterialRadioButton>(radioId).tag = minutes
    }

    private fun render(state: StoreThreadDraft.ThreadDraftState) {
        currentState = state
        val selected = state.autoArchiveDuration ?: 1_440
        view?.findViewById<MaterialRadioButton>(R.id.option_one_hour_radio)?.isChecked = selected == 60
        view?.findViewById<MaterialRadioButton>(R.id.option_twenty_four_hours_radio)?.isChecked = selected == 1_440
        view?.findViewById<MaterialRadioButton>(R.id.option_three_days_radio)?.isChecked = selected == 4_320
        view?.findViewById<MaterialRadioButton>(R.id.option_seven_days_radio)?.isChecked = selected == 10_080
    }

    companion object {
        fun show(fragmentManager: FragmentManager, guildId: Long) {
            WidgetThreadDraftArchiveSheet().apply {
                arguments = Bundle().apply { putLong("INTENT_EXTRA_GUILD_ID", guildId) }
            }.show(fragmentManager, WidgetThreadDraftArchiveSheet::class.java.name)
        }
    }
}
