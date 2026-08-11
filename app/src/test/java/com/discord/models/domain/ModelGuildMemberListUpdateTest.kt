package com.discord.models.domain

import com.discord.api.presence.ClientStatus
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.stores.memberListMembers
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GUILD_MEMBER_LIST_UPDATE` is the highest-volume gateway payload the client parses, so it is
 * modelled with the streaming [Model.Parser] contract and typed operations rather than maps.
 */
class ModelGuildMemberListUpdateTest {

    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    @Test
    fun `parses every operation into its typed form`() {
        val update = gson.fromJson(PAYLOAD, ModelGuildMemberListUpdate::class.java)

        assertEquals(100L, update.guildId)
        assertEquals(ModelGuildMemberListUpdate.EVERYONE_ID, update.id)
        assertEquals(update.id, update.memberListId)
        assertEquals(
            listOf(
                ModelGuildMemberListUpdate.Group("online", 2),
                ModelGuildMemberListUpdate.Group("offline", 1),
            ),
            update.groups,
        )

        val sync = update.ops[0] as ModelGuildMemberListUpdate.Operation.Sync
        assertEquals(0..2, sync.range)
        assertEquals(2, sync.items.size)
        val header = sync.items[0] as ModelGuildMemberListUpdate.Item.GroupItem
        assertEquals(ModelGuildMemberListUpdate.Group.Type.ONLINE, header.group.type)
        val member = sync.items[1] as ModelGuildMemberListUpdate.Item.MemberItem
        assertEquals(200L, member.member.user.id)
        assertEquals("Tester", member.member.nick)
        assertEquals(ClientStatus.ONLINE, member.member.presence?.status)
        assertEquals(listOf(200L, 201L, 200L), memberListMembers(update).map { it.user.id }.toList())

        val insert = update.ops[1] as ModelGuildMemberListUpdate.Operation.Insert
        assertEquals(1, insert.index)
        assertTrue(insert.item is ModelGuildMemberListUpdate.Item.MemberItem)

        val updateOp = update.ops[2] as ModelGuildMemberListUpdate.Operation.Update
        assertEquals(0, updateOp.index)

        val delete = update.ops[3] as ModelGuildMemberListUpdate.Operation.Delete
        assertEquals(2, delete.index)

        val invalidate = update.ops[4] as ModelGuildMemberListUpdate.Operation.Invalidate
        assertEquals(3..9, invalidate.range)
    }

    @Test
    fun `base operation exposes item and items uniformly`() {
        val update = gson.fromJson(PAYLOAD, ModelGuildMemberListUpdate::class.java)

        val sync: ModelGuildMemberListUpdate.Operation = update.ops[0]
        assertEquals(2, sync.items!!.size)
        assertNull(sync.item)

        val insert: ModelGuildMemberListUpdate.Operation = update.ops[1]
        assertNull(insert.items)
        assertTrue(insert.item is ModelGuildMemberListUpdate.Item.MemberItem)

        val delete: ModelGuildMemberListUpdate.Operation = update.ops[3]
        assertNull(delete.item)
        assertNull(delete.items)
    }

    @Test
    fun `id defaults to everyone when absent`() {
        val json = """{"guild_id":"100","groups":[],"ops":[]}"""
        val update = gson.fromJson(json, ModelGuildMemberListUpdate::class.java)

        assertEquals(ModelGuildMemberListUpdate.EVERYONE_ID, update.id)
        assertEquals(emptyList<ModelGuildMemberListUpdate.Operation>(), update.ops)
    }

    @Test
    fun `streaming parser consumes only its own object`() {
        val reader = Model.JsonReader(
            StringReader("""[{"guild_id":"100","groups":[],"ops":[]},"tail"]"""),
        )
        reader.`in`.beginArray()

        val update = ModelGuildMemberListUpdate.Parser.parse(reader)

        assertEquals(100L, update.guildId)
        assertEquals("tail", reader.nextStringOrNull())
        reader.`in`.endArray()
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `serialization is refused because the payload is inbound only`() {
        gson.toJson(
            ModelGuildMemberListUpdate(
                guildId = 100,
                ops = emptyList(),
                groups = emptyList(),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an item without a member or a group is rejected`() {
        gson.fromJson(
            """{"guild_id":"100","groups":[],"ops":[{"op":"UPDATE","index":0,"item":{}}]}""",
            ModelGuildMemberListUpdate::class.java,
        )
    }

    private companion object {
        // Sanitised: synthetic snowflakes, no real account data.
        val PAYLOAD = """
            {
              "guild_id": "100",
              "id": "everyone",
              "groups": [
                {"id": "online", "count": 2},
                {"id": "offline", "count": 1}
              ],
              "ops": [
                {
                  "op": "SYNC",
                  "range": [0, 2],
                  "items": [
                    {"group": {"id": "online", "count": 2}},
                    {"member": ${member(200, "Tester")}}
                  ]
                },
                {"op": "INSERT", "index": 1, "item": {"member": ${member(201, "Other")}}},
                {"op": "UPDATE", "index": 0, "item": {"member": ${member(200, "Renamed")}}},
                {"op": "DELETE", "index": 2},
                {"op": "INVALIDATE", "range": [3, 9]}
              ]
            }
        """.trimIndent()

        fun member(userId: Long, nick: String) = """
            {
              "user": {"id": "$userId", "username": "user$userId", "discriminator": "0001"},
              "roles": [],
              "nick": "$nick",
              "joined_at": "2021-01-01T00:00:00.000000+00:00",
              "presence": {"user_id": "$userId", "status": "online", "activities": [], "client_status": {"web": "online"}}
            }
        """.trimIndent()
    }
}
