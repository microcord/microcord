package com.discord.models.experiments.dto

import com.discord.models.experiments.domain.ExperimentHash
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class GuildExperimentFilterTest {
    @Test
    fun nullRangeBoundsRemainOpenEnded() {
        val range = ExperimentHash.from("guild_id_range")
        val min = ExperimentHash.from("min_id")
        val max = ExperimentHash.from("max_id")
        val json = JsonParser.parseString("""
            [$range,null,1,[[[[0,[{"s":0,"e":1}]]],[[$range,[[$min,null],[$max,null]]]]]]]
        """).asJsonArray

        val dto = GuildExperimentDtoTypeAdapter().fromJsonTree(json)
        val filter = dto.populations.single().filters.single() as GuildExperimentFilter.GuildIdRangeFilter

        assertEquals(0L..Long.MAX_VALUE, filter.range)
    }

    @Test
    fun objectOverridesParseAlongsideLegacyArrays() {
        val json = JsonParser.parseString("""
            [1,null,1,[],[{"b":2,"k":["10","11"]},[3,[12]]]]
        """).asJsonArray

        val overrides = GuildExperimentDtoTypeAdapter().fromJsonTree(json).overrides

        assertEquals(GuildExperimentOverridesDto(2, listOf(10, 11)), overrides[0])
        assertEquals(GuildExperimentOverridesDto(3, listOf(12)), overrides[1])
    }
}
