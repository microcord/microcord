package com.discord.widgets.guildscheduledevent

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.models.domain.ModelInvite
import com.discord.models.invite.InviteUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.discord.widgets.guilds.invite.copyLinkClick
import com.discord.widgets.guilds.invite.getInviteLinkText
import com.discord.widgets.guilds.invite.shareLinkClick
import com.google.android.material.button.MaterialButton

class WidgetGuildScheduledEventCreateSuccess : AppFragment(R.layout.widget_guild_scheduled_event_create_success) {
    private val channelId: Long?
        get() = requireArguments().takeIf { it.containsKey(EXTRA_CHANNEL_ID) }?.getLong(EXTRA_CHANNEL_ID)
    private val guildId: Long get() = requireArguments().getLong(EXTRA_GUILD_ID)
    private val eventId: Long get() = requireArguments().getLong(EXTRA_EVENT_ID)

    private val viewModel: WidgetGuildScheduledEventCreateSuccessViewModel by appViewModels {
        WidgetGuildScheduledEventCreateSuccessViewModel(channelId, guildId, eventId, resources = resources)
    }

    private lateinit var inviteLink: TextView
    private lateinit var neverExpire: CheckedSetting
    private lateinit var share: MaterialButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        inviteLink = view.findViewById(R.id.guild_invite_link)
        neverExpire = view.findViewById(R.id.guild_invite_never_expire)
        share = view.findViewById(R.id.guild_invite_share_btn)
        view.findViewById<View>(R.id.close_button).setOnClickListener { activity?.finish() }
        viewModel.generateInviteLink(channelId)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ofType(WidgetGuildScheduledEventCreateSuccessViewModel.ViewState.Loaded::class.java)
            .ui(this)
            .appSubscribe(javaClass, onNext = ::configureUi)
    }

    private fun configureUi(state: WidgetGuildScheduledEventCreateSuccessViewModel.ViewState.Loaded) {
        val model = state.widgetInviteModel
        val invite = model.invite
        inviteLink.text = invite?.toLink(resources, InviteUtils.HOST_INVITE) ?: InviteUtils.HOST_INVITE
        inviteLink.setOnClickListener { copyLinkClick(requireContext(), invite, mostRecentIntent) }

        val settings = model.settings
        neverExpire.visibility = if (state.showInviteSettings) View.VISIBLE else View.GONE
        neverExpire.setChecked(settings?.maxAge == ModelInvite.Settings.NEVER)
        neverExpire.setSubtext(getInviteLinkText(requireContext()))
        neverExpire.setContainerClickListener {
            val current = model.settings ?: return@setContainerClickListener
            neverExpire.toggle()
            viewModel.updateInviteSettings(
                current.mergeMaxAge(
                    if (neverExpire.isChecked) ModelInvite.Settings.NEVER else ModelInvite.Settings.SEVEN_DAYS,
                ),
            )
            model.targetChannel?.id?.let(viewModel::generateInviteLink)
        }
        share.isEnabled = invite != null
        share.setOnClickListener { invite?.let { shareLinkClick(context, it) } }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_EVENT_ID = "com.discord.intent.extra.EXTRA_GUILD_SCHEDULED_EVENT_ID"

        fun launch(context: Context, channelId: Long?, guildId: Long, eventId: Long) {
            val extras = Intent()
                .putExtra(EXTRA_GUILD_ID, guildId)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .apply { channelId?.let { putExtra(EXTRA_CHANNEL_ID, it) } }
            AppScreenRouter.launch(context, WidgetGuildScheduledEventCreateSuccess::class.java, extras)
        }
    }
}
