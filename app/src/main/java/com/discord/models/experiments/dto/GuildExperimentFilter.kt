package com.discord.models.experiments.dto

import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildHubType
import com.discord.models.experiments.domain.ExperimentHash
import com.google.gson.JsonArray

sealed class GuildExperimentFilter {
    data class GuildHasFeatureFilter(val features: Set<GuildFeature>) : GuildExperimentFilter()
    data class GuildHubTypesFeatureFilter(val hubTypes: Set<GuildHubType>) : GuildExperimentFilter()
    data class GuildIdRangeFilter(val range: LongRange) : GuildExperimentFilter()
    data class GuildIdsFilter(val guildIds: Set<Long>) : GuildExperimentFilter()
    data class GuildMemberCountRangeFilter(val range: LongRange) : GuildExperimentFilter()

    companion object {
        private val guildIdsHash = ExperimentHash.from("guild_ids")
        private val guildIdRangeHash = ExperimentHash.from("guild_id_range")
        private val memberCountRangeHash = ExperimentHash.from("guild_member_count_range")
        private val hasFeatureHash = ExperimentHash.from("guild_has_feature")
        private val hubTypesHash = ExperimentHash.from("guild_hub_types")
        private val minIdHash = ExperimentHash.from("min_id")
        private val maxIdHash = ExperimentHash.from("max_id")

        internal fun parseAll(array: JsonArray): List<GuildExperimentFilter> = array.mapNotNull { item ->
            val encoded = item.asJsonArray
            val type = encoded[0].asLong
            val options = encoded[1].asJsonArray
            when (type) {
                guildIdsHash -> GuildIdsFilter(values(options).map { it.asLong }.toSet())
                guildIdRangeHash -> GuildIdRangeFilter(range(options))
                memberCountRangeHash -> GuildMemberCountRangeFilter(range(options))
                hasFeatureHash -> GuildHasFeatureFilter(
                    values(options).mapNotNull { value ->
                        runCatching { GuildFeature.valueOf(value.asString) }.getOrNull()
                    }.toSet(),
                )
                hubTypesHash -> GuildHubTypesFeatureFilter(
                    values(options).mapNotNull { value ->
                        GuildHubType.entries.firstOrNull { it.apiValue == value.asInt }
                    }.toSet(),
                )
                else -> null
            }
        }

        private fun values(options: JsonArray): JsonArray {
            if (options.size() == 0) return JsonArray()
            val first = options[0].asJsonArray
            return if (first.size() > 1) first[1].asJsonArray else JsonArray()
        }

        private fun range(options: JsonArray): LongRange {
            var minimum = 0L
            var maximum = Long.MAX_VALUE
            options.forEach { option ->
                val pair = option.asJsonArray
                when (pair[0].asLong) {
                    minIdHash -> minimum = pair[1].asLong
                    maxIdHash -> maximum = pair[1].asLong
                }
            }
            return minimum..maximum
        }
    }
}
