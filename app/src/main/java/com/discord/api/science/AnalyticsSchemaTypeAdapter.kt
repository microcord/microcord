package com.discord.api.science

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Serializes a schema-backed analytics event as `{ type, properties }`.
 *
 * The schema object is rendered with snake_case field names, then flattened: any nested object
 * has its own entries hoisted into the top-level property map, because the analytics pipeline
 * only accepts a flat key/value bag.
 */
class AnalyticsSchemaTypeAdapter : JsonSerializer<Science.Event.SchemaObject> {
    private val gson: Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    override fun serialize(
        src: Science.Event.SchemaObject,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        val flattened = src.schema.serializeToMap().flatMapProperties().toMap() +
            listOfNotNull(src.clientHeartbeatSessionId?.let { SESSION_ID_KEY to it })
        return gson.toJsonTree(Science.Event.MapObject(src.type, flattened), Science.Event.MapObject::class.java)
    }

    private fun AnalyticsSchema.serializeToMap(): Map<String, Any> =
        gson.fromJson(gson.toJson(this), object : TypeToken<Map<String, Any>>() {}.type)

    private companion object {
        const val SESSION_ID_KEY = "client_heartbeat_session_id"
    }

    private fun Map<String, Any>.flatMapProperties(): List<Pair<String, Any>> =
        entries.flatMap { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            (value as? Map<String, Any>)?.flatMapProperties() ?: listOf(key to value)
        }
}
