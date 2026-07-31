package com.discord.restapi

import com.discord.api.creatormonetization.CreatorMonetizationEnableRequest
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CreatorMonetizationRestContractTest {
    private val server = MockWebServer()
    private lateinit var api: RestAPIInterface

    @Before fun setUp() {
        server.start()
        api = RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
            .build(RestAPIInterface::class.java)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `creator monetization requests use the 126 21 routes`() {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "guild_id":"42","latest_request":null,"can_apply":true,"sufficient":true,
                    "age":true,"engagement_healthy":true,"nsfw_properties":{},
                    "retention_healthy":true,"safe_environment":true,"size":true,
                    "health_score":null,"health_score_pending":false,"rejection":null,
                    "minimum_age_in_days":30,"minimum_size":100
                }""".trimIndent(),
            ),
        )
        assertEquals(42L, api.getCreatorMonetizationEligibilityRequirements(42).toBlocking().first().guildId)
        assertEquals("/api/v9/guilds/42/creator-monetization/requirements", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"id":"99","state":"OPEN"}"""))
        assertEquals(
            CreatorMonetizationEnableRequest.State.OPEN,
            api.createCreatorMonetizationEnableRequest(42).toBlocking().first().state,
        )
        assertEquals("/api/v9/guilds/42/creator-monetization/enable-requests", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"id":"99","state":"APPROVED"}"""))
        api.acceptCreatorMonetizationTerms(42, 99).toBlocking().first()
        assertEquals(
            "/api/v9/guilds/42/creator-monetization/99/accept-terms",
            server.takeRequest().path,
        )
    }
}
