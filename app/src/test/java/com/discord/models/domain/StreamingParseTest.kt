package com.discord.models.domain

import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonToken
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the hand-rolled streaming parse contract ([Model]).
 *
 * The point of these models is that they consume exactly the tokens belonging to their own value
 * and nothing more; a tree-based replacement would drain the whole document and break the gateway's
 * "read one payload, keep the socket stream open" behaviour.
 */
class StreamingParseTest {

    @Test
    fun `parse consumes only its own object and leaves the stream positioned`() {
        val reader = Model.JsonReader(StringReader("""[{"heartbeat_interval":41250},"after"]"""))

        reader.`in`.beginArray()
        val hello = reader.parse(ModelPayload.Hello())

        assertEquals(41250, hello!!.heartbeatInterval)
        // Nothing beyond the first element was touched.
        assertEquals(JsonToken.STRING, reader.peek())
        assertEquals("after", reader.nextStringOrNull())
        reader.`in`.endArray()
    }

    @Test
    fun `unknown fields are skipped rather than aborting the parse`() {
        val json = """{"unknown_object":{"a":[1,2]},"heartbeat_interval":100,"_trace":["gateway-a"]}"""
        val hello = Model.JsonReader(StringReader(json)).parse(ModelPayload.Hello())!!

        assertEquals(100, hello.heartbeatInterval)
        assertEquals(listOf("gateway-a"), hello.trace)
    }

    @Test
    fun `null values fall back to the supplied default`() {
        val reader = Model.JsonReader(StringReader("""{"id":null,"name":null,"sample_port":null}"""))
        val region = reader.parse(ModelVoiceRegion())!!

        assertNull(region.id)
        assertNull(region.name)
        assertEquals(0, region.samplePort)
    }

    @Test
    fun `a null value in place of an object parses to null`() {
        assertNull(Model.JsonReader(StringReader("null")).parse(ModelVoiceRegion()))
    }

    @Test
    fun `voice region reads every field`() {
        val json = """
            {
              "id": "test-region",
              "name": "Test Region",
              "optimal": true,
              "sample_port": 443,
              "sample_hostname": "test-region.example.invalid",
              "deprecated": false,
              "hidden": true
            }
        """.trimIndent()

        val region = Model.JsonReader(StringReader(json)).parse(ModelVoiceRegion())!!

        assertEquals("test-region", region.id)
        assertEquals("Test Region", region.name)
        assertTrue(region.isOptimal)
        assertEquals(443, region.samplePort)
        assertEquals("test-region.example.invalid", region.sampleHostname)
        assertFalse(region.isDeprecated)
        assertTrue(region.isHidden)
    }

    @Test
    fun `model type adapter factory routes models through the streaming reader`() {
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerTypeAdapterFactory(Model.TypeAdapterFactory())
            .create()

        val webhook = gson.fromJson(
            """{"id":"11","name":"Announcer","channel_id":"22","guild_id":"33","avatar":null}""",
            ModelWebhook::class.java,
        )

        assertEquals(11L, webhook.id)
        assertEquals("Announcer", webhook.name)
        assertEquals(22L, webhook.channelId)
        assertEquals(33L, webhook.guildId)
        assertNull(webhook.avatar)
    }

    @Test
    fun `audit log entry parses nested changes and options`() {
        val json = """
            {
              "id": "1",
              "action_type": 25,
              "target_id": "2",
              "user_id": "3",
              "reason": "cleanup",
              "options": {"count": 4, "channel_id": "5", "role_name": "Testers", "type": 1},
              "changes": [
                {"key": "${'$'}add", "new_value": [{"name": "Testers", "id": "6"}]},
                {"key": "nick", "old_value": "before", "new_value": "after"}
              ]
            }
        """.trimIndent()

        val entry = Model.JsonReader(StringReader(json)).parse(ModelAuditLogEntry())!!

        assertEquals(1L, entry.id)
        assertEquals(ModelAuditLogEntry.ACTION_MEMBER_ROLE_UPDATE, entry.actionTypeId)
        assertEquals(ModelAuditLogEntry.ActionType.UPDATE, entry.actionType)
        assertEquals(ModelAuditLogEntry.TargetType.USER, entry.targetType)
        assertEquals(2L, entry.targetId)
        assertEquals(3L, entry.userId)
        assertEquals("cleanup", entry.reason)
        assertEquals(4, entry.options!!.count)
        assertEquals(5L, entry.options!!.channelId)
        assertEquals("Testers", entry.options!!.roleName)

        val changes = entry.changes!!
        assertEquals(2, changes.size)
        val added = changes[0].newValue as List<*>
        assertEquals(6L, (added.single() as ModelAuditLogEntry.ChangeNameId).id)
        assertEquals("after", changes[1].value)
        assertEquals("before", changes[1].oldValue)
    }

    @Test
    fun `action and target types cover every declared action id`() {
        assertEquals(
            ModelAuditLogEntry.ActionType.CREATE,
            ModelAuditLogEntry.getActionType(ModelAuditLogEntry.ACTION_THREAD_CREATE),
        )
        assertEquals(
            ModelAuditLogEntry.ActionType.DELETE,
            ModelAuditLogEntry.getActionType(ModelAuditLogEntry.ACTION_MESSAGE_BULK_DELETE),
        )
        assertEquals(
            ModelAuditLogEntry.ActionType.UPDATE,
            ModelAuditLogEntry.getActionType(ModelAuditLogEntry.ACTION_GUILD_UPDATE),
        )
        assertEquals(
            ModelAuditLogEntry.ActionType.ALL,
            ModelAuditLogEntry.getActionType(ModelAuditLogEntry.ACTION_ALL),
        )

        assertEquals(
            ModelAuditLogEntry.TargetType.CHANNEL,
            ModelAuditLogEntry.getTargetType(ModelAuditLogEntry.ACTION_MESSAGE_BULK_DELETE),
        )
        assertEquals(
            ModelAuditLogEntry.TargetType.USER,
            ModelAuditLogEntry.getTargetType(ModelAuditLogEntry.ACTION_MESSAGE_PIN),
        )
        assertEquals(
            ModelAuditLogEntry.TargetType.THREAD,
            ModelAuditLogEntry.getTargetType(ModelAuditLogEntry.ACTION_THREAD_DELETE),
        )
    }

    @Test
    fun `gateway gson instance snake cases field names`() {
        assertEquals(
            """{"category_name":"Reactions","gif_preview_url":"https://example.invalid/a.gif"}""",
            InboundGatewayGsonParser.toJson(
                com.discord.models.gifpicker.domain.ModelGifCategory(
                    "Reactions",
                    "https://example.invalid/a.gif",
                ),
            ),
        )
    }
}
