package com.discord.models.experiments.dto

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

data class UserExperimentDto(
    val nameHash: Long,
    val revision: Int,
    val bucket: Int,
    val population: Int,
) {
    object TypeAdapterFactory : com.google.gson.TypeAdapterFactory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> create(gson: Gson?, type: TypeToken<T>?): TypeAdapter<T>? =
            if (type?.rawType == UserExperimentDto::class.java) Adapter() as TypeAdapter<T> else null
    }

    class Adapter : TypeAdapter<UserExperimentDto>() {
        override fun read(reader: JsonReader): UserExperimentDto {
            reader.beginArray()
            val result = UserExperimentDto(reader.nextLong(), reader.nextInt(), reader.nextInt(), reader.nextInt())
            while (reader.hasNext()) reader.skipValue()
            reader.endArray()
            return result
        }

        override fun write(writer: JsonWriter, value: UserExperimentDto?) =
            throw UnsupportedOperationException()
    }
}

data class UnauthenticatedUserExperimentsDto(
    val fingerprint: String,
    val assignments: List<UserExperimentDto>,
    /** Minted server-side on first contact and reused thereafter; absent once this device has one. */
    val installation: String? = null,
)
