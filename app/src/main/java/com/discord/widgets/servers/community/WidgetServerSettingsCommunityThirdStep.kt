package com.discord.widgets.servers.community

import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.i18n.I18nSpans
import com.discord.i18n.setI18nText
import com.discord.utilities.rx.ui
import com.discord.utilities.view.ToastManager
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.CheckedSetting

class WidgetServerSettingsCommunityThirdStep :
    AppFragment(R.layout.widget_server_settings_community_setup_third_step) {

    private val viewModel: WidgetServerSettingsEnableCommunityViewModel by enableCommunityViewModel()
    private val toastManager = ToastManager()
    private lateinit var defaultNotifications: CheckedSetting
    private lateinit var everyonePermissions: CheckedSetting
    private lateinit var guidelinesAccepted: CheckedSetting

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        defaultNotifications = view.findViewById(R.id.community_setting_notifications_to_mentions_switch)
        everyonePermissions = view.findViewById(R.id.community_setting_manage_permissions_switch)
        guidelinesAccepted = view.findViewById(R.id.community_setting_community_guidelines_switch)
        defaultNotifications.setText(
            I18nSpans.render(getString(R.string.enable_community_modal_default_notifications_label_mobile)),
        )
        everyonePermissions.setText(
            I18nSpans.render(getString(R.string.enable_community_modal_everyone_role_permission_label_mobile)),
        )
        view.findViewById<LinkifiedTextView>(R.id.community_setting_guidelines).setI18nText(
            R.string.community_policy_help,
            "helpdeskArticle" to COMMUNITY_GUIDELINES_URL,
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe { state ->
            if (state is WidgetServerSettingsEnableCommunityViewModel.ViewState.Loaded) configureUI(state)
        }
    }

    private fun configureUI(state: WidgetServerSettingsEnableCommunityViewModel.ViewState.Loaded) {
        val config = state.communityGuildConfig
        defaultNotifications.setChecked(config.defaultMessageNotifications)
        everyonePermissions.setChecked(config.everyonePermissions)
        guidelinesAccepted.setChecked(config.isPrivacyPolicyAccepted)

        val guildDefaultsToMentions = config.guild?.defaultMessageNotifications == 1
        val everyoneRoleAlreadyRestricted = config.roles?.get(config.guild?.id)?.permissions?.let {
            it and WidgetServerSettingsEnableCommunityViewModel.COMMUNITY_EVERYONE_PERMISSIONS == it
        } == true
        defaultNotifications.setContainerClickListener {
            if (config.defaultMessageNotifications && guildDefaultsToMentions) showRequirementSatisfied()
            else viewModel.modifyGuildConfig {
                it.copy(defaultMessageNotifications = !defaultNotifications.isChecked)
            }
        }
        everyonePermissions.setContainerClickListener {
            if (config.everyonePermissions && everyoneRoleAlreadyRestricted) showRequirementSatisfied()
            else viewModel.modifyGuildConfig { it.copy(everyonePermissions = !everyonePermissions.isChecked) }
        }
        guidelinesAccepted.setContainerClickListener {
            viewModel.modifyGuildConfig {
                it.copy(isPrivacyPolicyAccepted = !guidelinesAccepted.isChecked)
            }
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

    companion object {
        private const val COMMUNITY_GUIDELINES_URL =
            "https://support.discord.com/hc/en-us/articles/360035969312"
    }
}
