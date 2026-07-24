package com.discord.models.domain

import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.api.user.UserSurvey
import com.discord.models.experiments.domain.ExperimentHash
import com.discord.models.experiments.dto.GuildExperimentDto
import com.discord.models.experiments.dto.UserExperimentDto
import com.discord.utilities.time.Clock
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyModelClosureTest {
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(UserExperimentDto.TypeAdapterFactory)
        .create()

    @Test
    fun parsesCompleteReadyWrappersAndRedactsTokens() {
        val fixture = requireNotNull(javaClass.classLoader?.getResource("ready/ready-envelope.json")).readText()
        val payload = gson.fromJson(fixture, ModelPayload::class.java)

        assertEquals(9, payload.v)
        assertEquals(7, payload.readState?.version)
        assertTrue(payload.readState?.isPartial == true)
        assertEquals(ModelReadState(100, 200, 2, 4), payload.readState?.entries?.single())
        assertEquals(5, payload.userGuildSettings?.version)
        assertTrue(payload.userGuildSettings?.entries?.single()?.isMuted == true)
        assertEquals(16384, payload.userGuildSettings?.entries?.single()
            ?.channelOverrides?.single()?.getForumPostNotificationSetting())
        assertEquals(UserExperimentDto(111, 2, 3, 4), payload.experiments?.get(111))
        assertEquals(8, payload.guildExperiments?.single()?.populations?.single()?.buckets?.single()?.bucket)
        assertEquals(listOf(emptyList<com.discord.api.presence.Presence>()), payload.guildPresences)
        assertEquals("android", payload.sessions?.single()?.clientInfo?.os)
        assertEquals(listOf(1, null, 10), payload.userSurvey?.guildSize)
        assertFalse(payload.toString().contains("analytics-secret"))
        assertFalse(payload.toString().contains("auth-secret"))
    }

    @Test
    fun factoriesAndValueBehaviorMatchReferenceContracts() {
        val userExperiment = gson.fromJson("[12,3,4,5]", UserExperimentDto::class.java)
        assertEquals(UserExperimentDto(12, 3, 4, 5), userExperiment)

        val guildExperiment = gson.fromJson(
            "[22,null,1,[[[[2,[{\"s\":10,\"e\":20}]]],[]]],[]]",
            GuildExperimentDto::class.java,
        )
        assertEquals(10..20, guildExperiment.populations.single().buckets.single().positions.single())
        assertEquals(ExperimentHash.from("guild_ids"), ExperimentHash.from("guild_ids"))

        val request = gson.fromJson(
            "{\"user_id\":1,\"user\":null,\"guild_id\":2,\"created_at\":\"now\"," +
                "\"application_status\":\"SUBMITTED\",\"rejection_reason\":null," +
                "\"invite_code\":null,\"last_seen\":null,\"actioned_at\":null}",
            GuildJoinRequest::class.java,
        )
        assertEquals(ApplicationStatus.PENDING, request.applicationStatus)

        val survey = gson.fromJson(
            "{\"prompt\":\"p\",\"cta\":\"c\",\"url\":\"u\",\"key\":\"k\"," +
                "\"guild_requirements\":[],\"guild_size\":[null],\"guild_permissions\":[]}",
            UserSurvey::class.java,
        )
        assertEquals(listOf<Int?>(null), survey.guildSize)
    }

    @Test
    fun muteExpiryAndPayloadCopiesAreDeterministic() {
        val future = ModelNotificationSettings(
            muted = true,
            muteConfig = ModelMuteConfig(2_000L),
        ).copyAndRecomputeTransientFields(Clock { 1_000L })
        val expired = future.copyAndRecomputeTransientFields(Clock { 3_000L })
        assertTrue(future.isMuted)
        assertFalse(expired.isMuted)
        assertEquals(2_000L, future.getNextMuteEndTimeMs(Clock { 1_000L }))
        assertEquals(Long.MAX_VALUE, future.getNextMuteEndTimeMs(Clock { 3_000L }))

        val original = ModelPayload()
        val copy = original.withGuilds(emptyList())
        assertNull(original.guilds)
        assertEquals(emptyList<com.discord.api.guild.Guild>(), copy.guilds)
    }
}
