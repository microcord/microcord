package com.discord.widgets.servers.member_verification

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.appViewModels
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.settings.SettingsStrings
import com.google.android.material.button.MaterialButton

class MemberVerificationSuccessDialog : AppDialog(R.layout.widget_member_verification_success) {

    private val guildId: Long get() = argumentsOrDefault.getLong(INTENT_EXTRA_GUILD_ID)
    private val viewModel: MemberVerificationSuccessViewModel by appViewModels {
        MemberVerificationSuccessViewModel(guildId)
    }

    private lateinit var guildName: TextView
    private lateinit var confirm: MaterialButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        guildName = view.findViewById(R.id.member_verification_guild_name)
        confirm = view.findViewById(R.id.member_verification_confirm)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().ui(this)
            .appSubscribe(MemberVerificationSuccessDialog::class.java, context) { state ->
                when (state) {
                    MemberVerificationSuccessViewModel.ViewState.Acked -> dismiss()
                    is MemberVerificationSuccessViewModel.ViewState.Loaded -> configure(state)
                }
            }
    }

    private fun configure(state: MemberVerificationSuccessViewModel.ViewState.Loaded) {
        guildName.text = SettingsStrings.render(
            requireContext(),
            R.string.lurker_mode_popout_success_header,
            "guildName" to state.guildName.orEmpty(),
        )
        confirm.setOnClickListener {
            ChannelSelector.getInstance().selectChannel(viewModel.guildId, 0L)
            viewModel.ackGuildJoinRequest()
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"

        fun enqueue(guildId: Long) {
            val notices = StoreStream.getNotices()
            val name = "GUILD_JOIN_REQUEST_SUCCESS_$guildId"
            notices.requestToShow(
                StoreNotices.Notice(name = name, validScreens = listOf(WidgetHome::class)) { activity ->
                    MemberVerificationSuccessDialog().apply {
                        arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
                    }.show(activity.supportFragmentManager, MemberVerificationSuccessDialog::class.java.name)
                    notices.markSeen(name)
                    true
                },
            )
        }

    }
}
