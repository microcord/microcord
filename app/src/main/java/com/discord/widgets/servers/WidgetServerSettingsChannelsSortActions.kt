package com.discord.widgets.servers

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet

class WidgetServerSettingsChannelsSortActions : AppBottomSheet() {
    var sortTypeSelectedListener: ((Int) -> Unit)? = null

    override fun getContentViewResId() = R.layout.widget_server_settings_channels_sort_actions

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.server_settings_channels_sort_actions_category)
            .setOnClickAndDismissListener { sortTypeSelectedListener?.invoke(4) }
        view.findViewById<View>(R.id.server_settings_channels_sort_actions_voice)
            .setOnClickAndDismissListener { sortTypeSelectedListener?.invoke(2) }
        view.findViewById<View>(R.id.server_settings_channels_sort_actions_text)
            .setOnClickAndDismissListener { sortTypeSelectedListener?.invoke(0) }
    }

    override fun onPause() {
        super.onPause()
        dismiss()
    }

    companion object {
        fun show(fragmentManager: FragmentManager, sortTypeSelectedListener: (Int) -> Unit) {
            WidgetServerSettingsChannelsSortActions().apply {
                this.sortTypeSelectedListener = sortTypeSelectedListener
            }.show(fragmentManager, "javaClass")
        }
    }
}
