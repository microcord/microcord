package com.discord.widgets.chat.input.autocomplete

import com.discord.api.commands.CommandChoice

data class ApplicationCommandChoiceAutocompletable(
    val choice: CommandChoice,
    val optionName: String? = null,
) : Autocompletable() {
    private val textMatchers = listOf(choice.displayName, choice.name).distinct()

    override fun getInputReplacement() = choice.value.toString()
    override fun getInputTextMatchers() = textMatchers
}
