package com.discord.models.commands

import com.discord.api.commands.ApplicationCommandAttachment
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.ApplicationCommandIndexCommand
import com.discord.restapi.RestAPIParams
import com.discord.widgets.chat.input.models.ApplicationCommandValue

/**
 * Everything needed to send — and, after a process death, to *re-*send — one slash-command
 * invocation.
 *
 * [com.discord.stores.StoreApplicationInteractions] persists a map of these as JSON, keyed by
 * [nonceString], which is why the whole thing is a plain serialisable data class with no live
 * references in it. [interactionId] is filled in once `INTERACTION_CREATE` acknowledges the send;
 * an entry that still has `null` there when the socket reconnects is treated as failed.
 *
 * [guildId] is the guild the command was *sent in*; [commandGuildId] is the guild the command is
 * *registered to*. They differ for a global command used inside a guild, and the wire body wants
 * the latter.
 */
data class ApplicationCommandLocalSendData(
    val nonce: Long,
    val channelId: Long,
    val guildId: Long?,
    val commandGuildId: Long?,
    val application: Application,
    val sessionId: String?,
    val applicationCommandName: String,
    val applicationCommandId: String,
    val applicationCommandsValues: List<ApplicationCommandValue>,
    val version: String?,
    val interactionId: Long? = null,
    val applicationCommand: ApplicationCommandIndexCommand? = null,
    val targetId: Long? = null,
) {
    val nonceString: String get() = nonce.toString()

    /**
     * Projects the composer's argument tree onto the wire shape.
     *
     * Attachment options are rewritten as they go: the value the composer holds is a local file
     * reference, but the API expects the option's value to be the *index* of the matching entry in
     * [com.discord.api.commands.ApplicationCommandData.attachments]. The counter therefore has to
     * advance in the same traversal order that builds the attachment list — which is why both use
     * `flatten()` rather than the nested tree.
     */
    fun toRestApiApplicationCommandDataParam(): com.discord.api.commands.ApplicationCommandData {
        var attachmentCounter = 0
        val options = applicationCommandsValues.toRestParams { value ->
            if (value.type == ApplicationCommandType.ATTACHMENT.type) {
                value.copy(value = attachmentCounter++)
            } else {
                value
            }
        }
        val attachments = applicationCommandsValues.flatten()
            .filter { it.type == ApplicationCommandType.ATTACHMENT.type }
            .mapIndexed { index, value ->
                ApplicationCommandAttachment(
                    filename = value.value.toString(),
                    id = index.toString(),
                )
            }
        return com.discord.api.commands.ApplicationCommandData(
            version = version,
            guildId = commandGuildId?.toString(),
            id = applicationCommandId,
            name = applicationCommandName,
            options = options,
            attachments = attachments,
            type = applicationCommand?.type ?: ApplicationCommandIndexCommand.TYPE_CHAT_INPUT,
            applicationCommand = applicationCommand,
            targetId = targetId?.toString(),
        )
    }

    fun toRestApiParam(): RestAPIParams.ApplicationCommand = RestAPIParams.ApplicationCommand(
        type = TYPE_APPLICATION_COMMAND,
        channelId = channelId.toString(),
        applicationId = application.id.toString(),
        guildId = guildId?.toString(),
        data = toRestApiApplicationCommandDataParam(),
        sessionId = sessionId,
        nonce = nonceString,
        analyticsLocation = "slash_ui",
    )

    override fun toString(): String = "ApplicationCommandLocalSendData(nonce=$nonce, " +
        "channelId=$channelId, guildId=$guildId, commandGuildId=$commandGuildId, " +
        "application=${application.id}, applicationCommandName=$applicationCommandName, " +
        "applicationCommandId=$applicationCommandId, version=$version, interactionId=$interactionId)"

    private companion object {
        /** `StoreApplicationCommands.TYPE_APPLICATION_COMMAND`. */
        const val TYPE_APPLICATION_COMMAND = 2L
    }
}

/** Recursive composer→wire projection of one argument, applying [mappingFunction] to each leaf. */
fun ApplicationCommandValue.toRestParam(
    mappingFunction: (com.discord.api.commands.ApplicationCommandValue) -> com.discord.api.commands.ApplicationCommandValue,
): com.discord.api.commands.ApplicationCommandValue = com.discord.api.commands.ApplicationCommandValue(
    name = name,
    value = value,
    type = type,
    options = options?.toRestParams(mappingFunction),
    focused = focused,
)

fun List<ApplicationCommandValue>.toRestParams(
    mappingFunction: (com.discord.api.commands.ApplicationCommandValue) -> com.discord.api.commands.ApplicationCommandValue,
): List<com.discord.api.commands.ApplicationCommandValue> =
    map { mappingFunction(it.toRestParam(mappingFunction)) }

/**
 * Flattens one level of sub-command nesting, keeping the container in place.
 *
 * A `SUBCOMMAND` contributes its own options; a `SUBCOMMAND_GROUP` contributes the options of each
 * sub-command it holds. Anything deeper does not exist — the API caps command nesting at two
 * levels.
 */
fun List<ApplicationCommandValue>.flatten(): List<ApplicationCommandValue> {
    val flattened = ArrayList<ApplicationCommandValue>()
    forEach { value ->
        flattened.add(value)
        when (value.type) {
            ApplicationCommandType.SUBCOMMAND.type ->
                flattened.addAll(value.options.orEmpty())

            ApplicationCommandType.SUBCOMMAND_GROUP.type ->
                value.options.orEmpty().forEach { flattened.addAll(it.options.orEmpty()) }
        }
    }
    return flattened
}
