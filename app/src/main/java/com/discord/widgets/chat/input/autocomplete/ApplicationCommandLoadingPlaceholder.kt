package com.discord.widgets.chat.input.autocomplete

import com.discord.models.commands.Application

data class ApplicationCommandLoadingPlaceholder(
    val application: Application? = null,
) : Autocompletable() {
    override fun getInputReplacement() = ""
    override fun getInputTextMatchers(): List<String> = emptyList()
}
