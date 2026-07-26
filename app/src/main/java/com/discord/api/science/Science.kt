package com.discord.api.science

/** Body of `POST science`: a batch of analytics events for one session. */
class Science(
    private val token: String?,
    private val events: List<Event>?,
) {
    /** One analytics event. */
    sealed class Event {
        /** The analytics event name. */
        abstract val type: String

        /**
         * An event whose properties are already a flat map — used by call sites that build
         * properties by hand rather than from a schema class.
         */
        data class MapObject(
            override val type: String,
            val properties: Map<String, Any>,
        ) : Event()

        /**
         * An event described by an [AnalyticsSchema] class. [AnalyticsSchemaTypeAdapter] flattens
         * the schema into properties at serialization time.
         */
        data class SchemaObject(
            val schema: AnalyticsSchema,
        ) : Event() {
            override val type: String = schema.analyticsSchemaTypeName
        }
    }

    override fun toString(): String = "Science(token=<redacted>, events=$events)"
}
