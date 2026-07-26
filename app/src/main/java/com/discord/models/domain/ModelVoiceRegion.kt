package com.discord.models.domain

/** One entry of `guilds/{id}/regions` / `voice/regions`. */
class ModelVoiceRegion : Model {
    var id: String? = null
        private set
    var name: String? = null
        private set
    var isOptimal: Boolean = false
        private set
    var samplePort: Int = 0
        private set
    var sampleHostname: String? = null
        private set
    var isDeprecated: Boolean = false
        private set
    var isHidden: Boolean = false
        private set

    override fun assignField(reader: Model.JsonReader) {
        when (reader.nextName()) {
            "id" -> id = reader.nextString(id)
            "name" -> name = reader.nextString(name)
            "optimal" -> isOptimal = reader.nextBoolean(isOptimal)
            "sample_port" -> samplePort = reader.nextInt(samplePort)
            "sample_hostname" -> sampleHostname = reader.nextString(sampleHostname)
            "deprecated" -> isDeprecated = reader.nextBoolean(isDeprecated)
            "hidden" -> isHidden = reader.nextBoolean(isHidden)
            else -> reader.skipValue()
        }
    }

    override fun equals(other: Any?): Boolean = other is ModelVoiceRegion &&
        id == other.id && name == other.name && isOptimal == other.isOptimal &&
        samplePort == other.samplePort && sampleHostname == other.sampleHostname &&
        isDeprecated == other.isDeprecated && isHidden == other.isHidden

    override fun hashCode(): Int = listOf(
        id, name, isOptimal, samplePort, sampleHostname, isDeprecated, isHidden,
    ).hashCode()

    override fun toString(): String = "ModelVoiceRegion(id=$id, name=$name, optimal=$isOptimal, " +
        "samplePort=$samplePort, sampleHostname=$sampleHostname, deprecated=$isDeprecated, " +
        "hidden=$isHidden)"
}
