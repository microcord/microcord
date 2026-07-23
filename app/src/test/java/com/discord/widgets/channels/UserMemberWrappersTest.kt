package com.discord.widgets.channels

import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UserMemberWrappersTest {
    @Test
    fun preservesAkaFilteringAndGuildAvatarUrls() {
        val first = GuildMember().copy(nick = "First", guildId = 1, userId = 2, avatarHash = "avatar")
        val blank = GuildMember().copy(nick = "   ")
        val duplicate = GuildMember().copy(nick = "First")

        assertEquals(listOf("First", "First"), akaNicknames(listOf(first, blank, duplicate)))
        assertEquals(
            "https://cdn.discordapp.com/guilds/1/users/2/avatars/avatar.webp?size=16",
            guildMemberAvatarUrl(first, 16, "webp"),
        )
        assertEquals("data:image/png;base64,x", guildMemberAvatarUrl(first.copy(avatarHash = "data:image/png;base64,x"), 16, "webp"))
    }

    @Test
    fun preservesMemberItemIdentityAndDataClassSemantics() {
        val user = CoreUser(42, "member")
        val member = GuildMember().copy(userId = 42)
        val item = SimpleMembersAdapter.MemberItem(user, member)

        assertEquals("42", item.key)
        assertEquals(SimpleMembersAdapter.MemberItem.TYPE_MEMBER, item.type)
        assertEquals(item, item.copy())
        assertNotEquals(item, item.copy(guildMember = null))
    }
}
