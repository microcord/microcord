package com.discord.widgets.guilds.invite

import android.content.Context
import android.content.Intent
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.stores.StoreInviteSettings
import com.discord.utilities.guildscheduledevent.getCreatorUserGuildMember
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.guildscheduledevent.GuildScheduledEventItemView
import com.discord.widgets.guildscheduledevent.toModel
import com.google.android.material.button.MaterialButton

class WidgetGuildInvite : AppFragment(R.layout.widget_guild_invite_page) {

    private val inviteCode: StoreInviteSettings.InviteCode?
        get() = arguments?.getParcelable(EXTRA_CODE) ?: mostRecentIntent.getParcelableExtra(EXTRA_CODE)
    private val viewModel by appViewModels { GuildInviteViewModel(inviteCode) }
    private lateinit var info: WidgetInviteInfo
    private lateinit var eventInfo: GuildScheduledEventItemView
    private lateinit var cancel: MaterialButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        info = view.findViewById(R.id.guild_invite_info)
        eventInfo = view.findViewById(R.id.guild_invite_event_info)
        cancel = view.findViewById(R.id.guild_invite_cancel)
        cancel.setOnClickListener { appActivity?.onBackPressedDispatcher?.onBackPressed() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.fetchInviteIfNotLoaded()
        viewModel.observeViewState().bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildInvite::class.java, onNext = ::configureUI)
    }

    fun configureUI(state: GuildInviteViewModel.ViewState) {
        when (state) {
            GuildInviteViewModel.ViewState.Invalid -> {
                cancel.visibility = View.VISIBLE
                info.configureUIFailure()
                consumeInviteCode()
            }
            is GuildInviteViewModel.ViewState.Loaded -> {
                consumeInviteCode()
                cancel.visibility = View.GONE
                info.configureUI(state.invite) {
                    InviteJoinHelper.joinViaInvite(
                        state.invite,
                        WidgetGuildInvite::class.java,
                        this,
                        "Accept Invite Page",
                        onInvitePostError = { info.configureUIFailure() },
                    )
                }
                val event = state.invite.getGuildScheduledEvent()?.toModel()
                eventInfo.visibility = if (event == null) View.GONE else View.VISIBLE
                event?.let { eventInfo.configureAsPreview(it, state.invite.channel, it.getCreatorUserGuildMember()) }
            }
            GuildInviteViewModel.ViewState.Loading,
            GuildInviteViewModel.ViewState.Uninitialized,
            -> Unit
        }
    }

    private fun consumeInviteCode() {
        InviteSettingsSource.store.clearInviteCode()
        viewModel.inviteResolved = true
    }

    companion object {
        private const val EXTRA_CODE = "EXTRA_CODE"

        fun launch(context: Context, inviteCode: StoreInviteSettings.InviteCode) = AppScreenRouter.launch(
            context,
            WidgetGuildInvite::class.java,
            Intent().putExtra(EXTRA_CODE, inviteCode),
        )
    }
}
