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
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.guildboost.GuildBoostConfirmationView
import com.google.android.material.button.MaterialButton

class WidgetGuildBoostConfirmation : AppFragment(R.layout.widget_server_boost_confirmation) {
    private val guildId by lazy { mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, -1L) }
    private val slotId by lazy { mostRecentIntent.getLongExtra(INTENT_EXTRA_SLOT_ID, -1L) }
    private val viewModel: GuildBoostInProgressViewModel by appViewModels {
        GuildBoostInProgressViewModel(guildId)
    }

    private lateinit var confirmationView: GuildBoostConfirmationView
    private lateinit var dimmer: DimmerView
    private lateinit var error: TextView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled(false)
        confirmationView = view.findViewById(R.id.boost_confirmation_confirmation_view)
        dimmer = view.findViewById(R.id.dimmer_view)
        error = view.findViewById(R.id.boost_confirmation_error)
        view.findViewById<MaterialButton>(R.id.boost_confirmation_select).apply {
            text = context.i18nFormat(
                R.string.premium_guild_subscribe_confirm_confirmation,
                "slotCount" to context.i18nPlural(
                    R.plurals.premium_guild_subscribe_confirm_confirmation_slotCount,
                    1,
                ),
            )
            setOnClickListener { viewModel.subscribeToGuildBoost(guildId, slotId) }
        }
        view.findViewById<TextView>(R.id.boost_confirmation_cooldown_warning).text =
            view.context.i18nFormat(
                R.string.premium_guild_subscribe_confirm_cooldown_warning,
                "slotCount" to view.context.i18nPlural(
                    R.plurals.premium_guild_subscribe_confirm_cooldown_warning_slotCount,
                    1,
                ),
                "days" to view.context.i18nPlural(
                    R.plurals.premium_guild_subscribe_confirm_cooldown_warning_days,
                    7,
                    "#" to 7,
                ),
            )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (guildId == 0L || guildId == -1L || slotId == -1L) {
            activity?.finish()
            return
        }
        viewModel.observeViewState().distinctUntilChanged().ui(this)
            .appSubscribe(javaClass, onNext = ::configureUI)
    }

    private fun configureUI(state: GuildBoostInProgressViewModel.ViewState) {
        val loaded = state as? GuildBoostInProgressViewModel.ViewState.Loaded ?: return
        val guild = loaded.guild ?: return activity?.finish() ?: Unit
        setActionBarTitle(R.string.premium_guild_subscription_title)
        setActionBarSubtitle(guild.name)
        confirmationView.configure(guild, 1)
        when (loaded.guildBoostState) {
            GuildBoostInProgressViewModel.GuildBoostState.CALL_IN_PROGRESS -> {
                dimmer.setDimmed(true)
                error.visibility = View.INVISIBLE
            }
            GuildBoostInProgressViewModel.GuildBoostState.ERROR -> {
                dimmer.setDimmed(false)
                error.visibility = View.VISIBLE
            }
            GuildBoostInProgressViewModel.GuildBoostState.COMPLETED -> if (loaded.canShowConfirmationDialog) {
                GuildBoostActivatedDialog.show(
                    parentFragmentManager,
                    requireContext(),
                    guild.name,
                    loaded.subscriptionCount + 1,
                    false,
                ) { activity?.finish() }
            }
            GuildBoostInProgressViewModel.GuildBoostState.NOT_IN_PROGRESS -> {
                dimmer.setDimmed(false)
                error.visibility = View.INVISIBLE
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "GUILD_ID"
        private const val INTENT_EXTRA_SLOT_ID = "SLOT_ID"

        fun create(context: Context, guildId: Long, slotId: Long) {
            AppScreenRouter.launch(
                context,
                WidgetGuildBoostConfirmation::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId).putExtra(INTENT_EXTRA_SLOT_ID, slotId),
            )
        }
    }
}
