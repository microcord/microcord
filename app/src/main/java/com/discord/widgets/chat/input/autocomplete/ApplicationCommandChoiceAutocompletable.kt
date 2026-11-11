package com.discord.widgets.chat.input.autocomplete

import com.discord.api.commands.CommandChoice

data class ApplicationCommandChoiceAutocompletable(
    val choice: CommandChoice,
    val optionName: String? = null,
) : Autocompletable() {
    private val textMatchers = listOf(choice.displayName, choice.name).distinct()

    // Picking a choice writes its value, not its name, into the composer. Without this the pick is
    // not found in the finished text, so an option whose choices differ from their values (what an
    // app returns when it loads them on request) was reported as not filled in.
    private val valueMatchers = listOf(choice.value.toString()).filter { it.isNotEmpty() && it !in textMatchers }

    override fun getInputReplacement() = choice.value.toString()
    override fun getInputTextMatchers() = textMatchers
    override fun getHiddenInputTextMatchers() = valueMatchers.takeIf { it.isNotEmpty() }
}
