package com.discord.widgets.servers.community

import android.view.View
import com.discord.R
import com.discord.api.guild.GuildExplicitContentFilter
import com.discord.api.guild.GuildVerificationLevel
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.utilities.rx.ui
import com.discord.utilities.view.ToastManager
import com.discord.views.CheckedSetting

class WidgetServerSettingsCommunityFirstStep :
    AppFragment(R.layout.widget_server_settings_community_setup_first_step) {

    private val viewModel: WidgetServerSettingsEnableCommunityViewModel by enableCommunityViewModel()
    private val toastManager = ToastManager()
    private lateinit var verification: CheckedSetting
    private lateinit var contentFilter: CheckedSetting

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        verification = view.findViewById(R.id.community_settings_verified_emailed_switch)
        contentFilter = view.findViewById(R.id.community_settings_scan_messages_switch)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe { state ->
            if (state is WidgetServerSettingsEnableCommunityViewModel.ViewState.Loaded) configureUI(state)
        }
    }

    private fun configureUI(state: WidgetServerSettingsEnableCommunityViewModel.ViewState.Loaded) {
        val config = state.communityGuildConfig
        verification.setChecked(config.verificationLevel)
        contentFilter.setChecked(config.explicitContentFilter)
        val guildVerificationEnabled = config.guild?.verificationLevel?.let { it > GuildVerificationLevel.NONE } == true
        val guildContentFilterEnabled =
            config.guild?.explicitContentFilter?.let { it > GuildExplicitContentFilter.NONE } == true

        verification.setContainerClickListener {
            if (config.verificationLevel && guildVerificationEnabled) showRequirementSatisfied()
            else viewModel.modifyGuildConfig { it.copy(verificationLevel = !verification.isChecked) }
        }
        contentFilter.setContainerClickListener {
            if (config.explicitContentFilter && guildContentFilterEnabled) showRequirementSatisfied()
            else viewModel.modifyGuildConfig { it.copy(explicitContentFilter = !contentFilter.isChecked) }
        }
    }

    private fun showRequirementSatisfied() = AppToast.show(
        context,
        R.string.enable_community_modal_requirement_satisfied_tooltip,
        toastManager = toastManager,
    )

    override fun onDestroyView() {
        toastManager.close()
        super.onDestroyView()
    }
}
