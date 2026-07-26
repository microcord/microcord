package com.discord.models.domain

import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.user.User
import com.discord.models.deserialization.gson.InboundGatewayGsonParser

/**
 * One page of `guilds/{id}/audit-logs`.
 *
 * The entries reference users, webhooks, integrations, threads and scheduled events by id only;
 * the server ships the referenced objects alongside them so the log can be rendered without any
 * further requests.
 */
class ModelAuditLog : Model {

    var users: List<User>? = null
        private set
    var webhooks: List<ModelWebhook>? = null
        private set
    var integrations: List<ModelGuildIntegration>? = null
        private set
    var auditLogEntries: List<ModelAuditLogEntry>? = null
        private set
    var guildScheduledEvents: List<GuildScheduledEvent>? = null
        private set
    var threads: List<Channel>? = null
        private set

    override fun assignField(reader: Model.JsonReader) {
        when (reader.nextName()) {
            "users" -> users = reader.nextList {
                InboundGatewayGsonParser.fromJson(reader, User::class.java)
            }
            "webhooks" -> webhooks = reader.nextList { reader.parse(ModelWebhook()) }
            "integrations" -> integrations = reader.nextList {
                InboundGatewayGsonParser.fromJson(reader, ModelGuildIntegration::class.java)
            }
            "audit_log_entries" -> auditLogEntries =
                reader.nextList { reader.parse(ModelAuditLogEntry()) }
            "guild_scheduled_events" -> guildScheduledEvents = reader.nextList {
                InboundGatewayGsonParser.fromJson(reader, GuildScheduledEvent::class.java)
            }
            "threads" -> threads = reader.nextList {
                InboundGatewayGsonParser.fromJson(reader, Channel::class.java)
            }
            else -> reader.skipValue()
        }
    }

    override fun equals(other: Any?): Boolean = other is ModelAuditLog &&
        users == other.users && webhooks == other.webhooks && integrations == other.integrations &&
        auditLogEntries == other.auditLogEntries &&
        guildScheduledEvents == other.guildScheduledEvents && threads == other.threads

    override fun hashCode(): Int = listOf(
        users, webhooks, integrations, auditLogEntries, guildScheduledEvents, threads,
    ).hashCode()

    override fun toString(): String = "ModelAuditLog(users=$users, webhooks=$webhooks, " +
        "integrations=$integrations, auditLogEntries=$auditLogEntries, " +
        "guildScheduledEvents=$guildScheduledEvents, threads=$threads)"
}
