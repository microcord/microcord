package com.discord.widgets.chat.input.autocomplete

import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.discord.app.AppViewModel
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.AutocompleteStickerItem
import com.discord.widgets.chat.input.models.ChatInputMentionsMap
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
    channelId: Long? = null,
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

    /**
     * Where every recognised mention currently sits in the composer.
     *
     * Recomputed from scratch on each edit rather than shifted, because
     * [AutocompleteModelUtils.mapInputToMentions] re-scans the text anyway and only consults the
     * previous map to (a) keep a picked candidate alive after its source left the store and
     * (b) preserve the exact candidate the user chose for an unchanged range.
     */
    private var inputMentionsMap: ChatInputMentionsMap = ChatInputMentionsMap("", emptyMap())

    private var currentChannelId: Long? = null

    init {
        setChannelId(channelId)
    }

    /**
     * Points the model at a different channel.
     *
     * The reference scopes one `AutocompleteViewModel` to the activity and lets the channel arrive
     * through `StoreChannelsSelected`; here the composer owns the model and re-targets it, which
     * keeps the popup alive across a channel switch instead of tearing the whole controller down.
     * Re-targeting clears the candidate set immediately so the popup can never offer a member of
     * the channel the user just left.
     */
    @MainThread
    fun setChannelId(channelId: Long?) {
        if (channelId == currentChannelId) return
        currentChannelId = channelId
        subscription?.unsubscribe()
        subscription = null
        autocompletables = emptyMap()
        currentToken = null
        inputMentionsMap = ChatInputMentionsMap("", emptyMap())
        updateViewState(AutocompleteViewState.Hidden)
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
        val text = input.toString()
        currentToken = AutocompleteModelUtils.getMessageAutocompleteToken(input, selection)
        inputMentionsMap = AutocompleteModelUtils.mapInputToMentions(
            input = text,
            autocompletables = autocompletables,
            currentInputMentionMap = if (inputMentionsMap.input == text) inputMentionsMap.mentions else emptyMap(),
            isCommand = currentToken?.let { it.isAtStart && it.leadingIdentifier == LeadingIdentifier.APP_COMMAND } == true,
        )
        publish()
    }

    /**
     * The composer text as the API should receive it: every recognised mention swapped for its
     * server form.
     *
     * Falls back to returning [input] unchanged when the map was built from different text, which
     * happens if the user types while the send is in flight. Sending the literal `@name` is a far
     * smaller failure than splicing a mention into the wrong offset.
     *
     * Reference: `AutocompleteViewModel.replaceAutocompletableDataWithServerValues`, which returns
     * a `MessageContent` carrying the mentioned users alongside the text. `MessageContent` is not
     * reconstructed in this project — the send pipeline takes a plain string — so only the text
     * half is returned.
     */
    fun replaceAutocompletableDataWithServerValues(input: String): String =
        if (inputMentionsMap.input != input) input
        else input.replaceAutocompleteDataWithServerValues(inputMentionsMap.mentions)

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
