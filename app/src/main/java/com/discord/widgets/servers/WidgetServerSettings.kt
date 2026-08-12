package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.models.guild.Guild
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.permissions.ManageGuildContext
import com.discord.utilities.rx.ui
import com.discord.widgets.servers.auditlog.WidgetServerSettingsAuditLog
import com.discord.widgets.servers.community.WidgetServerSettingsCommunityOverview
import com.discord.widgets.servers.community.WidgetServerSettingsEnableCommunitySteps
import com.discord.widgets.servers.creator_monetization_eligibility.onboarding.WidgetServerSettingsCreatorMonetizationOnboarding
import com.discord.widgets.servers.guild_role_subscription.WidgetServerSettingsGuildRoleSubscriptionTierList
import com.discord.widgets.servers.guild_role_subscription.WidgetServerSettingsGuildRoleSubscriptions
import com.discord.widgets.servers.guild_role_subscription.payments.WidgetServerSettingsGuildRoleSubscriptionEarnings
import com.discord.widgets.servers.settings.invites.WidgetServerSettingsInstantInvites
import com.discord.widgets.servers.settings.members.WidgetServerSettingsMembers
import com.discord.widgets.settings.SettingsNavigation

/**
 * The server-settings landing screen: the guild's icon and name, and the sections a member is
 * allowed to open.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettings`. Every row is gated on
 * [com.discord.utilities.permissions.ManageGuildContext], computed by
 * [WidgetServerSettingsViewModel]; a section header disappears when none of its rows survives the
 * gate, and the whole screen closes when the user may manage nothing at all.
 *
 * **Rows whose destination screen has not been reconstructed stay hidden even when the permission
 * allows them** — see [IMPLEMENTED_OPTIONS]. That set is the only thing to change as those screens
 * land; the permission gating below already matches the reference.
 */
