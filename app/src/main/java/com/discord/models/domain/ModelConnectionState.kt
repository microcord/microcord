package com.discord.models.domain

/** OAuth state handed back by `connections/{provider}/authorize`. */
class ModelConnectionState : Model {
    var code: String? = null
        private set
    var state: String? = null
        private set
    var providerId: String? = null
        private set

    override fun assignField(reader: Model.JsonReader) {
        when (reader.nextName()) {
            "code" -> code = reader.nextString("")
            "state" -> state = reader.nextString("")
            "provider_id" -> providerId = reader.nextString("")
            else -> reader.skipValue()
        }
    }

    override fun equals(other: Any?): Boolean = other is ModelConnectionState &&
        code == other.code && state == other.state && providerId == other.providerId

    override fun hashCode(): Int = listOf(code, state, providerId).hashCode()

    override fun toString(): String =
        "ModelConnectionState(code=<redacted>, state=<redacted>, providerId=$providerId)"
}
