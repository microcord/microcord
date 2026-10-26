package com.discord.widgets.user.profile

import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.api.game.Game
import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.api.user.UserProfile
import com.discord.api.user.UserApplicationDynamicField
import com.discord.api.user.UserApplicationIdentities
import com.discord.api.voice.state.StageRequestToSpeakState
import com.discord.models.member.GuildMember
import com.discord.models.domain.emoji.toCodePoints
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import com.discord.nullserializable.NullSerializable
import com.google.gson.FieldNamingPolicy
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileContractsTest {
    @Test
    fun parsesModernProfileThemeMetadata() {
        val profile = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()
            .fromJson(
                """{
                    "user":{
                        "id":"2","username":"target",
                        "avatar_decoration_data":{"asset":"a_global","sku_id":"31","expires_at":null}
                    },
                    "guild_member":{
                        "guild_id":"10","user":{"id":"2","username":"target"},"roles":[],
                        "avatar_decoration_data":{"asset":"a_guild","sku_id":"32","expires_at":1740124800}
                    },
                    "user_profile":{"pronouns":"they/them","theme_colors":[1193046,6636321]},
                    "guild_member_profile":{
                        "guild_id":"10","pronouns":"","banner":"guild-banner",
                        "theme_colors":[1122867,4478310],
                        "emoji":{"id":"1090395834966880336","name":"meowlien","roles":[],"require_colons":true,"managed":false,"animated":false,"available":true},
                        "profile_effect":{"sku_id":"33","expires_at":1740124800}
                    }
                }""".trimIndent(),
                UserProfile::class.java,
            )

        assertEquals(listOf(1193046, 6636321), profile.userProfile?.themeColors)
        assertEquals(10L, profile.guildMemberProfile?.guildId)
        assertEquals("guild-banner", profile.guildMemberProfile?.banner)
        assertEquals(1090395834966880336L, profile.guildMemberProfile?.emoji?.id)
        assertEquals(33L, profile.guildMemberProfile?.profileEffect?.skuId)
        assertEquals(1740124800L, profile.guildMemberProfile?.profileEffect?.expiresAt)
        assertEquals("a_global", profile.user.avatarDecorationData?.value?.asset)
        assertEquals("a_guild", profile.guildMember?.avatarDecorationData?.asset)
        assertTrue(profile.widgets.orEmpty().isEmpty())
        // The fixture's decoration expired in 2025, so it resolves to nothing to render.
        assertEquals(
            emptyList<String>(),
            profile.guildMember?.avatarDecorationData?.imageUrls(animate = true),
        )
        assertEquals(
            emptyList<String>(),
            loaded(null, false).copy(userProfile = profile).avatarDecoration?.imageUrls(animate = true).orEmpty(),
        )
    }

    /** `private` is a Kotlin keyword, so the field is renamed and only the annotation binds it. */
    @Test
    fun parsesThePrivateProfileFlag() {
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()
        val user = """"user":{"id":"2","username":"target"}"""

        assertTrue(gson.fromJson("""{$user,"private":true}""", UserProfile::class.java).isPrivate)
        assertFalse(gson.fromJson("""{$user}""", UserProfile::class.java).isPrivate)
    }

    @Test
    fun parsesProfileBoardWidgetsInServerOrder() {
        val profile = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()
            .fromJson(
                """{
                    "user":{"id":"2","username":"target"},
                    "widgets":[
                        {"id":"10","updated_at":"2026-05-14T00:00:00Z","data":{"type":"favorite_games","games":[{"game_id":"20","comment":"Best game","tags":["expert","open_to_teach"]}]}},
                        {"id":"11","data":{"type":"application","application_id":"30"}}
                    ]
                }""".trimIndent(),
                UserProfile::class.java,
            )

        assertEquals(listOf("favorite_games", "application"), profile.widgets.orEmpty().map { it.data.type })
        assertEquals(20L, profile.widgets.orEmpty().first().data.games.orEmpty().single().gameId)
        assertEquals(listOf("expert", "open_to_teach"), profile.widgets.orEmpty().first().data.games.orEmpty().single().tags)
        assertTrue(profile.widgets.orEmpty().last().data.games.orEmpty().isEmpty())
        assertEquals(30L, profile.widgets.orEmpty().last().data.applicationId)
    }

    @Test
    fun parsesApplicationWidgetIdentityFieldsWithoutUntypedValues() {
        val response = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()
            .fromJson(
                """{"identities":[{"application_id":"30","provider_issued_user_id":"steam-id","profile":{"username":"Player","data":{"primary":{"playtime_hours":66.8,"total_wins":401},"dynamic":[{"type":1,"name":"favorite_game","value":"Portal 2"},{"type":2,"name":"albums","value":14000},{"type":3,"name":"hero","value":{"url":"https://example.com/hero.png","width":300,"height":450}}]}}}]}""",
                UserApplicationIdentities::class.java,
            )

        val profile = requireNotNull(response.identities.orEmpty().single().displayProfile())
        assertEquals("Player", profile.username)
        assertEquals(66.8, profile.data?.primary?.playtimeHours)
        assertEquals("Portal 2", (profile.data?.dynamic?.get(0) as UserApplicationDynamicField.Text).value)
        assertEquals(14000.0, (profile.data?.dynamic?.get(1) as UserApplicationDynamicField.Number).value, 0.0)
        assertEquals("https://example.com/hero.png", (profile.data?.dynamic?.get(2) as UserApplicationDynamicField.Image).value.url)
    }

    @Test
    fun profileWidgetTagsCollapseIntoOverflowChip() {
        assertEquals(listOf("expert", "obsessed", "open_to_play", "+2"), visibleWidgetTags(
            listOf("expert", "obsessed", "open_to_play", "lfg", "casual"),
        ))
        assertEquals(listOf("expert"), visibleWidgetTags(listOf("expert")))
        assertEquals(
            listOf("expert", "obsessed", "open_to_play", "lfg", "casual"),
            visibleWidgetTags(listOf("expert", "obsessed", "open_to_play", "lfg", "casual"), expanded = true),
        )
    }

    @Test
    fun parsesRnGameMediaCoverReference() {
        val game = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()
            .fromJson(
                """{"id":"700136079562375258","name":"VALORANT","media":{"cover":{"type":"hash","value":"9c55c3dbef3b1621d17ee32dc7765dcc"}}}""",
                Game::class.java,
            )

        assertEquals(Game.TYPE_HASH, game.cover()?.type)
        assertEquals("9c55c3dbef3b1621d17ee32dc7765dcc", game.cover()?.value)
    }

    @Test
    fun preservesConnectionAndPresenceContracts() {
        val account = ConnectedAccount(id = "42", name = "user", visibility = ConnectedAccount.VISIBLE, type = "github")
        assertEquals("42", UserProfileConnectionsView.ConnectedAccountItem(account).key)
        assertTrue(StageRequestToSpeakState.NONE.canBeInvitedToSpeak)
        assertFalse(StageRequestToSpeakState.ON_STAGE.canBeInvitedToSpeak)
        assertEquals("1f44b_1f3fd", "👋🏽".toCodePoints())
    }

    @Test
    fun profileHeaderStateKeepsDerivedAvatarAndReducedMotionState() {
        val user = CoreUser(id = 2, username = "target", avatar = "avatar")
        val member = GuildMember(
            color = 0,
            hoistRoleId = 0,
            roles = emptyList(),
            nick = "guild nick",
            premiumSince = null,
            pending = false,
            joinedAt = null,
            guildId = 10,
            userId = user.id,
            avatarHash = "guild-avatar",
        )
        val profile = UserProfile(
            user = com.discord.api.user.User(
                id = user.id,
                username = user.username,
                banner = NullSerializable.Value("banner"),
                bannerColor = NullSerializable.Value("#123456"),
            ),
        )
        val storeState = UserProfileHeaderViewModel.StoreState(
            me = meUser(),
            user = user,
            userIdToGuildMemberMap = mapOf(user.id to member),
            guildMembers = emptyList(),
            richPresence = null,
            streamContext = null,
            userProfile = profile,
            allowAnimatedEmojis = true,
            reducedMotionEnabled = true,
            channel = null,
        )

        val initial = UserProfileHeaderViewModel.mapStoreState(storeState, null)
        val rebuilt = UserProfileHeaderViewModel.mapStoreState(
            storeState,
            initial.copy(allowAnimationInReducedMotion = true),
        )

        assertTrue(rebuilt.hasGuildMemberAvatar)
        assertTrue(rebuilt.showSmallAvatar)
        assertFalse(rebuilt.showMediumAvatar)
        assertEquals("banner", rebuilt.banner)
        assertEquals("#123456", rebuilt.bannerColorHex)
        assertTrue(rebuilt.allowAnimationInReducedMotion)
    }

    @Test
    fun profileBannerAnimationHonorsReducedMotionAndBannerSource() {
        val animated = loaded(banner = "a_banner", reducedMotion = false)
        assertTrue(animated.shouldAnimateBanner)
        assertTrue(animated.shouldAutoPlayBanner)
        assertFalse(animated.shouldShowGIFTag)

        val reduced = loaded(banner = "a_banner", reducedMotion = true)
        assertFalse(reduced.shouldAutoPlayBanner)
        assertTrue(reduced.shouldShowGIFTag)
        assertTrue(reduced.copy(allowAnimationInReducedMotion = true).shouldAutoPlayBanner)

        assertFalse(loaded(banner = "banner", reducedMotion = false).shouldAutoPlayBanner)
        assertFalse(loaded(banner = "data:image/gif;base64,AAAA", reducedMotion = true).shouldAutoPlayBanner)

        val guildBanner = reduced.copy(
            banner = null,
            guildMember = GuildMember(
                color = 0,
                hoistRoleId = 0,
                roles = emptyList(),
                nick = null,
                premiumSince = null,
                pending = false,
                joinedAt = null,
                guildId = 10,
                userId = 2,
                bannerHash = "a_guild",
            ),
            allowAnimationInReducedMotion = true,
        )
        assertTrue(guildBanner.shouldAutoPlayBanner)
        assertFalse(guildBanner.shouldShowGIFTag)
    }

    @Test
    fun guildProfileBannerKeepsGuildScopeWithoutBreakingGlobalFallback() {
        val member = GuildMember(
            color = 0,
            hoistRoleId = 0,
            roles = emptyList(),
            nick = null,
            premiumSince = null,
            pending = false,
            joinedAt = null,
            guildId = 10,
            userId = 2,
        )
        val guildBanner = loaded("global-banner", false).copy(
            guildMember = member,
            userProfile = UserProfile(
                userProfile = UserProfile.ProfileMetadata(banner = "global-banner"),
                guildMemberProfile = UserProfile.ProfileMetadata(banner = "guild-banner"),
            ),
        )

        assertEquals("guild-banner", guildBanner.effectiveBanner)
        assertEquals(10L, guildBanner.effectiveBannerGuildId)

        val globalFallback = guildBanner.copy(
            userProfile = UserProfile(
                userProfile = UserProfile.ProfileMetadata(banner = "global-banner"),
                guildMemberProfile = UserProfile.ProfileMetadata(guildId = 10),
            ),
        )
        assertEquals("global-banner", globalFallback.effectiveBanner)
        assertNull(globalFallback.effectiveBannerGuildId)
    }

    @Test
    fun profileThemeUsesGuildColorsAndBuildsOpaqueColors() {
        val profile = UserProfile(
            user = com.discord.api.user.User(2, "target"),
            userProfile = UserProfile.ProfileMetadata(themeColors = listOf(0x112233, 0x445566)),
            guildMemberProfile = UserProfile.ProfileMetadata(
                guildId = 10,
                banner = "guild-banner",
                themeColors = listOf(0x123456, 0x654321),
            ),
        )
        val user = CoreUser(id = 2, username = "target")
        val state = UserProfileHeaderViewModel.mapStoreState(
            UserProfileHeaderViewModel.StoreState(
                meUser(), user, emptyMap(), emptyList(), null, null, profile,
                allowAnimatedEmojis = true, reducedMotionEnabled = false, channel = null,
            ),
            null,
        )

        assertEquals(listOf(0x123456, 0x654321), state.themeColors)
        assertEquals(listOf(0xFF123456.toInt(), 0xFF654321.toInt()), state.themeGradientColors)
        assertEquals("guild-banner", state.banner)
        assertEquals(10L, state.bannerGuildId)
        assertNull(state.guildMember)
        assertNull(loaded(null, false, listOf(0x123456)).themeGradientColors)
        assertEquals(0xFF000000.toInt(), UserProfileHeaderViewModel.profileColorToArgb(0))
        assertEquals(0xFFFFFFFF.toInt(), UserProfileHeaderViewModel.profileColorToArgb(0xFFFFFF))
        assertNull(UserProfileHeaderViewModel.profileColorToArgb(-1))
        assertNull(UserProfileHeaderViewModel.profileColorToArgb(0x1000000))
    }

    @Test
    fun profileThemeDerivesDesktopModeSurfacesTextAndContrastButtons() {
        val dark = ProfileThemeColors.from(0xFF123456.toInt(), 0xFF654321.toInt(), true)
        assertTrue(dark.isDark)
        assertEquals(0xFF071422.toInt(), dark.bodySurface)
        assertEquals(dark.headerSurface, dark.bodySurface)
        assertEquals(0x29000000, dark.sectionOverlay)
        assertEquals(0x29000000, dark.noteOverlay)
        assertEquals(0x14FFFFFF, dark.borderOverlay)
        assertEquals(0xF5FFFFFF.toInt(), dark.textDefault)
        assertTrue(ProfileThemeColors.contrastRatio(dark.textDefault, dark.bodySurface) >= 4.5)
        assertEquals(0xFF123456.toInt(), dark.button)

        val light = ProfileThemeColors.from(0xFF123456.toInt(), 0xFF654321.toInt(), false)
        assertEquals(dark, light)

        val bright = ProfileThemeColors.from(0xFFF0F0F0.toInt(), 0xFFE0D0C0.toInt(), false)
        assertFalse(bright.isDark)
        assertEquals(0xFFF9F9F9.toInt(), bright.bodySurface)
        assertEquals(0x3DFFFFFF, bright.sectionOverlay)
        assertEquals(0xF5000000.toInt(), bright.textDefault)
        assertTrue(ProfileThemeColors.contrastRatio(bright.textDefault, bright.bodySurface) >= 4.5)
        assertEquals(0xFF363636.toInt(), bright.button)

        assertEquals(
            0xFF003BBE.toInt(),
            ProfileThemeColors.from(0xFF5865F2.toInt(), 0xFF000000.toInt(), true).button,
        )
        assertEquals(
            0xFF424242.toInt(),
            ProfileThemeColors.from(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), true).button,
        )
        assertEquals(
            0xFF555555.toInt(),
            ProfileThemeColors.from(0xFF000000.toInt(), 0xFF000000.toInt(), true).button,
        )
    }

    @Test
    fun guildProfileEffectWinsWithoutAGuildMember() {
        val global = UserProfile.ProfileEffect(1)
        val guild = UserProfile.ProfileEffect(2)
        val state = loaded(null, false).copy(
            userProfile = UserProfile(
                userProfile = UserProfile.ProfileMetadata(profileEffect = global),
                guildMemberProfile = UserProfile.ProfileMetadata(guildId = 10, profileEffect = guild),
            ),
        )

        assertNull(state.guildMember)
        assertEquals(guild, state.profileEffect)
        assertEquals(1, UserProfile.ProfileEffectItem.TYPE)
    }

    @Test
    fun parsesProfileEffectProductLayers() {
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()
        val effect = gson.fromJson(
            """{"sku_id":"4","expires_at":null}""",
            UserProfile.ProfileEffect::class.java,
        )
        val product = gson.fromJson(
            """{
                "sku_id":"4",
                "name":"fixture",
                "summary":"fixture summary",
                "store_listing_id":"4",
                "styles":{
                    "background_colors":[4620287,1396431],
                    "button_colors":[5793266,5793266],
                    "confetti_colors":[43772,15774258,16414587,3000177,9739511]
                },
                "preview_assets":null,
                "items":[{
                    "sku_id":"4","type":1,
                    "title":"fixture","description":"fixture summary",
                    "accessibilityLabel":"Animated profile effect",
                    "animationType":2,
                    "thumbnailPreviewSrc":"https://cdn.example/assets/content/thumbnail",
                    "reducedMotionSrc":"https://cdn.example/assets/content/reduced",
                    "effects":[{
                        "src":"https://cdn.example/assets/content/layer-one","loop":false,
                        "height":880,"width":450,"duration":4980,"start":0,"loopDelay":0,
                        "position":{"x":0,"y":0},"zIndex":100,"randomizedSources":[]
                    },{
                        "src":"https://cdn.example/assets/content/layer-two","loop":true,
                        "height":880,"width":450,"duration":5000,"start":6500,"loopDelay":1500,
                        "position":{"x":0,"y":0},"zIndex":101,"randomizedSources":[]
                    }]
                }],
                "type":1,"premium_type":0,"category_sku_id":"5",
                "google_sku_ids":{"5":"fixture-five","7":"fixture-seven"},
                "is_first_party":true
            }""".trimIndent(),
            UserProfile.ProfileEffectProduct::class.java,
        )

        assertEquals(4L, effect.skuId)
        assertNull(effect.expiresAt)
        val item = product.items.single()
        assertEquals(4L, item.skuId)
        assertEquals(2, item.animationType)
        assertEquals("https://cdn.example/assets/content/reduced", item.reducedMotionSrc)
        assertEquals(2, item.effects.size)
        assertFalse(item.effects.first().loop)
        assertEquals(4980L, item.effects.first().duration)
        assertEquals(0L, item.effects.first().start)
        assertEquals(100, item.effects.first().zIndex)
        assertTrue(item.effects.last().loop)
        assertEquals(5000L, item.effects.last().duration)
        assertEquals(6500L, item.effects.last().start)
        assertEquals(1500L, item.effects.last().loopDelay)
        assertEquals(101, item.effects.last().zIndex)
        assertTrue(item.effects.all { it.randomizedSources.orEmpty().isEmpty() })
        assertTrue(item.effects.all { it.src.substringAfterLast('/').contains('.') == false })
        val layerWithoutRandomizedSources = gson.fromJson(
            """{"src":"https://cdn.example/layer","loop":false}""",
            UserProfile.ProfileEffectLayer::class.java,
        )
        assertTrue(layerWithoutRandomizedSources.randomizedSources.orEmpty().isEmpty())
    }

    @Test
    fun profileDecorationUsesTheAvatarDecorationCdnAndReducedMotionFallback() {
        val profile = UserProfile(
            user = com.discord.api.user.User(
                2,
                "target",
                avatarDecorationData = com.discord.nullserializable.NullSerializable.Value(
                    com.discord.api.user.AvatarDecorationData("a_frame", 3),
                ),
            ),
        )
        val animated = loaded(null, false).copy(userProfile = profile)
        val reduced = animated.copy(reducedMotionEnabled = true)

        assertEquals(
            listOf(
                "https://cdn.discordapp.com/media/v1/collectibles-shop/3/animated",
                "https://cdn.discordapp.com/avatar-decoration-presets/a_frame.png?size=64&passthrough=true",
            ),
            animated.avatarDecoration?.imageUrls(animated.shouldAnimateBanner),
        )
        assertEquals(
            listOf(
                "https://cdn.discordapp.com/media/v1/collectibles-shop/3/static",
                "https://cdn.discordapp.com/avatar-decoration-presets/a_frame.png?size=64&passthrough=false",
            ),
            reduced.avatarDecoration?.imageUrls(reduced.shouldAnimateBanner),
        )
    }

    @Test
    fun pronounsAreOneLine() {
        val state = loaded(banner = null, reducedMotion = false)
            .copy(userProfile = UserProfile(userProfile = UserProfile.ProfileMetadata(pronouns = " she\n\nher \r\n ")))
        assertEquals("she her", state.pronouns)
    }

    private fun loaded(
        banner: String?,
        reducedMotion: Boolean,
        themeColors: List<Int>? = null,
    ) = UserProfileHeaderViewModel.ViewState.Loaded(
        user = CoreUser(id = 2, username = "target"),
        banner = banner,
        bannerGuildId = null,
        bannerColorHex = null,
        themeColors = themeColors,
        guildMember = null,
        guildMembersForAka = emptyList(),
        presence = null,
        streamContext = null,
        userProfile = UserProfile(),
        isMeUserPremium = false,
        isMeUserVerified = true,
        allowAnimatedEmojis = true,
        showPresence = true,
        editable = false,
        reducedMotionEnabled = reducedMotion,
        allowAnimationInReducedMotion = false,
        isMe = false,
    )

    private fun meUser() = MeUser(
        1, "me", null, null, false, false, 1, PremiumTier.TIER_1,
        null, false, true, null, 0, 0, null, NsfwAllowance.UNKNOWN, null, null,
    )
}
