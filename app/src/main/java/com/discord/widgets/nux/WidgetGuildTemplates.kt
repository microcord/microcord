package com.discord.widgets.nux

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.utilities.analytics.AnalyticsTracker
import com.google.android.material.button.MaterialButton

/**
 * "What is your server for?" — the template picker that opens guild creation.
 *
 * Two entry points share this screen: post-registration onboarding (`isNux`, which reports funnel
 * steps) and the in-app "add a server" button. The picker itself is identical; only the analytics
 * location and whether the "already have an invite?" footer is shown differ.
 */
open class WidgetGuildTemplates : AppFragment(R.layout.widget_nux_guild_template) {

    @Suppress("DEPRECATION")
    private val args: GuildCreateArgs
        get() = requireNotNull(mostRecentIntent.getParcelableExtra(INTENT_ARGS_KEY)) {
            "Missing ${GuildCreateArgs::class.java.simpleName} for key $INTENT_ARGS_KEY!"
        }

    private val adapter = GuildTemplatesAdapter(
        object : GuildTemplatesAdapter.Callbacks {
            override fun onTemplateClick(guildTemplate: GuildTemplate) = selectTemplate(guildTemplate)
        },
    )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val args = this.args
        trackPostRegistrationTransition(
            GuildTemplateAnalytics.STEP_REGISTRATION,
            GuildTemplateAnalytics.STEP_GUILD_TEMPLATE,
        )
        // Leaving the picker by Back is a funnel exit, but it must not be swallowed.
        setOnBackPressed({
            trackPostRegistrationSkip()
            false
        })

        view.findViewById<RecyclerView>(R.id.recycler_view).adapter = adapter
        view.findViewById<TextView>(R.id.nux_guild_template_section_title).text = args.guildTemplate.title
        view.findViewById<TextView>(R.id.nux_guild_template_section_custom_label).text =
            args.guildTemplate.subtitle
        adapter.items = args.guildTemplate.templates.map(GuildTemplateViewType::Template)

        view.findViewById<MaterialButton>(R.id.nux_guild_template_action_join).setOnClickListener {
            trackPostRegistrationTransition(
                GuildTemplateAnalytics.STEP_GUILD_TEMPLATE,
                GuildTemplateAnalytics.STEP_GUILD_JOIN,
            )
            WidgetNuxPostRegistrationJoin.show(requireContext())
            activity?.finish()
        }
        view.findViewById<LinearLayout>(R.id.nux_guild_template_section_bottom).visibility =
            if (args.guildTemplate.showInvitePrompt) View.VISIBLE else View.GONE
    }

    /**
     * "Create My Own" skips straight past the intent question, because the user has already told us
     * they do not want a template — that is what [GuildCreateOptions.showChannelPrompt] encodes.
     */
    private fun selectTemplate(guildTemplate: GuildTemplate) {
        val args = this.args
        val options = GuildCreateOptions(
            analyticsLocation = args.location,
            template = guildTemplate.templateType,
            showChannelPrompt = guildTemplate.templateType == StockGuildTemplate.CREATE,
            customTitle = getString(R.string.guild_create_title),
            closeWithResult = args.closeWithResult,
        )
        if (args.guildTemplate.skipCreationIntent) {
            AnalyticsTracker.openModal("Create Guild Step 2", options.analyticsLocation)
            navigation.showGuildCreate(this, options)
        } else {
            trackPostRegistrationTransition(
                GuildTemplateAnalytics.STEP_GUILD_TEMPLATE,
                GuildTemplateAnalytics.STEP_CREATION_INTENT,
            )
            navigation.showCreationIntent(this, args.trigger, options)
        }
    }

    private fun trackPostRegistrationTransition(fromStep: String, toStep: String) {
        if (args.isNux) GuildTemplateAnalytics.postRegistrationTransition(fromStep, toStep)
    }

    private fun trackPostRegistrationSkip() {
        if (args.isNux) GuildTemplateAnalytics.postRegistrationSkip(GuildTemplateAnalytics.STEP_GUILD_TEMPLATE)
    }

    companion object {
        private const val INTENT_ARGS_KEY = "intent_args_key"

        /**
         * Where the picker goes next.
         *
         * Guild creation lives in `widgets/guilds/create`, which is outside this package's ownership.
         * Keep this seam until those two screens are reconstructed; the NUX-owned join destination is
         * launched directly.
         */
        @JvmStatic
        var navigation: Navigation = LoggingNavigation

        fun launch(context: Context, args: GuildCreateArgs) {
            AppScreenRouter.launch(
                context,
                WidgetGuildTemplates::class.java,
                Intent().putExtra(INTENT_ARGS_KEY, args),
            )
        }

        /** In-app entry point. [isNux] selects the post-registration analytics location. */
        fun launch(context: Context, trigger: CreateGuildTrigger, isNux: Boolean) {
            launch(
                context,
                GuildCreateArgs(
                    isNux = isNux,
                    location = if (isNux) {
                        GuildTemplateAnalytics.STEP_GUILD_TEMPLATE
                    } else {
                        GuildTemplateAnalytics.IN_APP_LOCATION_TEMPLATE
                    },
                    trigger = trigger,
                    guildTemplate = GuildTemplateArgs(
                        title = context.getString(R.string.guild_template_selector_title),
                        subtitle = context.getString(R.string.guild_template_selector_description),
                        showInvitePrompt = false,
                        skipCreationIntent = false,
                    ),
                ),
            )
        }
    }

    interface Navigation {
        fun showCreationIntent(fragment: Fragment, trigger: CreateGuildTrigger, options: GuildCreateOptions)
        fun showGuildCreate(fragment: Fragment, options: GuildCreateOptions)
    }

    private object LoggingNavigation : Navigation {
        override fun showCreationIntent(
            fragment: Fragment,
            trigger: CreateGuildTrigger,
            options: GuildCreateOptions,
        ) {
            AppLog.w("WidgetCreationIntent is not reconstructed; dropping $trigger / $options")
        }

        override fun showGuildCreate(fragment: Fragment, options: GuildCreateOptions) {
            AppLog.w("WidgetGuildCreate is not reconstructed; dropping $options")
        }
    }
}

/**
 * What [WidgetGuildTemplates] hands to guild creation once a template is chosen.
 *
 * Mirrors the reference's `WidgetGuildCreate.Options`; declared here because `WidgetGuildCreate`
 * does not exist yet. Move it onto that class when it does.
 */
data class GuildCreateOptions(
    val analyticsLocation: String,
    val template: StockGuildTemplate,
    val showChannelPrompt: Boolean,
    val customTitle: String,
    val closeWithResult: Boolean,
)
