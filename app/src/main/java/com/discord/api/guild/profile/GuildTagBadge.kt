package com.discord.api.guild.profile

import com.discord.api.guild.GuildFeature

/**
 * The badge shown beside a guild tag.
 *
 * [pack] is `null` for the ten always-available kinds; the rest are unlocked by buying that pack's
 * powerup, which the server reports back as a guild feature. Values 10–20 are deliberately absent —
 * the API still returns them for guilds that already carry one, but they can no longer be set.
 */
enum class GuildTagBadge(
    val apiValue: Int,
    val pack: GuildFeature? = null,
    /** Badges whose art has a second ramp take an accent colour as well as a primary. */
    val hasAccentColor: Boolean = false,
) {
    LEAF(6),
    SWORD(0, hasAccentColor = true),
    HEART(7),
    FIRE(8),
    WATER_DROP(1),
    SKULL(2),
    MOON(4),
    LIGHTNING(5),
    COMPASS(9, hasAccentColor = true),
    TOADSTOOL(3, hasAccentColor = true),

    BUNNY(21, GuildFeature.GUILD_TAGS_BADGE_PACK_PETS),
    DOG(22, GuildFeature.GUILD_TAGS_BADGE_PACK_PETS, hasAccentColor = true),
    FROG(23, GuildFeature.GUILD_TAGS_BADGE_PACK_PETS, hasAccentColor = true),
    GOAT(24, GuildFeature.GUILD_TAGS_BADGE_PACK_PETS),
    CAT(25, GuildFeature.GUILD_TAGS_BADGE_PACK_PETS),

    DIAMOND(26, GuildFeature.GUILD_TAGS_BADGE_PACK_FLEX),
    CROWN(27, GuildFeature.GUILD_TAGS_BADGE_PACK_FLEX, hasAccentColor = true),
    TROPHY(28, GuildFeature.GUILD_TAGS_BADGE_PACK_FLEX),
    MONEY_BAG(29, GuildFeature.GUILD_TAGS_BADGE_PACK_FLEX),
    DOLLAR_SIGN(30, GuildFeature.GUILD_TAGS_BADGE_PACK_FLEX),

    CLOVER(31, GuildFeature.GUILD_TAGS_BADGE_PACK_PLANT),
    BLOSSOM(32, GuildFeature.GUILD_TAGS_BADGE_PACK_PLANT),
    POTTED_PLANT(33, GuildFeature.GUILD_TAGS_BADGE_PACK_PLANT, hasAccentColor = true),
    MAPLE(34, GuildFeature.GUILD_TAGS_BADGE_PACK_PLANT),
    WILTED_FLOWER(35, GuildFeature.GUILD_TAGS_BADGE_PACK_PLANT, hasAccentColor = true),

    BUTTERFLY(36, GuildFeature.GUILD_TAGS_BADGE_PACK_CREEPY_CRAWLIES, hasAccentColor = true),
    SNAIL(37, GuildFeature.GUILD_TAGS_BADGE_PACK_CREEPY_CRAWLIES, hasAccentColor = true),
    CATERPILLAR(38, GuildFeature.GUILD_TAGS_BADGE_PACK_CREEPY_CRAWLIES, hasAccentColor = true),
    SPIDER(39, GuildFeature.GUILD_TAGS_BADGE_PACK_CREEPY_CRAWLIES, hasAccentColor = true),
    BEE(40, GuildFeature.GUILD_TAGS_BADGE_PACK_CREEPY_CRAWLIES),
    ;

    companion object {
        /** The kind a guild that has never had a tag starts with. */
        val DEFAULT = LEAF

        fun fromApiValue(value: Int?) = entries.firstOrNull { it.apiValue == value }

        fun unlockedFor(features: Set<GuildFeature>) =
            entries.filter { it.pack == null || it.pack in features }
    }
}
