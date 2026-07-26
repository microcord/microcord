package com.discord.widgets.chat.input.autocomplete

import com.discord.widgets.chat.input.AutocompleteStickerItem

/**
 * What the autocomplete surface above the composer should show.
 *
 * Reference: `AutocompleteViewState`. The reference's fourth variant, `CommandBrowser`, is not
 * reconstructed — it renders the application category rail and the "discover commands" model,
 * both of which need `StoreApplicationCommands` and `WidgetChatInputDiscoveryCommandsModel`. Those
 * do not exist in this project yet; see the handoff notes.
 */
sealed class AutocompleteViewState {

    /** Nothing to show: no token under the caret, or the token matched nothing worth offering. */
    object Hidden : AutocompleteViewState()

    /**
     * The candidate list.
     *
     * [isAutocomplete] distinguishes "the user is actively completing a token" from "we are
     * showing the browser"; [token] is carried so the view can highlight the matched substring
     * and so a late emission for a stale query can be discarded.
     */
    data class Autocomplete(
        val isLoading: Boolean = false,
        val isError: Boolean = false,
        val isAutocomplete: Boolean = false,
        val autocompletables: List<Autocompletable>,
        val stickers: List<AutocompleteStickerItem>,
        val token: String,
    ) : AutocompleteViewState()

    /** Skeleton rows while an application's commands are being fetched. */
    data class LoadingItems(val numberLoadingItems: Int) : AutocompleteViewState()
}
