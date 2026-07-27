package com.discord.widgets.guild_role_subscriptions.entrypoint

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.i18n.Hook
import com.discord.i18n.I18nHooks
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.guild_role_subscriptions.entrypoint.WidgetGuildRoleSubscriptionEntryPointViewModel.ViewState

/**
 * Explains that a server's memberships cannot be bought inside the Android app.
 *
 * The screen is reached from a `role-subscriptions` deep link for a guild the client cannot sell
 * for. A guild that turns out not to sell memberships at all closes the screen again rather than
 * showing an empty explanation — [ViewState.Invalid].
 */
class WidgetGuildRoleSubscriptionEntryPoint : AppFragment(R.layout.widget_guild_role_subscription_entry_point) {

    private val viewModel: WidgetGuildRoleSubscriptionEntryPointViewModel by appViewModels {
        WidgetGuildRoleSubscriptionEntryPointViewModel()
    }

    private lateinit var title: TextView
    private lateinit var description: TextView

    private val guildId: Long
        get() = mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, -1L)

    private val trackingSourceLocation: String?
        get() = mostRecentIntent.getStringExtra(INTENT_EXTRA_TRACKING_SOURCE_LOCATION)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        // Bound before the view exists so the first store emission is already on its way by the
        // time `onViewBoundOrOnResume` subscribes.
        viewModel.setGuildId(guildId)
        viewModel.setTrackingSourceLocation(trackingSourceLocation)
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        title = requireNotNull(view.findViewById(R.id.title))
        description = requireNotNull(view.findViewById(R.id.description))
        setActionBarDisplayHomeAsUpEnabled()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionEntryPoint::class.java) { configureUI(it) }
    }

    private fun configureUI(viewState: ViewState) {
        when (viewState) {
            // The guild is not known yet; the headline would name an empty server.
            is ViewState.Uninitialized -> description.visibility = View.GONE
            is ViewState.Invalid -> requireActivity().onBackPressed()
            is ViewState.Valid -> {
                title.text = I18nHooks.format(
                    requireContext(),
                    R.string.guild_role_subscription_purchase_page_unavailable_cta_hook,
                    mapOf("serverName" to viewState.guild.name),
                    mapOf(
                        "serverNameHook" to Hook(
                            spans = listOf(
                                ForegroundColorSpan(
                                    ColorCompat.getThemedColor(context, R.attr.primary_000),
                                ),
                            ),
                        ),
                    ),
                )
                description.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val INTENT_EXTRA_TRACKING_SOURCE_LOCATION = "INTENT_EXTRA_TRACKING_SOURCE_LOCATION"

        fun launch(context: Context, guildId: Long, trackingSourceLocation: String) {
            AppScreenRouter.launch(
                context,
                WidgetGuildRoleSubscriptionEntryPoint::class.java,
                Intent()
                    .putExtra(INTENT_EXTRA_GUILD_ID, guildId)
                    .putExtra(INTENT_EXTRA_TRACKING_SOURCE_LOCATION, trackingSourceLocation),
            )
        }
    }
}
