package com.discord.widgets.chat.input.autocomplete

import com.discord.models.commands.ApplicationCommandOption
import com.lytefast.flexinput.model.Attachment

sealed class Event {
    data class PickAttachment(val commandOption: ApplicationCommandOption) : Event()
    data class PreviewAttachment(val attachment: Attachment<*>) : Event()
    data class RequestAutocompleteData(val option: ApplicationCommandOption) : Event()
    data class ScrollAutocompletablesToApplication(
        val applicationId: Long,
        val targetPosition: Int,
    ) : Event()
}
