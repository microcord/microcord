package com.discord.widgets.channels.permissions

import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import com.discord.R
import com.discord.app.AppFragment
import com.discord.utilities.rx.ui

class WidgetStageChannelModeratorPermissions : AppFragment(R.layout.widget_stage_channel_moderator_permissions) {
    private val channelId get() = arguments?.getLong(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID)
        ?: parentFragment?.arguments?.getLong(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID)
        ?: mostRecentIntent.getLongExtra(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID, -1L)
    private val viewModel by viewModels<WidgetStageChannelModeratorPermissionsViewModel> {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                WidgetStageChannelModeratorPermissionsViewModel(channelId) as T
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe { state ->
            if (state !is WidgetStageChannelModeratorPermissionsViewModel.ViewState.Valid) return@subscribe
            requireView().findViewById<AddPermissionOwnerView>(R.id.add_permission_owner_button).apply {
                alpha = if (state.canEditModerators) 1f else 0.3f
                setOnClickListener {
                    if (state.canEditModerators) WidgetChannelSettingsAddMemberSheet.show(parentFragmentManager, channelId)
                    else Toast.makeText(context, R.string.channel_permissions_cannot_edit_moderators, Toast.LENGTH_SHORT).show()
                }
            }
            requireView().findViewById<PermissionOwnerListView>(R.id.roles_permission_owners_list)
                .setData(state.roleItems, ::confirmRemove)
            requireView().findViewById<PermissionOwnerListView>(R.id.members_permission_owners_list)
                .setData(state.memberItems, ::confirmRemove)
        }
    }

    private fun confirmRemove(owner: PermissionOwner) =
        ConfirmRemovePermissionDialog.show(owner, channelId, parentFragmentManager)
}
