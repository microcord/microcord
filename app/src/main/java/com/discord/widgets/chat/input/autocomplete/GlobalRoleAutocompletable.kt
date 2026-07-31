package com.discord.widgets.chat.input.autocomplete

data class GlobalRoleAutocompletable(val text: String) : Autocompletable() {
    private val textMatchers = listOf("${leadingIdentifier().identifier}$text")

    override fun getInputReplacement() = "${leadingIdentifier().identifier}$text"
    override fun getInputTextMatchers() = textMatchers
    override fun leadingIdentifier() = LeadingIdentifier.MENTION

    companion object {
        val everyone by lazy { GlobalRoleAutocompletable("everyone") }
        val here by lazy { GlobalRoleAutocompletable("here") }
    }
}
