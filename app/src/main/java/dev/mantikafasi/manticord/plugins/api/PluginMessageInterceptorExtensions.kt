package dev.mantikafasi.manticord.plugins.api

import com.discord.models.message.Message

internal data class HostMessageInterceptor(
    override val id: String,
    override val types: Set<PluginMessageInterceptionType>,
    override val priority: Int,
    val transform: (Message) -> Message,
) : PluginMessageInterceptor

/**
 * Intercepts Manticord's real message model. Returning the original instance makes this a no-op;
 * use [Message.copy] to change content, embeds, attachments, stickers, mentions, flags, or components.
 */
fun pluginMessageInterceptor(
    id: String,
    vararg types: PluginMessageInterceptionType,
    transform: (Message) -> Message,
    configure: PluginMessageInterceptorBuilder.() -> Unit = {},
): PluginMessageInterceptor {
    require(id.isNotBlank()) { "message interceptor ID must not be blank" }
    require(types.isNotEmpty()) { "message interceptor must target send, edit, and/or receive" }
    val builder = PluginMessageInterceptorBuilder().apply(configure)
    return HostMessageInterceptor(id, types.toSet(), builder.priority, transform)
}

internal data class HostMessageDeleteInterceptor(
    override val id: String,
    override val priority: Int,
    val onDelete: (Message) -> Message?,
) : PluginMessageInterceptor {
    override val types: Set<PluginMessageInterceptionType> get() = emptySet()
}

internal data class HostMessageUpdateInterceptor(
    override val id: String,
    override val priority: Int,
    val onUpdate: (Message, Message) -> Message,
) : PluginMessageInterceptor {
    override val types: Set<PluginMessageInterceptionType> get() = emptySet()
}

/**
 * Runs on the store thread when the Gateway deletes a message this client has loaded, before it is
 * removed. Return null to let the delete happen, or a message to keep in its place in the chat;
 * other stores (pins, replies, mentions) still drop it. Deletes this client makes itself are not
 * intercepted, and a kept message is gone once the channel's messages are reloaded from the server.
 * Keep it cheap: it runs inside the message store.
 */
fun pluginMessageDeleteInterceptor(
    id: String,
    onDelete: (Message) -> Message?,
    configure: PluginMessageInterceptorBuilder.() -> Unit = {},
): PluginMessageInterceptor {
    require(id.isNotBlank()) { "message interceptor ID must not be blank" }
    return HostMessageDeleteInterceptor(id, PluginMessageInterceptorBuilder().apply(configure).priority, onDelete)
}

/**
 * Runs on the store thread for a Gateway update to a message this client has loaded, with the
 * version before the update and the merged result. Return the message to store; the merged one is
 * a no-op. Updates also arrive when embeds resolve, not only for edits, so compare [Message.content] or [Message.editedTimestamp] to find edits.
 */
fun pluginMessageUpdateInterceptor(
    id: String,
    onUpdate: (old: Message, new: Message) -> Message,
    configure: PluginMessageInterceptorBuilder.() -> Unit = {},
): PluginMessageInterceptor {
    require(id.isNotBlank()) { "message interceptor ID must not be blank" }
    return HostMessageUpdateInterceptor(id, PluginMessageInterceptorBuilder().apply(configure).priority, onUpdate)
}
