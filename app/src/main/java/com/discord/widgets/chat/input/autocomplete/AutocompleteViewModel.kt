package com.discord.widgets.chat.input.autocomplete

import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.discord.app.AppViewModel
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.AutocompleteStickerItem
import com.discord.widgets.chat.input.models.MentionToken
import rx.Observable
import rx.Subscription

/**
 * Owns the autocomplete popup's state for one composer.
 *
 * The model has exactly two inputs — what is typed ([onInputChanged]) and what the channel offers
 * ([observeAutocompletables]) — and one output, [AutocompleteViewState]. Keeping the reduction in
 * one pure function ([reduce]) is what makes it testable and is why it lives here rather than in
 * the fragment.
 *
 * Candidate sets are held as state rather than re-derived per keystroke: on a large guild the set
 * costs a permission computation per member, and it changes on presence updates, not on typing.
 */
class AutocompleteViewModel(
    private val channelId: Long?,
    private val autocompletablesSource: (Long) -> Observable<Map<LeadingIdentifier, Set<Autocompletable>>> =
        ChatInputAutocompletables::observeChannelAutocompletables,
    /**
     * Sticker suggestions for the current token. The reference sources these from `StoreStickers`
     * via `StickerUtils.findStickerMatches`; neither exists in this project, so the default offers
     * none and the sticker row stays hidden.
     */
    private val stickerSource: (MentionToken?) -> List<AutocompleteStickerItem> = { emptyList() },
) : AppViewModel<AutocompleteViewState>(AutocompleteViewState.Hidden) {

    private var autocompletables: Map<LeadingIdentifier, Set<Autocompletable>> = emptyMap()
    private var currentToken: MentionToken? = null
    private var subscription: Subscription? = null

    init {
        if (channelId != null) {
            subscription = autocompletablesSource(channelId)
                .ui(this)
                .subscribe({ onAutocompletablesChanged(it) }, { /* keep the last good set */ })
        }
    }

    /** The candidate set, exposed so the composer can resolve finished text into mention pills. */
    fun getAutocompletables(): Map<LeadingIdentifier, Set<Autocompletable>> = autocompletables

    @MainThread
    fun onAutocompletablesChanged(next: Map<LeadingIdentifier, Set<Autocompletable>>) {
        autocompletables = next
        publish()
    }

    /**
     * Called on every composer edit. [selection] is the caret, as a range so a selection (rather
     * than a caret) can be distinguished later; only [IntRange.first] is consulted today, matching
     * the reference.
     */
    @MainThread
    fun onInputChanged(input: CharSequence, selection: IntRange) {
        currentToken = AutocompleteModelUtils.getMessageAutocompleteToken(input, selection)
        publish()
    }

    /** Closes the popup, e.g. after a candidate is picked or the composer loses focus. */
    @MainThread
    fun onAutocompleteDismissed() {
        currentToken = null
        updateViewState(AutocompleteViewState.Hidden)
    }

    private fun publish() = updateViewState(reduce(currentToken, autocompletables, stickerSource))

    override fun onCleared() {
        subscription?.unsubscribe()
        subscription = null
        super.onCleared()
    }

    companion object {
        /**
         * The whole of the model's logic.
         *
         * `Hidden` is produced in two distinct situations that must not be conflated:
         *
         * * there is no token under the caret at all, and
         * * there is a token but nothing matches it *and* there are no sticker suggestions.
         *
         * The second is deliberate — showing an empty popup would steal a row of screen height and
         * swallow the next Enter keypress.
         */
        @VisibleForTesting
        fun reduce(
            token: MentionToken?,
            autocompletables: Map<LeadingIdentifier, Set<Autocompletable>>,
            stickerSource: (MentionToken?) -> List<AutocompleteStickerItem> = { emptyList() },
        ): AutocompleteViewState {
            if (token == null) return AutocompleteViewState.Hidden

            val matches = AutocompleteModelUtils.filterMentionsFromToken(
                token = token,
                mentions = autocompletables.mapValues { (_, value) -> value.toList() },
            )
            val stickers = stickerSource(token)
            if (matches.isEmpty() && stickers.isEmpty()) return AutocompleteViewState.Hidden

            return AutocompleteViewState.Autocomplete(
                isLoading = false,
                isError = false,
                isAutocomplete = true,
                autocompletables = matches,
                stickers = stickers,
                token = token.token,
            )
        }
    }
}
