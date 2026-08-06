package com.discord.utilities.rest

import android.util.Base64
import org.json.JSONObject

fun contextPropertiesOf(vararg properties: Pair<String, Any?>): String {
    val json = JSONObject(properties.toMap())
    return Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
