package com.discord.widgets.voice.feedback

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.widgets.feedback.FeedbackSheetViewModel
import com.discord.widgets.feedback.WidgetFeedbackSheet
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.voice.feedback.call.CallFeedbackSheetViewModel
import com.discord.widgets.voice.feedback.stream.StreamFeedbackSheetViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.discord.widgets.feedback.PendingFeedback as FeedbackSheetPendingFeedback

class WidgetIssueDetailsForm : AppFragment(R.layout.widget_issue_details_form) {
    private val pendingFeedback: FeedbackSheetPendingFeedback
        get() = requireNotNull(
            mostRecentIntent.getSerializableExtra(ARG_PENDING_VOICE_FEEDBACK) as? FeedbackSheetPendingFeedback,
        )

    private val viewModel: IssueDetailsFormViewModel by appViewModels {
        IssueDetailsFormViewModel(pendingFeedback)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.feedback_modal_title)
        setActionBarDisplayHomeAsUpEnabled(iconAccessibilityLabel = R.string.close)
            ?.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        val input = view.findViewById<TextInputLayout>(R.id.issue_details_input)
        val submit = view.findViewById<MaterialButton>(R.id.issue_details_submit_button)
        val prompt = view.findViewById<LinkifiedTextView>(R.id.issue_details_cx_prompt)
        submit.isEnabled = false
        submit.setOnClickListener { viewModel.submitForm(input.editText?.text?.toString().orEmpty()) }
        input.editText?.doAfterTextChanged { submit.isEnabled = !it.isNullOrEmpty() }
        prompt.visibility = if (mostRecentIntent.getBooleanExtra(ARG_SHOW_CX_PROMPT, false)) View.VISIBLE else View.GONE
        prompt.text = SettingsStrings.render(
            view.context,
            R.string.feedback_need_more_help,
            "helpdeskURL" to HELP_URL,
        )
        input.editText?.let(::showKeyboard)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeEvents().takeUntil(unsubscribeSignal).subscribe {
            if (it == IssueDetailsFormViewModel.Event.Close) {
                AppToast.show(this, R.string.call_feedback_confirmation)
                hideKeyboard()
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    companion object {
        const val ARG_PENDING_VOICE_FEEDBACK = "ARG_PENDING_VOICE_FEEDBACK"
        const val ARG_SHOW_CX_PROMPT = "ARG_SHOW_CX_PROMPT"
        private const val HELP_URL = "https://support.discord.com/hc/en-us/requests/new"

        fun launch(context: Context, pendingFeedback: FeedbackSheetPendingFeedback, showCxPrompt: Boolean) {
            AppScreenRouter.launch(
                context,
                WidgetIssueDetailsForm::class.java,
                Intent()
                    .putExtra(ARG_PENDING_VOICE_FEEDBACK, pendingFeedback)
                    .putExtra(ARG_SHOW_CX_PROMPT, showCxPrompt),
            )
        }

        internal fun installBindings() {
            WidgetFeedbackSheet.issueDetailsLauncher = ::launch
            WidgetFeedbackSheet.voiceViewModelProvider = { sheet, feedbackType ->
                when (feedbackType) {
                    WidgetFeedbackSheet.FeedbackType.CALL -> sheet.voiceViewModel(
                        CallFeedbackSheetViewModel::class.java,
                    ) {
                        val config = requireNotNull(
                            sheet.requireArguments().getParcelable<CallFeedbackSheetViewModel.Config>(
                                WidgetFeedbackSheet.ARG_CALL_FEEDBACK_CONFIG,
                            ),
                        )
                        CallFeedbackSheetViewModel(config)
                    }
                    WidgetFeedbackSheet.FeedbackType.STREAM -> sheet.voiceViewModel(
                        StreamFeedbackSheetViewModel::class.java,
                    ) {
                        StreamFeedbackSheetViewModel(
                            requireNotNull(
                                sheet.requireArguments().getString(WidgetFeedbackSheet.ARG_STREAM_FEEDBACK_STREAM_KEY),
                            ),
                            sheet.requireArguments().getString(
                                WidgetFeedbackSheet.ARG_STREAM_FEEDBACK_MEDIA_SESSION_ID,
                            ),
                        )
                    }
                    WidgetFeedbackSheet.FeedbackType.GUILD_DELETE -> null
                }
            }
        }

        private fun <T> WidgetFeedbackSheet.voiceViewModel(
            modelClass: Class<T>,
            create: () -> T,
        ): T where T : ViewModel, T : FeedbackSheetViewModel =
            ViewModelProvider(
                this,
                object : ViewModelProvider.Factory {
                    override fun <V : ViewModel> create(requestedClass: Class<V>): V {
                        require(requestedClass == modelClass)
                        @Suppress("UNCHECKED_CAST")
                        return create() as V
                    }
                },
            ).get(modelClass)
    }
}
