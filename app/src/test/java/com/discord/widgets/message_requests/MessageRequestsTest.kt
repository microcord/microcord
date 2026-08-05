package com.discord.widgets.message_requests

import com.discord.api.channel.Channel
import com.discord.api.channel.isMessageRequestChannel
import com.discord.api.channel.isSpamMessageRequestChannel
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.PUT

class MessageRequestsTest {
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()

    @Test fun `channel request fields deserialize from gateway names`() {
        val channel = gson.fromJson(
            """{"id":1,"type":1,"is_message_request":true,"is_message_request_timestamp":"2026-08-05T00:00:00Z","is_spam":true}""",
            Channel::class.java,
        )

        assertTrue(channel.isSpamMessageRequestChannel())
        assertEquals("2026-08-05T00:00:00Z", channel.isMessageRequestTimestamp)
        assertTrue(!channel.isMessageRequestChannel())
    }

    @Test fun `actions use the current recipient consent routes`() {
        val update = RestAPIInterface::class.java.methods.single { it.name == "updateMessageRequest" }
        val reject = RestAPIInterface::class.java.methods.single { it.name == "rejectMessageRequest" }

        assertEquals("channels/{channelId}/recipients/@me", update.getAnnotation(PUT::class.java).value)
        assertEquals("channels/{channelId}/recipients/@me", reject.getAnnotation(DELETE::class.java).value)
        assertTrue(gson.toJson(RestAPIParams.MessageRequestConsentStatus(2)).contains("\"consent_status\":2"))
    }
}
