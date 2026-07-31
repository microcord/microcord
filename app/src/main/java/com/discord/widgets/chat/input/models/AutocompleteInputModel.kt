package com.discord.widgets.chat.input.models

import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.LeadingIdentifier

data class AutocompleteInputModel(
    val input: CharSequence,
    val autocompletables: Map<LeadingIdentifier, Set<Autocompletable>>,
    val applicationCommands: AutocompleteApplicationCommands,
)
