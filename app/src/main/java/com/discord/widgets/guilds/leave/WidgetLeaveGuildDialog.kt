package com.discord.widgets.guilds.leave

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.appViewModels
import com.discord.i18n.i18nRender
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.views.LoadingButton

class WidgetLeaveGuildDialog : AppDialog(R.layout.leave_guild_dialog) {

    private val viewModel by appViewModels {
        LeaveGuildDialogViewModel(argumentsOrDefault.getLong(EXTRA_GUILD_ID))
    }
    private lateinit var header: TextView
    private lateinit var body: TextView
    private lateinit var confirm: LoadingButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        header = view.findViewById(R.id.leave_guild_dialog_header)
        body = view.findViewById(R.id.leave_guild_dialog_body)
        confirm = view.findViewById(R.id.leave_guild_confirm_btn)
        confirm.setOnClickListener { viewModel.leaveGuild() }
        view.findViewById<View>(R.id.leave_guild_cancel_btn).setOnClickListener { dismiss() }
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
        val valid = state as? LeaveGuildDialogViewModel.ViewState.Valid
        if (valid == null) {
            dismiss()
            return
        }
        header.text = requireContext().i18nRender(R.string.leave_server_title, "name" to valid.guild.name)
        body.text = requireContext().i18nRender(
            if (valid.guild.isHub()) R.string.leave_hub_body else R.string.leave_server_body_mobile,
            "name" to valid.guild.name,
        )
        confirm.setText(getString(if (valid.guild.isHub()) R.string.leave_hub else R.string.leave_server))
        confirm.setIsLoading(valid.isLoading)
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"

        fun show(fragmentManager: FragmentManager, guildId: Long) = WidgetLeaveGuildDialog().apply {
            arguments = Bundle().apply { putLong(EXTRA_GUILD_ID, guildId) }
        }.show(fragmentManager, WidgetLeaveGuildDialog::class.java.simpleName)
    }
}
