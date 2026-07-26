package com.discord.widgets.guild_automod

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ImageView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.google.android.material.button.MaterialButton

/**
 * "Something wrong with AutoMod?" — a two-option radio form reached from an AutoMod block notice.
 *
 * Both option labels come from the layout; only the checked state and the click listener are bound
 * here, and they are re-bound on every emission so the selection survives a store rebuild.
 */
class WidgetReportIssueWithAutoMod : AppFragment(R.layout.widget_report_issue_with_automod) {

    private val channelId: Long by lazy {
        mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
    }

    private val messageId: Long by lazy {
        mostRecentIntent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
    }

    private val viewModel: ReportIssueWithAutoModViewModel by appViewModels {
        ReportIssueWithAutoModViewModel(channelId, messageId)
    }

    private lateinit var backButton: ImageView
    private lateinit var option1: CheckedSetting
    private lateinit var option2: CheckedSetting
    private lateinit var submitButton: MaterialButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        backButton = view.findViewById(R.id.back_button)
        option1 = view.findViewById(R.id.option1)
        option2 = view.findViewById(R.id.option2)
        submitButton = view.findViewById(R.id.submit_button)
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState()
            .ui(this)
            .subscribe(::configureUI)
    }

    private fun configureUI(viewState: ReportIssueWithAutoModViewModel.ViewState) {
        backButton.setOnClickListener { activity?.finish() }
        submitButton.setOnClickListener {
            viewModel.onSubmit { activity?.finish() }
        }
        // Invalid: the blocked message is no longer in the store, so neither option is bound and
        // submitting reports nothing.
        if (viewState !is ReportIssueWithAutoModViewModel.ViewState.Valid) return
        configureOption(viewState, option1, FeedbackType.ALLOWED)
        configureOption(viewState, option2, FeedbackType.BUG)
    }

    private fun configureOption(
        viewState: ReportIssueWithAutoModViewModel.ViewState.Valid,
        setting: CheckedSetting,
        settingValue: FeedbackType,
    ) {
        setting.setChecked(viewState.selectedOption == settingValue.value)
        setting.setContainerClickListener { viewModel.onFeedbackTypeSelected(settingValue) }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"

        fun launch(context: Context, channelId: Long, messageId: Long) {
            val intent = Intent()
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_MESSAGE_ID, messageId)
            AppScreenRouter.launch(context, WidgetReportIssueWithAutoMod::class.java, intent)
        }
    }
}
