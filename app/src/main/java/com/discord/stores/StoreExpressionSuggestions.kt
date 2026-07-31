package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import rx.Observable

class StoreExpressionSuggestions @JvmOverloads constructor(
    private val observationDeck: ObservationDeck,
    private val dispatcher: Dispatcher,
    private val clock: Clock = ClockFactory.get(),
) : StoreV2() {
    private var lastSuggestionTrigger = ""
    private var lastTrackedExpressionSuggestionsDisplayed = 0L
    private var suggestionsEnabled = true

    fun getLastSuggestionTrigger(): String = lastSuggestionTrigger

    @StoreThread
    fun handleChannelSelected() {
        suggestionsEnabled = true
        markChanged()
    }

    fun observeSuggestionsEnabled(): Observable<Boolean> =
        observationDeck.connectRx(this, updateOnConnect = false) { suggestionsEnabled }.distinctUntilChanged()

    fun setExpressionSuggestionsEnabled(enable: Boolean) = dispatcher.schedule {
        suggestionsEnabled = enable
        markChanged()
    }

    @Synchronized
    fun trackExpressionSuggestionsDisplayed(suggestionTrigger: String) {
        val now = clock.currentTimeMillis()
        if (lastTrackedExpressionSuggestionsDisplayed + TRACKED_EXPRESSION_SUGGESTIONS_DISPLAYED_THROTTLE_WINDOW > now) return
        lastTrackedExpressionSuggestionsDisplayed = now
        AnalyticsTracker.expressionSuggestionsDisplayed(suggestionTrigger)
        lastSuggestionTrigger = suggestionTrigger
    }

    companion object {
        const val TRACKED_EXPRESSION_SUGGESTIONS_DISPLAYED_THROTTLE_WINDOW = 60_000L
    }
}
