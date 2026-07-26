package com.discord.widgets.guild_automod

import androidx.annotation.MainThread
import com.discord.api.message.embed.MessageEmbed
import com.discord.app.AppViewModel
import com.discord.models.message.Message
import com.discord.stores.StoreMessages
import com.discord.stores.StoreStream
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * Drives [WidgetReportIssueWithAutoMod] — "AutoMod blocked this and it shouldn't have".
 *
 * The whole screen hangs off one blocked message, which the store may not have (it is ephemeral
 * and does not survive a channel switch); [ViewState.Invalid] is that case and leaves the radio
 * group unbound so nothing can be submitted about a message we no longer hold.
 *
 * Submitting produces no REST call at all in the reference — the report is an analytics event
 * carrying the AutoMod `decision_id` lifted out of the block embed. That tracker does not exist in
 * this project yet, so the payload is assembled and handed to [reportSink]. **The submit button is
 * therefore inert until a tracker is installed**; see the handoff report.
 */
class ReportIssueWithAutoModViewModel(
    private val channelId: Long,
    private val messageId: Long,
    messageStore: StoreMessages = StoreStream.getMessages(),
    storeStateObservable: Observable<StoreState> = observeStores(channelId, messageId, messageStore),
) : AppViewModel<ReportIssueWithAutoModViewModel.ViewState>() {

    data class StoreState(val message: Message?)

    sealed class ViewState {
        object Invalid : ViewState()

        data class Valid(
            val message: Message,
            val selectedOption: String,
        ) : ViewState()
    }

    /** What a completed report carries: AutoMod decision id, message id, feedback type, content. */
    data class Report(
        val decisionId: String,
        val messageId: Long,
        val feedbackType: String,
        val messageContent: String,
    )

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .subscribe(::handleStoreState)
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        val previous = viewState as? ViewState.Valid
        val message = storeState.message
        updateViewState(
            if (message == null) {
                ViewState.Invalid
            } else {
                ViewState.Valid(
                    message = message,
                    selectedOption = previous?.selectedOption ?: FeedbackType.ALLOWED.value,
                )
            },
        )
    }

    fun onFeedbackTypeSelected(feedbackType: FeedbackType) {
        val current = viewState
        if (current is ViewState.Valid) {
            updateViewState(current.copy(selectedOption = feedbackType.value))
        }
    }

    fun onSubmit(onCompleted: () -> Unit) {
        val current = viewState as? ViewState.Valid ?: return
        val embed: MessageEmbed? = current.message.embeds?.firstOrNull()
        reportSink(
            Report(
                decisionId = embed?.embedFieldValue(DECISION_ID_FIELD).orEmpty(),
                messageId = current.message.id,
                feedbackType = current.selectedOption,
                messageContent = current.message.content.orEmpty(),
            ),
        )
        onCompleted()
    }

    companion object {
        private const val DECISION_ID_FIELD = "decision_id"

        /**
         * Reference: `AnalyticsTracker.trackReportIssueWithAutoMod`.
         * `com.discord.utilities.analytics.AnalyticsTracker` is not reconstructed, so the assembled
         * report is dropped here instead of being invented into some other transport.
         */
        @JvmStatic
        var reportSink: (Report) -> Unit = { }

        fun observeStores(
            channelId: Long,
            messageId: Long,
            messageStore: StoreMessages,
        ): Observable<StoreState> =
            messageStore.observeMessagesForChannel(channelId, messageId).map(::StoreState)

        /**
         * Reference `com.discord.utilities.guildautomod.AutoModUtils.getEmbedFieldValue`. That
         * utility package does not exist in this project; **move this there when it lands.**
         */
        private fun MessageEmbed.embedFieldValue(name: String): String =
            fields?.firstOrNull { it.name == name }?.value ?: ""
    }
}