class WidgetServerSettings : AppFragment(R.layout.widget_server_settings) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: WidgetServerSettingsViewModel by appViewModels {
        WidgetServerSettingsViewModel(guildId)
    }

    private lateinit var serverIcon: ImageView
    private lateinit var serverIconText: TextView
    private lateinit var serverName: TextView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.server_settings)
        serverIcon = view.findViewById(R.id.server_settings_server_icon)
        serverIconText = view.findViewById(R.id.server_settings_server_icon_text)
        serverName = view.findViewById(R.id.server_settings_server_name)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe { configureUI(it) }
    }

    private fun configureUI(viewState: WidgetServerSettingsViewModel.ViewState) {
        when (viewState) {
            is WidgetServerSettingsViewModel.ViewState.Uninitialized -> Unit
            is WidgetServerSettingsViewModel.ViewState.Unauthorized -> {
                appActivity?.onBackPressed()
            }

            is WidgetServerSettingsViewModel.ViewState.Loaded -> {
                configureHeader(viewState)
                configureOptions(viewState)
                configureSectionsVisibility()
            }
        }
    }

    private fun configureHeader(model: WidgetServerSettingsViewModel.ViewState.Loaded) {
        val iconUrl = IconUtils.getForGuild(model.guild, allowAnimation = true, size = HEADER_ICON_SIZE_DP)
        serverName.text = model.guild.name
        IconUtils.setIcon(serverIcon, iconUrl, R.dimen.avatar_size_hero)
        // The lettered fallback is a sibling of the image, not a placeholder inside it.
        serverIconText.visibility = if (iconUrl == null) View.VISIBLE else View.GONE
        serverIconText.text = model.guild.shortName

        setActionBarOptionsMenu(
            if (model.canDeleteGuild) R.menu.menu_server_settings else R.menu.menu_empty,
            onSelected = { item ->
                if (item.itemId == R.id.menu_server_settings_delete) {
                    WidgetServerDeleteDialog.show(parentFragmentManager, model.guild.id)
                }
            },
        )
    }

    private fun configureOptions(model: WidgetServerSettingsViewModel.ViewState.Loaded) {
        val permissions = model.manageGuildContext
        val guildId = model.guild.id

        bindOption(R.id.server_settings_option_overview, permissions.canManageServer)
        bindOption(R.id.server_settings_option_moderation, permissions.canManageServer)
        bindOption(R.id.server_settings_option_audit_log, permissions.canViewAuditLogs)
        bindOption(R.id.server_settings_option_channels, permissions.canManageChannels)
        bindOption(R.id.server_settings_option_integrations, permissions.canManageServer)
        bindOption(R.id.server_settings_option_security, permissions.isOwnerWithRequiredMFALevel)
        bindOption(
            R.id.server_settings_option_vanity_url,
            permissions.canManageServer && model.hasVanityUrl,
        )
        bindOption(R.id.server_settings_emojis_option, permissions.canManageEmojisAndStickers)
        bindOption(R.id.server_settings_stickers_option, permissions.canManageEmojisAndStickers)
        bindOption(
            R.id.server_settings_official_messages_option,
            permissions.canManageServer && model.isVerified,
        )
        bindOption(
            R.id.server_settings_server_tag_option,
            permissions.canManageServer && model.hasGuildTags,
        )

        // The community section as a whole needs Manage Server; inside it, exactly one of
        // "Overview" and "Enable Community" is shown, depending on whether the guild is one.
        bindOption(
            R.id.server_settings_community_overview_option,
            permissions.canManageServer && model.isCommunity,
        )
        bindOption(
            R.id.server_settings_enable_community_option,
            permissions.canManageServer && !model.isCommunity,
        )

        val showSubscriptionSections =
            model.canManageGuildRoleSubscriptions && model.hasGuildRoleSubscriptions
        bindOption(
            R.id.server_settings_guild_role_subscription_get_started,
            model.canManageGuildRoleSubscriptions &&
                !model.hasGuildRoleSubscriptions &&
                !model.isCreatorMonetizable,
        )
        bindOption(R.id.server_settings_guild_role_subscription_basic_info, showSubscriptionSections)
        bindOption(R.id.server_settings_guild_role_subscription_tiers, showSubscriptionSections)
        bindOption(R.id.server_settings_guild_role_subscription_earnings, showSubscriptionSections)
        bindOption(R.id.server_settings_guild_role_subscription_emojis, showSubscriptionSections)

        // Members is the one row the reference never gates: any member who reaches this screen at
        // all may open the member list.
        bindOption(R.id.server_settings_members_option, true)
        bindOption(R.id.server_settings_roles_option, permissions.canManageRoles)
        bindOption(R.id.server_settings_instant_invites_option, permissions.canManageServer)
        bindOption(R.id.server_settings_bans_option, permissions.canManageBans)

        bindDestination(R.id.server_settings_bans_option) {
            WidgetServerSettingsBans.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_option_audit_log) {
            WidgetServerSettingsAuditLog.launch(it.context, guildId, model.guild.name)
        }
        bindDestination(R.id.server_settings_members_option) {
            WidgetServerSettingsMembers.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_roles_option) {
            WidgetServerSettingsRoles.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_option_channels) {
            WidgetServerSettingsChannels.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_option_overview) {
            WidgetServerSettingsOverview.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_option_moderation) {
            WidgetServerSettingsModeration.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_option_security) {
            WidgetServerSettingsSecurity.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_option_vanity_url) {
            WidgetServerSettingsVanityUrl.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_option_integrations) {
            WidgetServerSettingsIntegrations.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_instant_invites_option) {
            WidgetServerSettingsInstantInvites.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_emojis_option) {
            WidgetServerSettingsEmojis.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_stickers_option) {
            WidgetServerSettingsStickers.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_official_messages_option) {
            WidgetServerSettingsOfficialMessages.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_server_tag_option) {
            WidgetServerSettingsServerTag.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_community_overview_option) {
            WidgetServerSettingsCommunityOverview.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_enable_community_option) {
            WidgetServerSettingsEnableCommunitySteps.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_guild_role_subscription_get_started) {
            WidgetServerSettingsCreatorMonetizationOnboarding.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_guild_role_subscription_basic_info) {
            WidgetServerSettingsGuildRoleSubscriptions.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_guild_role_subscription_tiers) {
            WidgetServerSettingsGuildRoleSubscriptionTierList.launch(it.context, guildId)
        }
        bindDestination(R.id.server_settings_guild_role_subscription_earnings) {
            WidgetServerSettingsGuildRoleSubscriptionEarnings.launch(it.context)
        }
        bindDestination(R.id.server_settings_guild_role_subscription_emojis) {
            WidgetServerSettingsEmojis.launch(it.context, guildId, roleSubscription = true)
        }
    }

    /** Shows the row when [allowed] *and* its destination exists; hides it otherwise. */
    private fun bindOption(optionId: Int, allowed: Boolean) {
        requireView().findViewById<View>(optionId).visibility =
            if (allowed && optionId in IMPLEMENTED_OPTIONS) View.VISIBLE else View.GONE
    }

    private fun bindDestination(optionId: Int, onClick: (View) -> Unit) {
        requireView().findViewById<View>(optionId).setOnClickListener(onClick)
    }

    /** A section header is only worth showing when at least one of its rows survived the gate. */
    private fun configureSectionsVisibility() {
        val view = requireView()
        SECTIONS.forEach { (sectionId, optionIds) ->
            val anyVisible = optionIds.any { view.findViewById<View>(it).visibility == View.VISIBLE }
            view.findViewById<View>(sectionId).visibility =
                if (anyVisible) View.VISIBLE else View.GONE
        }
    }

    /** Exact 126.21 state shape retained for callers that depend on the nested JVM path. */
    internal data class Model(
        val guild: Guild,
        val manageGuildContext: ManageGuildContext,
        val canManageGuildRoleSubscriptions: Boolean,
        val hasGuildRoleSubscriptions: Boolean,
    ) {
        companion object
    }

    companion object {
        /** Matches `avatar_size_hero`, which is what the header actually draws it at. */
        private const val HEADER_ICON_SIZE_DP = 128

        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L

        /**
         * Rows whose destination screen exists in this project.
         *
         * The reference has no equivalent: it shows every permitted row. Add an id here — and a
         * `bindDestination` call — as each section is reconstructed, and delete the whole mechanism
         * once they all are.
         */
        private val IMPLEMENTED_OPTIONS = setOf(
            R.id.server_settings_bans_option,
            R.id.server_settings_option_audit_log,
            R.id.server_settings_members_option,
            R.id.server_settings_roles_option,
            R.id.server_settings_option_channels,
            R.id.server_settings_option_overview,
            R.id.server_settings_option_moderation,
            R.id.server_settings_option_security,
            R.id.server_settings_option_vanity_url,
            R.id.server_settings_option_integrations,
            R.id.server_settings_instant_invites_option,
            R.id.server_settings_emojis_option,
            R.id.server_settings_stickers_option,
            R.id.server_settings_official_messages_option,
            R.id.server_settings_server_tag_option,
            R.id.server_settings_community_overview_option,
            R.id.server_settings_enable_community_option,
            R.id.server_settings_guild_role_subscription_get_started,
            R.id.server_settings_guild_role_subscription_basic_info,
            R.id.server_settings_guild_role_subscription_tiers,
            R.id.server_settings_guild_role_subscription_earnings,
            R.id.server_settings_guild_role_subscription_emojis,
        )

        /** Section container → the option rows inside it. */
        private val SECTIONS = listOf(
            R.id.server_settings_section_general_settings to listOf(
                R.id.server_settings_option_overview,
                R.id.server_settings_option_moderation,
                R.id.server_settings_option_audit_log,
                R.id.server_settings_option_channels,
                R.id.server_settings_option_integrations,
                R.id.server_settings_option_security,
                R.id.server_settings_option_vanity_url,
                R.id.server_settings_emojis_option,
                R.id.server_settings_stickers_option,
                R.id.server_settings_official_messages_option,
                R.id.server_settings_server_tag_option,
            ),
            R.id.server_settings_section_community to listOf(
                R.id.server_settings_community_overview_option,
                R.id.server_settings_enable_community_option,
            ),
            R.id.server_settings_section_server_monetization to listOf(
                R.id.server_settings_guild_role_subscription_get_started,
                R.id.server_settings_guild_role_subscription_basic_info,
                R.id.server_settings_guild_role_subscription_tiers,
                R.id.server_settings_guild_role_subscription_earnings,
                R.id.server_settings_guild_role_subscription_emojis,
            ),
            R.id.server_settings_section_user_management to listOf(
                R.id.server_settings_members_option,
                R.id.server_settings_roles_option,
                R.id.server_settings_instant_invites_option,
                R.id.server_settings_bans_option,
            ),
        )

        fun newInstance(guildId: Long) = WidgetServerSettings().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettings::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
