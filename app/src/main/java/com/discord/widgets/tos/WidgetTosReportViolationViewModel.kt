package com.discord.widgets.tos

import androidx.annotation.MainThread
import com.discord.api.report.ReportReason
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * State for the "report a Terms of Service violation" screen.
 *
 * The reason list is not stored anywhere — it is a per-report `GET /report` response — so the
 * "store state" this view model reduces is a single REST call. The screen has one piece of state
 * the reason list does not carry, [reasonSelected], which survives the loaded/error transitions
 * because the reference keeps it on the view model rather than in the view state.
 *
 * @param api injectable so state reduction can be unit-tested without the process-wide client.
 */
class WidgetTosReportViolationViewModel(
    private val channelId: Long,
    private val messageId: Long,
    private val api: RestAPIInterface? = RestAPI.apiOrNull,
) : AppViewModel<WidgetTosReportViolationViewModel.ViewState>(ViewState.Loading) {

    sealed class StoreState {
        data class ReportReasons(val reasons: List<ReportReason>) : StoreState()
    }

    sealed class ViewState {
        object Loading : ViewState()
        data class Submitting(val reason: Int) : ViewState()
        object Submitted : ViewState()
        object SubmissionError : ViewState()
        data class Loaded(val reasons: List<ReportReason>) : ViewState()
    }

    /** The radio the user picked; `null` disables the submit button. */
    var reasonSelected: ReportReason? = null

    /** Retained so [handleDismissError] can rebuild the loaded state after an error dialog. */
    private var storeState: StoreState? = null

    init {
        observeStoreState()
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetTosReportViolationViewModel::class.java) { next ->
                generateViewState(next)
            }
    }

    private fun observeStoreState(): Observable<StoreState> {
        val api = api ?: return Observable.empty()
        return api.report(channelId = channelId, messageId = messageId)
            .restSubscribeOn()
            .map { reasons -> StoreState.ReportReasons(reasons) }
    }

    @MainThread
    internal fun generateViewState(storeState: StoreState) {
        this.storeState = storeState
        if (storeState is StoreState.ReportReasons) {
            updateViewState(ViewState.Loaded(storeState.reasons))
        }
    }

    /** Re-publishes the last store state, returning the screen to its loaded form. */
    @MainThread
    fun handleDismissError() {
        storeState?.let(::generateViewState)
    }

    /**
     * Moves to [ViewState.Submitting]; the screen answers by disabling the form and calling
     * [sendReportAPICall]. Splitting the two keeps the "which reason" decision in the view model and
     * the "form is now busy" rendering in the view.
     */
    @MainThread
    fun sendReport() {
        val reason = reasonSelected ?: return
        updateViewState(ViewState.Submitting(reason.reason))
    }

    @MainThread
    fun sendReportAPICall(reason: Int, channelId: Long, messageId: Long) {
        val api = api
        if (api == null) {
            updateViewState(ViewState.SubmissionError)
            return
        }
        api.report(RestAPIParams.Report(reason = reason, channelId = channelId, messageId = messageId))
            .restSubscribeOn()
            .ui()
            .appSubscribe(
                errorClass = WidgetTosReportViolationViewModel::class.java,
                errorHandler = { updateViewState(ViewState.SubmissionError) },
            ) {
                updateViewState(ViewState.Submitted)
            }
    }
}
