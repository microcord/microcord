package com.discord.widgets.servers.community

import android.content.Context
import android.content.Intent
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.utilities.rx.ui
import com.discord.views.steps.StepsView
import com.discord.widgets.settings.SettingsNavigation

class WidgetServerSettingsEnableCommunitySteps : AppFragment(R.layout.widget_enable_community_steps) {

    val guildId: Long
        get() = arguments?.getLong(ARG_GUILD_ID, INVALID_ID)
            ?.takeIf { it != INVALID_ID }
            ?: mostRecentIntent.getLongExtra(ARG_GUILD_ID, INVALID_ID)

    private val viewModel: WidgetServerSettingsEnableCommunityViewModel by appViewModels {
        WidgetServerSettingsEnableCommunityViewModel(guildId)
    }

    private lateinit var stepsView: StepsView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        stepsView = view.findViewById(R.id.enable_community_stepsview)
        stepsView.setup(
            StepsView.PagerAdapter(
                this,
                listOf(
                    StepsView.FragmentStep(
                        WidgetServerSettingsEnableCommunity::class.java,
                        nextAllowed = false,
                        cancelAllowed = false,
                        scrollingAllowed = false,
                    ),
                    StepsView.FragmentStep(
                        WidgetServerSettingsCommunityFirstStep::class.java,
                        cancelAllowed = false,
                        scrollingAllowed = false,
                    ),
                    StepsView.FragmentStep(
                        WidgetServerSettingsCommunitySecondStep::class.java,
                        cancelAllowed = false,
                        scrollingAllowed = false,
                    ),
                    StepsView.FragmentStep(
                        WidgetServerSettingsCommunityThirdStep::class.java,
                        doneLabel = R.string.enable_community_button_text,
                        doneAction = { viewModel.updateGuild() },
                        cancelAllowed = false,
                        scrollingAllowed = false,
                    ),
                ),
            ),
            onClose = ::closeScreen,
            onPageSelected = viewModel::updateCurrentPage,
        )
        setOnBackPressed(handler = {
            viewModel.goBackToPreviousPage()
            true
        })
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().ui(this).subscribe(::configureUI)
        viewModel.observeEvents().ui(this).subscribe { event ->
            when (event) {
                WidgetServerSettingsEnableCommunityViewModel.Event.SaveSuccess -> {
                    val context = requireContext()
                    closeScreen(immediate = true)
                    WidgetServerSettingsCommunityOverview.launch(context, guildId)
                }
                WidgetServerSettingsEnableCommunityViewModel.Event.Error ->
                    AppToast.show(this, R.string.default_failure_to_perform_action_message)
            }
        }
    }

    private fun configureUI(viewState: WidgetServerSettingsEnableCommunityViewModel.ViewState) {
        when (viewState) {
            WidgetServerSettingsEnableCommunityViewModel.ViewState.Uninitialized -> Unit
            WidgetServerSettingsEnableCommunityViewModel.ViewState.Invalid -> closeScreen()
            is WidgetServerSettingsEnableCommunityViewModel.ViewState.Loaded -> {
                if (viewState.currentPage < 0) {
                    closeScreen()
                    return
                }
                stepsView.setCurrentPage(viewState.currentPage)
                stepsView.setIsNextButtonEnabled(
                    viewState.communityGuildConfig.explicitContentFilter &&
                        viewState.communityGuildConfig.verificationLevel,
                )
                stepsView.setIsDoneButtonEnabled(viewState.communityGuildConfig.isPrivacyPolicyAccepted)
                stepsView.setIsLoading(viewState.isLoading)
            }
        }
    }

    private fun closeScreen(immediate: Boolean = false) {
        val manager = parentFragmentManager
        if (manager.backStackEntryCount == 0) {
            requireActivity().finish()
        } else if (immediate) {
            manager.popBackStackImmediate()
        } else {
            manager.popBackStack()
        }
    }

    companion object {
        private const val INVALID_ID = -1L

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsEnableCommunitySteps::class.java,
                Intent().putExtra(ARG_GUILD_ID, guildId),
            )
        }
    }
}
