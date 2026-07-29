package com.discord.widgets.chat.input.autocomplete

import com.discord.widgets.chat.input.AutocompleteStickerItem
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption

/**
 * What the autocomplete surface above the composer should show.
 *
 * Reference: `AutocompleteViewState`. The reference's fourth variant, `CommandBrowser`, is not
 * reconstructed; this slice covers typed command queries and selected-command options only.
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

sealed class SelectedCommandViewState {
    object Hidden : SelectedCommandViewState()

    data class SelectedCommand(
        val command: ApplicationCommand,
        val option: ApplicationCommandOption?,
        val application: Application,
    ) : SelectedCommandViewState()
}
