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
