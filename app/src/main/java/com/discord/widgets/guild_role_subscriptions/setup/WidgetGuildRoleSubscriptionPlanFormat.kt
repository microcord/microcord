package com.discord.widgets.guild_role_subscriptions.setup

import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.views.RadioManager

/**
 * Step 1 of setup: does a membership unlock the whole server, or only the channels the creator
 * lists?
 *
 * The choice is the plan's, not the tier's, so it is held by the activity-scoped
 * [GuildRoleSubscriptionPlanDetailsViewModel]. It also decides what the later steps look like: a
 * full-server plan hides the channel-benefit editor entirely.
 *
 * [newInstance] can hide the header for hosts that supply their own (the server-settings editor).
 */
class WidgetGuildRoleSubscriptionPlanFormat : AppFragment(R.layout.widget_guild_role_subscription_plan_format) {

    private val viewModel: GuildRoleSubscriptionPlanDetailsViewModel by appActivityViewModels {
        GuildRoleSubscriptionPlanDetailsViewModel()
    }

    private lateinit var radioManager: RadioManager
    private lateinit var entireServerFormat: GuildRoleSubscriptionPlanFormatRadioButton
    private lateinit var someChannelsFormat: GuildRoleSubscriptionPlanFormatRadioButton

    private val hideHeader: Boolean
        get() = arguments?.getBoolean(INTENT_EXTRA_HIDE_HEADER, false) ?: false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        entireServerFormat = requireNotNull(view.findViewById(R.id.guild_role_subscription_plan_entire_server_format))
        someChannelsFormat = requireNotNull(view.findViewById(R.id.guild_role_subscription_plan_some_channels_format))
        radioManager = RadioManager(listOf(entireServerFormat, someChannelsFormat))
        entireServerFormat.setOnClickListener { viewModel.updateFullServerGate(true) }
        someChannelsFormat.setOnClickListener { viewModel.updateFullServerGate(false) }

        val headerVisibility = if (hideHeader) View.GONE else View.VISIBLE
        requireNotNull<TextView>(view.findViewById(R.id.guild_role_subscription_format_title))
            .visibility = headerVisibility
        requireNotNull<TextView>(view.findViewById(R.id.guild_role_subscription_format_subtitle))
            .visibility = headerVisibility
        requireNotNull<View>(view.findViewById(R.id.guild_role_subscription_format_divider))
            .visibility = headerVisibility
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionPlanFormat::class.java) { configureUI(it) }
    }

    private fun configureUI(viewState: GuildRoleSubscriptionPlanDetailsViewModel.ViewState) {
        radioManager.setChecked(
            if (viewState.isFullServerGate) entireServerFormat else someChannelsFormat,
        )
    }

    companion object {
        private const val INTENT_EXTRA_HIDE_HEADER = "INTENT_EXTRA_HIDE_HEADER"

        @JvmOverloads
        fun newInstance(hideHeader: Boolean = false) = WidgetGuildRoleSubscriptionPlanFormat().apply {
            arguments = bundleOf(INTENT_EXTRA_HIDE_HEADER to hideHeader)
        }
    }
}
