package com.discord.widgets.servers.guildboost

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.models.domain.ModelGuildBoostSlot
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.guildboost.GuildBoostConfirmationView
import com.google.android.material.button.MaterialButton

class WidgetGuildBoostTransfer : AppFragment(R.layout.widget_guild_boost_transfer) {
    private val viewModel: GuildBoostTransferInProgressViewModel by appViewModels {
        GuildBoostTransferInProgressViewModel(
            mostRecentIntent.getLongExtra(INTENT_EXTRA_SLOT_ID, -1L),
            mostRecentIntent.getLongExtra(INTENT_EXTRA_SUBSCRIPTION_ID, -1L),
            mostRecentIntent.getLongExtra(INTENT_EXTRA_PREVIOUS_GUILD_ID, -1L),
            mostRecentIntent.getLongExtra(INTENT_EXTRA_TARGET_GUILD_ID, -1L),
        )
    }

    private lateinit var dimmer: DimmerView
    private lateinit var error: TextView
    private lateinit var previousGuild: GuildBoostConfirmationView
    private lateinit var targetGuild: GuildBoostConfirmationView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled(false)
        dimmer = view.findViewById(R.id.dimmer_view)
        error = view.findViewById(R.id.guild_boost_transfer_error)
        previousGuild = view.findViewById(R.id.guild_boost_transfer_previous_guild)
        targetGuild = view.findViewById(R.id.guild_boost_transfer_target_guild)
        view.findViewById<TextView>(R.id.guild_boost_transfer_confirmation_blurb).text =
            context?.i18nFormat(
                R.string.premium_guild_subscribe_confirm_transfer_blurb,
                "slotCount" to requireContext().i18nPlural(
                    R.plurals.premium_guild_subscribe_confirm_transfer_blurb_slotCount,
                    1,
                ),
                "guildCount" to requireContext().i18nPlural(
                    R.plurals.premium_guild_subscribe_confirm_transfer_blurb_guildCount,
                    1,
                ),
            )
        view.findViewById<TextView>(R.id.guild_boost_transfer_previous_guild_header).text =
            context?.i18nFormat(
                R.string.premium_guild_subscribe_confirm_transfer_from_guild,
                "guildCount" to requireContext().i18nPlural(
                    R.plurals.premium_guild_subscribe_confirm_transfer_from_guild_guildCount,
                    1,
                ),
            )
        view.findViewById<TextView>(R.id.guild_boost_transfer_target_guild_header).text =
            context?.i18nFormat(
                R.string.premium_guild_subscribe_confirm_transfer_to_guild,
                "slotCount" to requireContext().i18nPlural(
                    R.plurals.premium_guild_subscribe_confirm_transfer_to_guild_slotCount,
                    1,
                ),
            )
        view.findViewById<MaterialButton>(R.id.guild_boost_transfer_select).apply {
            text = context.i18nFormat(
                R.string.premium_guild_subscribe_transfer_confirm_confirmation,
                "slotCount" to context.i18nPlural(
                    R.plurals.premium_guild_subscribe_transfer_confirm_confirmation_slotCount,
                    1,
                ),
            )
            setOnClickListener { viewModel.transferGuildBoost() }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().ui(this)
            .appSubscribe(javaClass, onNext = ::configureUI)
    }

    private fun configureUI(state: GuildBoostTransferInProgressViewModel.ViewState) {
        when (state) {
            GuildBoostTransferInProgressViewModel.ViewState.ErrorLoading -> activity?.finish()
            GuildBoostTransferInProgressViewModel.ViewState.Loading -> {
                dimmer.setDimmed(true)
                error.visibility = View.INVISIBLE
            }
            GuildBoostTransferInProgressViewModel.ViewState.ErrorTransfer -> {
                dimmer.setDimmed(false)
                error.visibility = View.VISIBLE
            }
            is GuildBoostTransferInProgressViewModel.ViewState.PreTransfer -> {
                dimmer.setDimmed(state.isTransferInProgress)
                error.visibility = View.INVISIBLE
                previousGuild.configure(state.previousGuild, -1)
                targetGuild.configure(state.targetGuild, 1)
            }
            is GuildBoostTransferInProgressViewModel.ViewState.PostTransfer -> {
                GuildBoostActivatedDialog.show(
                    parentFragmentManager,
                    requireContext(),
                    state.targetGuild.name,
                    state.targetGuildSubscriptionCount + 1,
                    true,
                ) { activity?.finish() }
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_PREVIOUS_GUILD_ID = "PREVIOUS_GUILD_ID"
        private const val INTENT_EXTRA_SLOT_ID = "SLOT_ID"
        private const val INTENT_EXTRA_SUBSCRIPTION_ID = "SUBSCRIPTION_ID"
        private const val INTENT_EXTRA_TARGET_GUILD_ID = "TARGET_GUILD_ID"

        @JvmOverloads
        fun create(
            context: Context,
            previousGuildId: Long,
            targetGuildId: Long,
            slot: ModelGuildBoostSlot? = null,
        ) {
            AppScreenRouter.launch(
                context,
                WidgetGuildBoostTransfer::class.java,
                Intent()
                    .putExtra(INTENT_EXTRA_PREVIOUS_GUILD_ID, previousGuildId)
                    .putExtra(INTENT_EXTRA_TARGET_GUILD_ID, targetGuildId)
                    .putExtra(INTENT_EXTRA_SLOT_ID, slot?.id)
                    .putExtra(INTENT_EXTRA_SUBSCRIPTION_ID, slot?.premiumGuildSubscription?.id),
            )
        }
    }
}
