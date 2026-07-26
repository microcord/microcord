package com.discord.models.deserialization.gson

import com.discord.models.domain.Model
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import java.lang.reflect.Modifier

/**
 * The single Gson instance used to decode gateway traffic.
 *
 * Static fields are excluded but transient ones are not: several `api` DTOs carry `transient`
 * properties (thread member counts, guild hashes) that the gateway really does send.
 */
object InboundGatewayGsonParser {

    @JvmStatic
    val gatewayGsonInstance: Gson = GsonBuilder()
        .excludeFieldsWithModifiers(Modifier.STATIC)
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    /** Decodes the value the streaming [reader] is positioned on, without buffering the document. */
    @JvmStatic
    fun <T> fromJson(reader: Model.JsonReader, clazz: Class<T>): T = fromJson(reader.`in`, clazz)

    @JvmStatic
    fun <T> fromJson(reader: JsonReader, clazz: Class<T>): T = gatewayGsonInstance.fromJson(reader, clazz)

    @JvmStatic
    fun <T> toJson(source: T): String = gatewayGsonInstance.toJson(source)
}
