package com.discord.widgets.chat.input.autocomplete

import com.discord.models.commands.Application

data class ApplicationPlaceholder(val application: Application) : Autocompletable() {
    override fun getInputReplacement() = ""
    override fun getInputTextMatchers(): List<String> = emptyList()
}
