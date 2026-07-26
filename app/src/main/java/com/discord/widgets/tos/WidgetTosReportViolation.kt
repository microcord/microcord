package com.discord.widgets.tos

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.children
import com.discord.R
import com.discord.api.report.ReportReason
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.i18n.i18nRender
import com.discord.i18n.setI18nText
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.ToastManager
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.LoadingButton
import com.discord.widgets.notice.WidgetNoticeDialog

/**
 * "Report a Terms of Service violation" — a radio list of API-supplied reasons plus a submit button.
 *
 * All of the state lives in [WidgetTosReportViolationViewModel]; this screen only renders the five
 * view states and forwards the two user intents (pick a reason, submit).
 */
class WidgetTosReportViolation : AppFragment(R.layout.widget_tos_report_violation) {

    /** Everything the screen needs to describe and address the report. */
    data class Arguments(val target: String, val channelId: Long, val messageId: Long)

    private val args: Arguments by lazy {
        Arguments(
            target = mostRecentIntent.getStringExtra(EXTRA_TARGET).orEmpty(),
            channelId = mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, 0L),
            messageId = mostRecentIntent.getLongExtra(EXTRA_MESSAGE_ID, 0L),
        )
    }

    private val viewModel: WidgetTosReportViolationViewModel by appViewModels {
        WidgetTosReportViolationViewModel(args.channelId, args.messageId)
    }

    private val toastManager = ToastManager()

    private val reportButton: LoadingButton
        get() = requireView().findViewById(R.id.report_button)

    private val reasonsContainer: LinearLayout
        get() = requireView().findViewById(R.id.report_reasons_container)

    private val reasonsLoading: ProgressBar
        get() = requireView().findViewById(R.id.report_reasons_loading)

    private val reasonsHeader: TextView
        get() = requireView().findViewById(R.id.report_reasons_header)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.report)
        setActionBarDisplayHomeAsUpEnabled()
        view.findViewById<TextView>(R.id.report_header)
            .setI18nText(R.string.report_message, "name" to args.target)
        view.findViewById<LinkifiedTextView>(R.id.report_tooltip)
            .setI18nText(R.string.form_report_help_text, "guidelinesURL" to COMMUNITY_GUIDELINES_URL)
        reportButton.setIsLoading(false)
        reportButton.isEnabled = false
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetTosReportViolation::class.java, context, onNext = ::configureUI)
    }

    private fun configureUI(viewState: WidgetTosReportViolationViewModel.ViewState) {
        when (viewState) {
            is WidgetTosReportViolationViewModel.ViewState.Loading -> handleLoading()
            is WidgetTosReportViolationViewModel.ViewState.Loaded -> handleLoaded(viewState.reasons)
            is WidgetTosReportViolationViewModel.ViewState.Submitting -> handleReportSubmitting(viewState.reason)
            is WidgetTosReportViolationViewModel.ViewState.Submitted -> handleReportSubmitted()
            is WidgetTosReportViolationViewModel.ViewState.SubmissionError -> handleReportSubmissionError()
        }
    }

    private fun handleLoading() {
        reasonsLoading.visibility = View.VISIBLE
        reportButton.isEnabled = false
        reasonsContainer.removeAllViews()
    }

    private fun handleLoaded(reasons: List<ReportReason>) {
        reasonsLoading.visibility = View.GONE
        reportButton.isEnabled = viewModel.reasonSelected != null
        reportButton.setIsLoading(false)
        reportButton.setOnClickListener { viewModel.sendReport() }
        reasonsHeader.visibility = View.VISIBLE

        reasonsContainer.removeAllViews()
        reasons.forEach { reason ->
            val row = WidgetTosReportViolationReasonView(requireContext())
            row.reason = reason
            row.isChecked = reason == viewModel.reasonSelected
            row.setOnClickListener { onReasonClicked(row) }
            reasonsContainer.addView(row)
        }
    }

    /** Radio semantics: exactly one row stays checked, and the button follows the selection. */
    private fun onReasonClicked(row: WidgetTosReportViolationReasonView) {
        if (viewModel.reasonSelected != row.reason) {
            viewModel.reasonSelected = row.reason
            reasonsContainer.children
                .filterIsInstance<WidgetTosReportViolationReasonView>()
                .forEach { it.isChecked = false }
            row.isChecked = true
        }
        reportButton.isEnabled = viewModel.reasonSelected != null
    }

    private fun handleReportSubmitting(reason: Int) {
        reasonsContainer.children.forEach { it.isEnabled = false }
        reportButton.isEnabled = false
        reportButton.setIsLoading(true)
        viewModel.sendReportAPICall(reason, args.channelId, args.messageId)
    }

    private fun handleReportSubmitted() {
        context?.let { toastManager.show(it, R.string.report_modal_submitted) }
        appActivity?.finish()
    }

    private fun handleReportSubmissionError() {
        reportButton.isEnabled = false
        reportButton.setIsLoading(false)
        val context = context ?: return
        WidgetNoticeDialog.show(
            fragmentManager = parentFragmentManager,
            headerText = context.i18nRender(R.string.notice_dispatch_error),
            bodyText = context.i18nRender(R.string.report_modal_error, "tnsReportUrl" to TNS_REPORT_URL),
            okText = context.i18nRender(R.string.okay),
            onDismiss = viewModel::handleDismissError,
        )
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "EXTRA_CHANNEL_ID"
        private const val EXTRA_MESSAGE_ID = "EXTRA_MESSAGE_ID"
        private const val EXTRA_TARGET = "EXTRA_TARGET"

        private const val COMMUNITY_GUIDELINES_URL = "https://discord.com/guidelines"
        private const val TNS_REPORT_URL = "https://dis.gd/request"

        /** [target] is the display name shown in the "Report Message Posted by …" header. */
        fun show(context: Context, target: String, channelId: Long? = null, messageId: Long? = null) {
            val intent = Intent()
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_CHANNEL_ID, channelId ?: 0L)
                .putExtra(EXTRA_MESSAGE_ID, messageId ?: 0L)
            AppScreenRouter.launch(context, WidgetTosReportViolation::class.java, intent)
        }
    }
}
