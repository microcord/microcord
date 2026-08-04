package com.discord.widgets.settings.account.mfa

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appActivityViewModels
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.steps.StepsView

class WidgetEnableMFASteps : AppFragment(R.layout.widget_enable_mfa_steps) {
    private val viewModel by appActivityViewModels { WidgetEnableMFAViewModel() }
    private lateinit var steps: StepsView
    private lateinit var dimmer: DimmerView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        steps = view.findViewById(R.id.enable_mfa_stepsview)
        dimmer = view.findViewById(R.id.dimmer_view)
        steps.setup(
            StepsView.PagerAdapter(
                this,
                listOf(
                    StepsView.FragmentStep(WidgetEnableMFADownload::class.java),
                    StepsView.FragmentStep(WidgetEnableMFAKey::class.java),
                    StepsView.FragmentStep(
                        WidgetEnableMFAInput::class.java,
                        nextAllowed = false,
                        scrollingAllowed = false,
                    ),
                    StepsView.FragmentStep(WidgetEnableMFASuccess::class.java, scrollingAllowed = false),
                ),
            ),
            onClose = { activity?.onBackPressedDispatcher?.onBackPressed() },
            onPageSelected = viewModel::updateScreenIndex,
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .appSubscribe(WidgetEnableMFASteps::class.java) { state ->
                dimmer.setDimmed(state.isLoading, false)
                state.screenIndex?.let(steps::setCurrentPage)
            }
    }

    /**
     * The secret and password live only in the activity-scoped view model.
     *
     * They used to round-trip through `onSaveInstanceState`, which the view model already makes
     * unnecessary for rotation and which is wrong for process death: a saved `Bundle` is written to
     * disk, and neither a TOTP secret nor an account password belongs there. After process death the
     * password is simply asked for again.
     */
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (viewModel.getTotpSecret() == null) {
            viewModel.setTotpSecret(WidgetEnableMFAViewModel.generateNewTotpKey())
        }
        if (viewModel.getPassword() == null) showPasswordModal()
    }

    private fun showPasswordModal() {
        WidgetEnableTwoFactorPasswordDialog().apply {
            onValidPasswordEntered = {
                viewModel.setPassword(it)
                hideKeyboard()
            }
        }.show(childFragmentManager, WidgetEnableTwoFactorPasswordDialog::class.java.simpleName)
    }

    companion object {
        const val KEY_SCREEN = 2
        const val SUCCESS_SCREEN = 3

        @JvmStatic
        fun launch(context: Context) =
            AppScreenRouter.launch(context, WidgetEnableMFASteps::class.java, Intent())
    }
}
