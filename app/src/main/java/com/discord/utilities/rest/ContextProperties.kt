package com.discord.utilities.rest

import com.google.gson.GsonBuilder
import okio.ByteString.Companion.encodeUtf8

/** `X-Context-Properties` for an empty property set: base64 of `{}`. */
const val EMPTY_CONTEXT_PROPERTIES = "e30="

/**
 * Builds an `X-Context-Properties` value: base64 of the JSON object, keys in the order given.
 *
 * Null values are dropped rather than encoded as JSON `null`, matching `JSON.stringify` over the
 * reference's `undefined` fields. An all-null property list still yields [EMPTY_CONTEXT_PROPERTIES]
 * — the header is sent either way.
 */
fun contextPropertiesOf(vararg properties: Pair<String, Any?>): String =
    GSON.toJson(properties.toMap().filterValues { it != null }).encodeUtf8().base64()

/**
 * Gson and okio rather than `org.json` and `android.util.Base64`, both of which are stubbed out in
 * unit tests. HTML escaping off so the bytes match `JSON.stringify`.
 */
private val GSON = GsonBuilder().disableHtmlEscaping().create()
