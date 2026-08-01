package com.discord.widgets.guilds.leave

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.appViewModels
import com.discord.databinding.LeaveGuildDialogBinding
import com.discord.i18n.i18nRender
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle

class WidgetLeaveGuildDialog : AppDialog(R.layout.leave_guild_dialog) {

    private val viewModel by appViewModels {
        LeaveGuildDialogViewModel(argumentsOrDefault.getLong(EXTRA_GUILD_ID))
    }
    private var _binding: LeaveGuildDialogBinding? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = LeaveGuildDialogBinding.bind(view).also { _binding = it }
        binding.leaveGuildConfirmBtn.setOnClickListener { viewModel.leaveGuild() }
        binding.leaveGuildCancelBtn.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        dialog?.setCanceledOnTouchOutside(true)
        viewModel.observeViewState().bindToComponentLifecycle(this)
            .appSubscribe(WidgetLeaveGuildDialog::class.java, onNext = ::configureUI)
        viewModel.observeEvents().bindToComponentLifecycle(this)
            .appSubscribe(WidgetLeaveGuildDialog::class.java) { dismiss() }
    }

    private fun configureUI(state: LeaveGuildDialogViewModel.ViewState) {
        val binding = _binding ?: return
        val valid = state as? LeaveGuildDialogViewModel.ViewState.Valid
        if (valid == null) {
            dismiss()
            return
        }
        binding.leaveGuildDialogHeader.text =
            requireContext().i18nRender(R.string.leave_server_title, "name" to valid.guild.name)
        binding.leaveGuildDialogBody.text = requireContext().i18nRender(
            if (valid.guild.isHub()) R.string.leave_hub_body else R.string.leave_server_body_mobile,
            "name" to valid.guild.name,
        )
        binding.leaveGuildConfirmBtn.setText(
            getString(if (valid.guild.isHub()) R.string.leave_hub else R.string.leave_server),
        )
        binding.leaveGuildConfirmBtn.setIsLoading(valid.isLoading)
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"

        fun show(fragmentManager: FragmentManager, guildId: Long) = WidgetLeaveGuildDialog().apply {
            arguments = Bundle().apply { putLong(EXTRA_GUILD_ID, guildId) }
        }.show(fragmentManager, WidgetLeaveGuildDialog::class.java.simpleName)
    }
}
