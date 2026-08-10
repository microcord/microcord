package com.discord.widgets.chat.list.adapter

import com.discord.api.message.embed.MessageEmbed
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoModMessageContextTest {
    @Test
    fun `extracts modern message and notification fields`() {
        val embed = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create().fromJson(
            """{"description":"blocked text","fields":[
                {"name":"channel_id","value":"10"},{"name":"rule_name","value":"Rule"},
                {"name":"decision_id","value":"decision"},{"name":"keyword","value":"bad"},
                {"name":"keyword_matched_content","value":"bad text"},{"name":"flagged_message_id","value":"20"},
                {"name":"timeout_duration","value":"60"},{"name":"quarantine_user","value":"username"},
                {"name":"application_name","value":"App"},{"name":"interaction_user_id","value":"30"},
                {"name":"notification_type","value":"raid"},{"name":"join_attempts","value":"40"},
                {"name":"dms_sent","value":"12"},{"name":"raid_type","value":"DM_RAID"}
            ]}""",
            MessageEmbed::class.java,
        )

        val context = embed.extractAutoModContext()
        assertEquals("blocked text", context.content)
        assertEquals(10L, context.channelId)
        assertEquals("decision", context.decisionId)
        assertEquals("bad text", context.keywordMatchedContent)
        assertEquals(20L, context.flaggedMessageId)
        assertEquals("username", context.quarantineType)
        assertEquals("App", context.applicationName)
        assertEquals(30L, context.interactionUserId)
        assertEquals("raid", context.notification?.type)
        assertEquals(40, context.notification?.joinAttempts)
        assertEquals(12, context.notification?.dmsSent)
        assertEquals("DM_RAID", context.notification?.raidType)
    }
}
