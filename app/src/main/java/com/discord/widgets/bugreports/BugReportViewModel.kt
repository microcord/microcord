package com.discord.widgets.bugreports

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import com.discord.api.bugreport.BugReportConfig
import com.discord.api.bugreport.Feature
import com.discord.api.sticker.Sticker
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreStream
import com.discord.stores.utilities.Loading
import com.discord.stores.utilities.RestCallState
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.stickers.StickerUtils
import java.util.concurrent.TimeUnit
import okhttp3.MultipartBody
import okhttp3.RequestBody
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Drives [WidgetBugReport]: the report form, the feature-area picker behind it, and the
 * sending/sent status screen.
 *
 * The form fields are held as plain mutable properties and every setter republishes a fresh
 * [ViewState.Report]; that is what lets the picker screen come and go without losing what has
 * already been typed. `GET /private/bug-reports` is fired once from the constructor and its
 * [RestCallState] doubles as the picker's loading flag — [ViewState.SelectFeature.loadingFeatures]
 * is literally `bugReportConfig is Loading`, so the picker shows a spinner instead of an empty list.
 */
class BugReportViewModel(
    val screenshotUri: Uri,
    val screenshotName: String?,
    private val restApi: RestAPIInterface = RestAPI.api,
) : AppViewModel<BugReportViewModel.ViewState>() {

    sealed class Event {
        /** The success screen has been on show long enough; close the reporter. */
        object CloseReport : Event()
    }

    sealed class ViewState {
        data class Report(
            val screenshotUri: Uri,
            val reportName: String?,
            val reportDescription: String?,
            val priority: Int?,
            val feature: Feature?,
            val useScreenshot: Boolean = true,
            val error: RestApiError? = null,
        ) : ViewState()

        data class SelectFeature(
            val query: String? = null,
            val loadingFeatures: Boolean = true,
            val features: List<Feature> = emptyList(),
        ) : ViewState()

        data class Sending(val sendingSticker: Sticker?) : ViewState()

        data class Success(val successSticker: Sticker?) : ViewState()
    }

    private val eventSubject: PublishSubject<Event> = PublishSubject.create()
    private var bugReportConfig: RestCallState<BugReportConfig>? = null
    private var featureSearchQuery: String? = null

    var reportName: String? = null
        private set
    var reportDescription: String? = null
        private set
    var priority: Int? = null
        private set
    var feature: Feature? = null
        private set
    var useScreenshot: Boolean = true
        private set
    var stickersPrefetched: Boolean = false
        private set

    /**
     * Which of the five sticker pairs this report uses. The reference picks once per report so the
     * "sending" and "sent" art are the same character.
     */
    private val stickerCharacterIndex: Int = SENDING_STICKER_IDS.indices.random()

    val sendingSticker: Sticker? = stickerLookup(SENDING_STICKER_IDS[stickerCharacterIndex])
    val successSticker: Sticker? = stickerLookup(SUCCESS_STICKER_IDS[stickerCharacterIndex])

    init {
        loadFeatures()
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun loadFeatures() {
        restApi.getBugReportConfig().executeRequest { bugReportConfig = it }
    }

    /** Warms the sticker cache so the status screen does not pop in. No-op without a sticker store. */
    fun prefetchStickers(context: Context) {
        if (stickersPrefetched) return
        listOfNotNull(sendingSticker, successSticker).forEach { sticker ->
            StickerUtils.fetchSticker(context, sticker)
                .restSubscribeOn()
                .appSubscribe(errorClass = BugReportViewModel::class.java) { /* cache warm-up only */ }
        }
        stickersPrefetched = true
    }

    // region form state

    @MainThread
    fun updateReportName(name: String) {
        reportName = name
        showReport()
    }

    @MainThread
    fun updateReportDescription(description: String) {
        reportDescription = description
        showReport()
    }

    @MainThread
    fun updatePriority(priority: Int) {
        this.priority = priority
        showReport()
    }

    @MainThread
    fun useScreenshot(useScreenshot: Boolean) {
        this.useScreenshot = useScreenshot
        showReport()
    }

    fun selectFeatureArea(feature: Feature?) {
        this.feature = feature
        showReport()
    }

    fun showReport(error: RestApiError? = null) {
        updateViewState(
            ViewState.Report(
                screenshotUri = screenshotUri,
                reportName = reportName,
                reportDescription = reportDescription,
                priority = priority,
                feature = feature,
                useScreenshot = useScreenshot,
                error = error,
            ),
        )
    }

    // endregion

    // region feature picker

    fun filterFeatures(query: String) {
        featureSearchQuery = query
        showFeatureAreas()
    }

    fun showFeatureAreas() {
        updateViewState(
            ViewState.SelectFeature(
                query = featureSearchQuery,
                loadingFeatures = bugReportConfig is Loading,
                // Sorted by squad then name, because the adapter inserts a squad heading whenever
                // the squad changes and would otherwise repeat headings.
                features = filterFeatures(featureSearchQuery).sortedBy { it.squad + it.name },
            ),
        )
    }

    private fun filterFeatures(filter: String?): List<Feature> {
        val features = (bugReportConfig?.invoke()?.features ?: emptyList()) + GENERIC_FEATURE_AREAS
        if (filter == null) return features
        return features.filter {
            it.name.contains(filter, ignoreCase = true) || it.squad.contains(filter, ignoreCase = true)
        }
    }

    // endregion

    /**
     * Submits the report. Does nothing without a name and a priority — the screen's
     * `ValidationManager` is what normally stops the user getting here.
     */
    @MainThread
    fun sendReport(screenshotBody: RequestBody?) {
        val name = reportName ?: return
        val priority = priority ?: return
        val screenshotPart = screenshotBody?.let {
            MultipartBody.Part.createFormData("screenshot", screenshotName, it)
        }
        updateViewState(ViewState.Sending(sendingSticker))
        restApi.sendBugReport(name, reportDescription, priority, feature?.asanaInboxId, screenshotPart)
            .restSubscribeOn()
            .ui()
            .appSubscribe(
                errorClass = BugReportViewModel::class.java,
                // A failure returns to the form with the message shown above the submit button
                // rather than toasting and leaving the user on a dead status screen.
                errorHandler = ::showReport,
            ) {
                updateViewState(ViewState.Success(successSticker))
                dismissAfterDelay()
            }
    }

    private fun dismissAfterDelay() {
        Observable.timer(SUCCESS_DISMISS_DELAY_MS, TimeUnit.MILLISECONDS)
            .ui(this)
            .appSubscribe(errorClass = BugReportViewModel::class.java) {
                eventSubject.onNext(Event.CloseReport)
            }
    }

    companion object {
        private const val SUCCESS_DISMISS_DELAY_MS = 1_500L

        /** Always offered, even when the remote config could not be fetched. */
        private val GENERIC_FEATURE_AREAS = listOf(Feature("general", "Android", null))

        private val SENDING_STICKER_IDS = listOf(
            754107634172297306L,
            751606719611928586L,
            749049128012742676L,
            761114619137359892L,
            783788733987815434L,
        )

        private val SUCCESS_STICKER_IDS = listOf(
            754109076933443614L,
            751606065447305216L,
            749053689419006003L,
            761108384010862602L,
            783794979704537108L,
        )

        /**
         * These five sticker pairs come from Discord's own internal server, so they are only
         * present once the sticker packs have loaded; the status screen renders its text without
         * the art until then rather than blocking on it.
         */
        @JvmStatic
        var stickerLookup: (Long) -> Sticker? = { id -> StoreStream.getStickers().getStickers()[id] }
    }
}
