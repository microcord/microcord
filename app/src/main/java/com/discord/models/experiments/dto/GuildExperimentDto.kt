package com.discord.models.experiments.dto

import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

@JsonAdapter(GuildExperimentDtoTypeAdapter::class)
data class GuildExperimentDto(
    val experimentIdHash: Long,
    val hashKey: String?,
    val revision: Int,
    val populations: List<GuildExperimentPopulationDto>,
    val overrides: List<GuildExperimentOverridesDto>,
)

data class GuildExperimentPopulationDto(
    val buckets: List<GuildExperimentBucketDto>,
    val filters: List<GuildExperimentFilter>,
)

data class GuildExperimentBucketDto(
    val bucket: Int,
    val positions: List<IntRange>,
)

data class GuildExperimentOverridesDto(
    val bucket: Int,
    val guilds: List<Long>,
)

class GuildExperimentDtoTypeAdapter : TypeAdapter<GuildExperimentDto>() {
    override fun read(reader: JsonReader): GuildExperimentDto {
        val root = JsonParser.parseReader(reader).asJsonArray
        val populations = root[3].asJsonArray.map { populationElement ->
            val population = populationElement.asJsonArray
            GuildExperimentPopulationDto(
                buckets = population[0].asJsonArray.map { bucketElement ->
                    val bucket = bucketElement.asJsonArray
                    GuildExperimentBucketDto(
                        bucket[0].asInt,
                        bucket[1].asJsonArray.map { position ->
                            val value = position.asJsonObject
                            value["s"].asInt..value["e"].asInt
                        },
                    )
                },
                filters = if (population.size() > 1) {
                    GuildExperimentFilter.parseAll(population[1].asJsonArray)
                } else {
                    emptyList()
                },
            )
        }
        val overrides = if (root.size() > 4) {
            root[4].asJsonArray.map { overrideElement ->
                val override = overrideElement.asJsonArray
                GuildExperimentOverridesDto(override[0].asInt, override[1].asJsonArray.map { it.asLong })
            }
        } else {
            emptyList()
        }
        return GuildExperimentDto(
            root[0].asLong,
            root[1].takeUnless { it.isJsonNull }?.asString,
            root[2].asInt,
            populations,
            overrides,
        )
    }

    override fun write(writer: JsonWriter, value: GuildExperimentDto?) =
        throw UnsupportedOperationException()
}
