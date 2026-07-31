package com.discord.widgets.chat.input.models

import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.stores.CommandAutocompleteState
import com.discord.widgets.chat.input.WidgetChatInputDiscoveryCommandsModel
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.LeadingIdentifier

sealed class MentionInputModel private constructor(
    open val input: CharSequence,
    open val autocompletables: Map<LeadingIdentifier, Set<Autocompletable>>,
    open val inputMentionsMap: Map<IntRange, Autocompletable>,
) {
    class VerifiedCommandInputModel(
        override val input: CharSequence,
        override val autocompletables: Map<LeadingIdentifier, Set<Autocompletable>>,
        override val inputMentionsMap: Map<IntRange, Autocompletable>,
        val applications: List<Application> = emptyList(),
        val queryCommands: List<ApplicationCommand> = emptyList(),
        val discoveryCommands: WidgetChatInputDiscoveryCommandsModel? = null,
        val commandOptionAutocompleteItems: Map<String, Map<String, CommandAutocompleteState>> = emptyMap(),
        val inputCommandContext: InputCommandContext,
        val commands: AutocompleteApplicationCommands,
        val inputCommandOptionValues: Map<ApplicationCommandOption, CommandOptionValue> = emptyMap(),
        val inputCommandOptionsRanges: Map<ApplicationCommandOption, OptionRange> = emptyMap(),
        val inputCommandOptionValidity: Map<ApplicationCommandOption, Boolean> = emptyMap(),
    ) : MentionInputModel(input, autocompletables, inputMentionsMap)

    data class VerifiedMessageInputModel(
        override val input: CharSequence,
        override val autocompletables: Map<LeadingIdentifier, Set<Autocompletable>>,
        override val inputMentionsMap: Map<IntRange, Autocompletable>,
    ) : MentionInputModel(input, autocompletables, inputMentionsMap)
}
