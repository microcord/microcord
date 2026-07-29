package com.discord.widgets.servers.community

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.appActivityViewModels

class WidgetConfirmRemoveCommunityDialog : AppDialog(R.layout.widget_confirm_remove_community_dialog) {

    private val viewModel: WidgetServerSettingsCommunityOverviewViewModel by appActivityViewModels {
        WidgetServerSettingsCommunityOverviewViewModel(
            argumentsOrDefault.getLong(INTENT_EXTRA_GUILD_ID, INVALID_ID),
        )
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<View>(R.id.confirm_deleting_community_ok).setOnClickListener {
            dismiss()
            viewModel.disableCommunity()
        }
        view.findViewById<View>(R.id.confirm_deleting_community_cancel).setOnClickListener { dismiss() }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_ID = -1L

        fun show(fragmentManager: FragmentManager, guildId: Long) {
            WidgetConfirmRemoveCommunityDialog().apply {
                arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
            }.show(fragmentManager, WidgetConfirmRemoveCommunityDialog::class.java.simpleName)
        }
    }
}
