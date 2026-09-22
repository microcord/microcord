package com.discord.utilities.icon

import com.discord.api.channel.Channel
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Build.VERSION.SDK_INT` is 0 under plain JVM unit tests, so [IconUtils.staticImageExtension]
 * resolves to `webp` here — the same value the reference produces on every API level except 28/29.
 */
class IconUtilsTest {

    private val staticExtension = IconUtils.staticImageExtension

    // ------------------------------------------------------------------ size quantisation

    @Test
    fun quantisesSizeUpToTheNextSupportedMediaProxyWidth() {
        assertEquals(16, IconUtils.getMediaProxySize(1))
        assertEquals(16, IconUtils.getMediaProxySize(16))
        assertEquals(20, IconUtils.getMediaProxySize(17))
        assertEquals(96, IconUtils.getMediaProxySize(81))
        assertEquals(128, IconUtils.getMediaProxySize(101))
        assertEquals(4096, IconUtils.getMediaProxySize(4096))
    }

    @Test
    fun clampsSizesLargerThanTheLargestSupportedWidth() {
        assertEquals(4096, IconUtils.getMediaProxySize(4097))
        assertEquals(4096, IconUtils.getMediaProxySize(100_000))
    }

    @Test
    fun snapsDownARungOnlyWhenTheOvershootIsSmall() {
        assertEquals(100, IconUtils.getMediaProxySize(101, allowUpscaling = true))
        assertEquals(128, IconUtils.getMediaProxySize(127, allowUpscaling = true))
        assertEquals(128, IconUtils.getMediaProxySize(101))
    }

    @Test
    fun quantisesTheRequestedAvatarSizeInTheQueryString() {
        assertEquals(
            "https://cdn.discordapp.com/avatars/1/abc.$staticExtension?size=128",
            IconUtils.getForUser(1L, "abc", 1, false, 101),
        )
    }

    @Test
    fun omitsTheSizeQueryForNonPositiveSizes() {
        assertEquals(
            "https://cdn.discordapp.com/avatars/1/abc.$staticExtension",
            IconUtils.getForUser(1L, "abc", 1, false, 0),
        )
        assertEquals(
            "https://cdn.discordapp.com/avatars/1/abc.$staticExtension",
            IconUtils.getForUser(1L, "abc", 1, false, null),
        )
    }

    // ------------------------------------------------------------------ animated gating

    @Test
    fun detectsAnimatedHashes() {
        assertTrue(IconUtils.isImageHashAnimated("a_deadbeef"))
        assertFalse(IconUtils.isImageHashAnimated("deadbeef"))
        assertTrue(IconUtils.isDataUrlForGif("data:image/gif;base64,AAAA"))
        assertFalse(IconUtils.isDataUrlForGif("data:image/png;base64,AAAA"))
    }

    @Test
    fun animatedHashOnlyBecomesGifWhenAnimationIsAllowed() {
        assertEquals(staticExtension, IconUtils.getImageExtension("a_hash", allowAnimation = false))
        assertEquals(staticExtension, IconUtils.getImageExtension("a_hash", allowAnimation = true))
        assertEquals(staticExtension, IconUtils.getImageExtension("hash", allowAnimation = true))
    }

    @Test
    fun animationSettingSelectsTheAvatarExtension() {
        assertEquals(
            "https://cdn.discordapp.com/avatars/7/a_hash.$staticExtension?size=128",
            IconUtils.getForUser(7L, "a_hash", 1, allowAnimation = false),
        )
        assertEquals(
            "https://cdn.discordapp.com/avatars/7/a_hash.$staticExtension?size=128&animated=true",
            IconUtils.getForUser(7L, "a_hash", 1, allowAnimation = true),
        )
    }

    @Test
    fun animationSettingSelectsTheGuildIconExtension() {
        assertEquals(
            "https://cdn.discordapp.com/icons/9/a_hash.$staticExtension",
            IconUtils.getForGuild(9L, "a_hash", allowAnimation = false),
        )
        assertEquals(
            "https://cdn.discordapp.com/icons/9/a_hash.$staticExtension?animated=true",
            IconUtils.getForGuild(9L, "a_hash", allowAnimation = true),
        )
    }

    // ------------------------------------------------------------------ user avatars

    @Test
    fun buildsUserAvatarUrls() {
        assertEquals(
            "https://cdn.discordapp.com/avatars/852/hash.$staticExtension?size=128",
            IconUtils.getForUser(852L, "hash", 5),
        )
    }

    @Test
    fun buildsArchivedAvatarUrlsWithTheAvatarRecordId() {
        assertEquals(
            "https://cdn.discordapp.com/avatars/852/archived/123/hash.$staticExtension?size=128",
            IconUtils.getForArchivedUserAvatar(852L, "123", "hash", size = 101),
        )
    }

    @Test
    fun fallsBackToTheDiscriminatorDefaultAvatar() {
        assertEquals(
            "asset://asset/images/default_avatar_2.png",
            IconUtils.getForUser(852L, null, 7),
        )
        assertEquals(
            "asset://asset/images/default_avatar_0.png",
            IconUtils.getForUser(852L, null, 5),
        )
        assertEquals(
            "asset://asset/images/default_avatar_4.png",
            IconUtils.getForUser(852L, null, 4),
        )
    }

    @Test
    fun fallsBackToTheFirstDefaultAvatarWithoutIdOrDiscriminator() {
        assertEquals(
            "asset://asset/images/default_avatar_0.png",
            IconUtils.getForUser(null, null, null),
        )
        assertEquals(
            "asset://asset/images/default_avatar_0.png",
            IconUtils.getForUser(852L, null, null),
        )
        assertEquals(
            "asset://asset/images/default_avatar_0.png",
            IconUtils.getForUser(null as com.discord.models.user.User?),
        )
    }

    @Test
    fun treatsMinusOneUserIdAsAPreResolvedUrl() {
        assertEquals(
            "https://example.invalid/already-a-url.png",
            IconUtils.getForUser(-1L, "https://example.invalid/already-a-url.png", 3),
        )
    }

    @Test
    fun buildsUserAvatarUrlsFromAUserModel() {
        val user = CoreUser(id = 42L, username = "n", avatar = "hash", discriminator = 1234)
        assertEquals(
            "https://cdn.discordapp.com/avatars/42/hash.$staticExtension?size=64",
            IconUtils.getForUser(user, false, 64),
        )
    }

    @Test
    fun buildsUserBannerUrls() {
        assertEquals(
            "https://cdn.discordapp.com/banners/42/a_b.$staticExtension?size=512&animated=true",
            IconUtils.getForUserBanner(42L, "a_b", 500, allowAnimation = true),
        )
        assertNull(IconUtils.getForUserBanner(42L, null))
        assertNull(IconUtils.getForUserBanner(42L, "  "))
        assertEquals(
            "https://cdn.discordapp.com/banners/42/a_b.png?size=512",
            IconUtils.getForUserBanner(42L, "a_b", 500, allowAnimation = false),
        )
    }

    // ------------------------------------------------------------------ guild member assets

    @Test
    fun buildsPerGuildMemberAvatarAndBannerUrls() {
        assertEquals(
            "https://cdn.discordapp.com/guilds/1/users/2/avatars/hash.$staticExtension?size=48",
            IconUtils.getForGuildMember("hash", 1L, 2L, 48),
        )
        assertEquals(
            "https://cdn.discordapp.com/guilds/1/users/2/banners/a_hash.$staticExtension?animated=true",
            IconUtils.getForGuildMemberBanner("a_hash", 1L, 2L, allowAnimation = true),
        )
        assertEquals(
            "https://cdn.discordapp.com/guilds/1/users/2/banners/a_hash.png",
            IconUtils.getForGuildMemberBanner("a_hash", 1L, 2L, allowAnimation = false),
        )
        assertNull(IconUtils.getForGuildMember("hash", 0L, 2L))
        assertNull(IconUtils.getForGuildMember("hash", 1L, 0L))
        assertNull(IconUtils.getForGuildMember(null, 1L, 2L))
    }

    @Test
    fun prefersThePerGuildAvatarOverTheGlobalOne() {
        val user = CoreUser(id = 2L, username = "n", avatar = "global", discriminator = 1)
        val memberWithAvatar = guildMember(avatarHash = "member")
        val memberWithoutAvatar = guildMember(avatarHash = null)

        assertEquals(
            "https://cdn.discordapp.com/guilds/1/users/2/avatars/member.$staticExtension?size=128",
            IconUtils.getForGuildMemberOrUser(user, memberWithAvatar),
        )
        assertEquals(
            "https://cdn.discordapp.com/avatars/2/global.$staticExtension?size=128",
            IconUtils.getForGuildMemberOrUser(user, memberWithoutAvatar),
        )
        assertEquals(
            "https://cdn.discordapp.com/avatars/2/global.$staticExtension?size=128",
            IconUtils.getForGuildMemberOrUser(user, null),
        )
    }

    // ------------------------------------------------------------------ guild assets

    @Test
    fun buildsGuildIconBannerAndSplashUrls() {
        assertEquals(
            "https://cdn.discordapp.com/icons/1/hash.$staticExtension?size=128",
            IconUtils.getForGuild(1L, "hash", null, false, 101),
        )
        assertEquals(
            "https://cdn.discordapp.com/banners/1/hash.$staticExtension?size=512",
            IconUtils.getBannerForGuild(1L, "hash", 500),
        )
        assertEquals(
            "https://cdn.discordapp.com/splashes/1/hash.jpg?size=1024",
            IconUtils.getGuildSplashUrl(1L, "hash", 1024),
        )
        assertEquals(
            "https://cdn.discordapp.com/splashes/1/hash.jpg",
            IconUtils.getGuildSplashUrl(1L, "hash"),
        )
    }

    @Test
    fun returnsTheDefaultIconWhenTheGuildHasNone() {
        assertEquals(IconUtils.DEFAULT_ICON, IconUtils.getForGuild(1L, null, IconUtils.DEFAULT_ICON))
        assertEquals(IconUtils.DEFAULT_ICON, IconUtils.getForGuild(1L, "", IconUtils.DEFAULT_ICON))
        assertNull(IconUtils.getForGuild(null as Long?, "hash"))
    }

    @Test
    fun buildsGuildUrlsFromAGuildModel() {
        val guild = Guild(id = 3L, icon = "gi", banner = "gb", splash = "gs")
        assertEquals(
            "https://cdn.discordapp.com/icons/3/gi.$staticExtension",
            IconUtils.getForGuild(guild),
        )
        assertEquals(
            "https://cdn.discordapp.com/banners/3/gb.$staticExtension?size=480",
            IconUtils.getBannerForGuild(guild),
        )
        assertEquals(
            "https://cdn.discordapp.com/splashes/3/gs.jpg",
            IconUtils.getGuildSplashUrl(guild),
        )
        assertNull(IconUtils.getGuildSplashUrl(Guild(id = 3L)))
    }

    // ------------------------------------------------------------------ channels

    @Test
    fun buildsChannelIconUrls() {
        assertEquals(
            "https://cdn.discordapp.com/channel-icons/5/hash.png?size=64",
            IconUtils.getForChannel(5L, "hash", Channel.GROUP_DM, false, 64),
        )
    }

    @Test
    fun fallsBackToADeterministicGroupDmIcon() {
        // Snowflake 0 maps to the Discord epoch, which is 1420070400000 % 8 == 0.
        assertEquals(
            "asset://asset/images/group_dm_icon_0.png",
            IconUtils.getForChannel(0L, null, Channel.GROUP_DM, false),
        )
        assertEquals(
            "asset://asset/images/group_dm_icon_1.png",
            IconUtils.getDefaultForGroupDM(1L shl 22),
        )
        assertEquals(
            "asset://asset/images/group_dm_icon_7.png",
            IconUtils.getDefaultForGroupDM(7L shl 22),
        )
        assertEquals(
            "asset://asset/images/group_dm_icon_0.png",
            IconUtils.getDefaultForGroupDM(8L shl 22),
        )
    }

    @Test
    fun returnsEmptyOrNullForIconlessNonGroupChannels() {
        assertEquals("", IconUtils.getForChannel(5L, null, Channel.GUILD_TEXT, true))
        assertNull(IconUtils.getForChannel(5L, null, Channel.GUILD_TEXT, false))
    }

    // ------------------------------------------------------------------ roles and emoji

    @Test
    fun buildsRoleIconUrls() {
        assertEquals(
            "https://cdn.discordapp.com/role-icons/11/hash.$staticExtension?size=24",
            IconUtils.getRoleIconUrl(11L, "hash", 24),
        )
        assertEquals(
            "https://cdn.discordapp.com/role-icons/11/hash.$staticExtension?quality=lossless",
            IconUtils.getRoleIconUrl(11L, "hash"),
        )
    }

    @Test
    fun buildsEmojiUrls() {
        assertEquals(
            "https://cdn.discordapp.com/emojis/12.$staticExtension?size=48",
            IconUtils.getForEmoji(12L, animated = false, size = 48),
        )
        assertEquals(
            "https://cdn.discordapp.com/emojis/12.$staticExtension?size=48&animated=true",
            IconUtils.getForEmoji(12L, animated = true, size = 48),
        )
    }

    // ------------------------------------------------------------------ application assets

    @Test
    fun buildsApplicationIconUrls() {
        assertEquals(
            "https://cdn.discordapp.com/app-icons/100/icon.png?size=160",
            IconUtils.getApplicationIcon(100L, "icon"),
        )
        assertEquals(
            "https://cdn.discordapp.com/app-icons/100/icon.png?size=256",
            IconUtils.getApplicationIcon(100L, "icon", 250),
        )
    }

    @Test
    fun buildsRichPresenceAssetUrls() {
        assertEquals(
            "https://cdn.discordapp.com/app-assets/100/asset.jpg?size=160",
            IconUtils.getAssetImage(100L, "asset"),
        )
        assertEquals(
            "https://media.discordapp.net/external/abc/def.png",
            IconUtils.getAssetImage(100L, "mp:external/abc/def.png"),
        )
        assertEquals(
            "https://i.scdn.co/image/abc",
            IconUtils.getAssetImage(100L, "SPOTIFY:abc"),
        )
        assertNull(IconUtils.getAssetImage(100L, "unknown:abc"))
        assertNull(IconUtils.getAssetImage(null, "asset"))
    }

    @Test
    fun buildsStoreAssetUrls() {
        assertEquals(
            "https://cdn.discordapp.com/app-assets/100/store/asset.jpg?size=160",
            IconUtils.getStoreAssetImage(100L, "asset"),
        )
        assertNull(IconUtils.getStoreAssetImage(null, "asset"))
    }

    @Test
    fun giftSplashRoundsStrictlyUpAndAlwaysKeepsAspectRatio() {
        assertEquals(
            "https://cdn.discordapp.com/app-icons/100/hash.png?keep_aspect_ratio=true",
            IconUtils.getGiftSplashUrl(100L, "hash"),
        )
        // 160 is a supported width, but this builder picks the next one *above* it.
        assertEquals(
            "https://cdn.discordapp.com/app-icons/100/hash.png" +
                "?size=240&keep_aspect_ratio=true",
            IconUtils.getGiftSplashUrl(100L, "hash", 160),
        )
        // Oversized requests clamp instead of throwing.
        assertEquals(
            "https://cdn.discordapp.com/app-icons/100/hash.png" +
                "?size=4096&keep_aspect_ratio=true",
            IconUtils.getGiftSplashUrl(100L, "hash", 9000),
        )
        assertNull(IconUtils.getGiftSplashUrl(100L, null))
    }

    private fun guildMember(avatarHash: String?) = GuildMember(
        color = 0,
        hoistRoleId = 0L,
        roles = null,
        nick = null,
        premiumSince = null,
        pending = false,
        joinedAt = null,
        guildId = 1L,
        userId = 2L,
        avatarHash = avatarHash,
    )
}
