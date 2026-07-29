package com.discord.widgets.channels.permissions

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import com.discord.R
import com.discord.app.AppDialog
import com.discord.utilities.rx.ui
import com.discord.views.LoadingButton
import com.discord.widgets.settings.SettingsStrings

class ConfirmRemovePermissionDialog : AppDialog(R.layout.confirm_remove_permission_dialog) {
    private val owner get() = requireArguments().getSerializable(ARG_OWNER) as PermissionOwner
    private val channelId get() = requireArguments().getLong(ARG_CHANNEL_ID)
    private val viewModel by viewModels<ConfirmRemovePermissionDialogViewModel> {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ConfirmRemovePermissionDialogViewModel(owner, channelId) as T
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val name = when (val value = owner) {
            is PermissionOwner.Role -> value.role.name
            is PermissionOwner.Member -> value.nickname ?: value.user.username
        }
        view.findViewById<TextView>(R.id.body).text = SettingsStrings.render(
            requireContext(), R.string.settings_permissions_delete_body, "name" to name,
        )
        view.findViewById<View>(R.id.cancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.confirm).setOnClickListener { viewModel.submit() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        dialog?.setCanceledOnTouchOutside(true)
        viewModel.observeViewState().ui(this).subscribe { state ->
            when (state) {
                ConfirmRemovePermissionDialogViewModel.ViewState.Complete -> dismiss()
                is ConfirmRemovePermissionDialogViewModel.ViewState.Default ->
                    view?.findViewById<LoadingButton>(R.id.confirm)?.setIsLoading(state.isSubmitting)
            }
        }
    }

    companion object {
        private const val ARG_OWNER = "ARG_PERMISSION_OWNER"
        private const val ARG_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        fun show(owner: PermissionOwner, channelId: Long, fragmentManager: FragmentManager) =
            ConfirmRemovePermissionDialog().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_OWNER, owner)
                    putLong(ARG_CHANNEL_ID, channelId)
                }
            }.show(fragmentManager, ConfirmRemovePermissionDialog::class.java.simpleName)
    }
}
