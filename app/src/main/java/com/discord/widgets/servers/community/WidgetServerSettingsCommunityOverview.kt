package com.discord.widgets.servers.community

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.appActivityViewModels
import com.discord.utilities.rx.ui
import com.discord.views.LoadingButton
import com.discord.widgets.channels.WidgetChannelSelector
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.WidgetSettingsLanguage
import com.discord.widgets.settings.WidgetSettingsLanguageSelect

class WidgetServerSettingsCommunityOverview :
    AppFragment(R.layout.widget_server_settings_community_overview) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_ID)
            ?.takeIf { it != INVALID_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_ID)

    private val viewModel: WidgetServerSettingsCommunityOverviewViewModel by appActivityViewModels {
        WidgetServerSettingsCommunityOverviewViewModel(guildId)
    }

    private lateinit var rulesChannel: CommunitySelectorView
    private lateinit var updatesChannel: CommunitySelectorView
    private lateinit var locale: CommunitySelectorView
    private lateinit var disableButton: LoadingButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        rulesChannel = view.findViewById(R.id.community_rules_channel)
        updatesChannel = view.findViewById(R.id.community_updates_channel)
        locale = view.findViewById(R.id.server_settings_community_locale)
        disableButton = view.findViewById(R.id.server_settings_disable_community_button)

        WidgetSettingsLanguageSelect.registerForResult(this, viewModel::saveLocale)
        WidgetChannelSelector.registerForResult(this, REQUEST_KEY_RULES_CHANNEL) { channelId, _ ->
            viewModel.saveRulesChannel(channelId)
        }
        WidgetChannelSelector.registerForResult(this, REQUEST_KEY_UPDATES_CHANNEL) { channelId, _ ->
            viewModel.saveCommunityUpdatesChannel(channelId)
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.guild_settings_community)
        viewModel.observeViewState().distinctUntilChanged().ui(this).subscribe(::configureUI)
        viewModel.observeEvents().ui(this).subscribe { event ->
            when (event) {
                WidgetServerSettingsCommunityOverviewViewModel.Event.DisableCommunitySuccess ->
                    requireActivity().finish()
                WidgetServerSettingsCommunityOverviewViewModel.Event.SaveSuccess ->
                    AppToast.show(this, R.string.server_settings_updated)
                WidgetServerSettingsCommunityOverviewViewModel.Event.Error ->
                    AppToast.show(this, R.string.default_failure_to_perform_action_message)
            }
        }
    }

    private fun configureUI(viewState: WidgetServerSettingsCommunityOverviewViewModel.ViewState) {
        when (viewState) {
            WidgetServerSettingsCommunityOverviewViewModel.ViewState.Uninitialized -> Unit
            WidgetServerSettingsCommunityOverviewViewModel.ViewState.Invalid -> requireActivity().finish()
            is WidgetServerSettingsCommunityOverviewViewModel.ViewState.DisableCommunityLoading ->
                disableButton.setIsLoading(viewState.isLoading)
            is WidgetServerSettingsCommunityOverviewViewModel.ViewState.Loaded -> {
                disableButton.setIsLoading(false)
                rulesChannel.setSubtitle(
                    viewState.rulesChannel?.getDisplayNameOrDefault(requireContext(), includePrefix = false)
                        ?: getString(R.string.none),
                )
                updatesChannel.setSubtitle(
                    viewState.updatesChannel?.getDisplayNameOrDefault(requireContext(), includePrefix = false)
                        ?: getString(R.string.none),
                )
                locale.setSubtitle(WidgetSettingsLanguage.getAsStringInLocale(viewState.guild.preferredLocale))
                rulesChannel.setOnClickListener {
                    WidgetChannelSelector.launchForText(this, viewState.guild.id, REQUEST_KEY_RULES_CHANNEL)
                }
                updatesChannel.setOnClickListener {
                    WidgetChannelSelector.launchForText(this, viewState.guild.id, REQUEST_KEY_UPDATES_CHANNEL)
                }
                locale.setOnClickListener { WidgetSettingsLanguageSelect.show(this) }
                disableButton.setOnClickListener {
                    WidgetConfirmRemoveCommunityDialog.show(parentFragmentManager, viewState.guild.id)
                }
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val REQUEST_KEY_RULES_CHANNEL = "REQUEST_KEY_RULES_CHANNEL"
        private const val REQUEST_KEY_UPDATES_CHANNEL = "REQUEST_KEY_UPDATES_CHANNEL"
        private const val INVALID_ID = -1L

        fun newInstance(guildId: Long) = WidgetServerSettingsCommunityOverview().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsCommunityOverview::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
