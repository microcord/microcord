package com.discord.widgets.nux

sealed class GuildTemplateViewType(val viewType: Int) {
    data class Template(val guildTemplate: GuildTemplate) : GuildTemplateViewType(TEMPLATE)

    object Text : GuildTemplateViewType(TEXT)

    companion object {
        const val TEXT: Int = 0
        const val TEMPLATE: Int = 1
    }
}
