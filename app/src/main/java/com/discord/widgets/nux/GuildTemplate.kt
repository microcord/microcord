package com.discord.widgets.nux

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.discord.R

enum class GuildTemplate(
    @StringRes val titleRes: Int,
    @DrawableRes val drawableRes: Int,
    val templateType: StockGuildTemplate,
) {
    CreateMyOwn(
        R.string.guild_template_header_create,
        R.drawable.drawable_guild_template_create,
        StockGuildTemplate.CREATE,
    ),
    Gaming(
        R.string.guild_template_header_gaming,
        R.drawable.drawable_guild_template_gaming,
        StockGuildTemplate.GAMING_GROUP,
    ),
    SchoolClub(
        R.string.guild_template_header_school_club,
        R.drawable.drawable_guild_template_club,
        StockGuildTemplate.CLUB,
    ),
    StudyGroup(
        R.string.guild_template_header_study,
        R.drawable.drawable_guild_template_study,
        StockGuildTemplate.STUDY_GROUP,
    ),
    Friends(
        R.string.guild_template_header_friend,
        R.drawable.drawable_guild_template_friend,
        StockGuildTemplate.FRIEND_GROUP,
    ),
    ArtistsAndCreators(
        R.string.guild_template_header_creator,
        R.drawable.drawable_guild_template_creator,
        StockGuildTemplate.CONTENT_CREATOR,
    ),
    LocalCommunity(
        R.string.guild_template_header_community,
        R.drawable.drawable_guild_template_community,
        StockGuildTemplate.LOCAL_COMMUNITY,
    ),
    HubStudyGroup(
        R.string.guild_template_header_study,
        R.drawable.drawable_guild_template_friend,
        StockGuildTemplate.HUB_STUDY_GROUP,
    ),
    HubSchoolClub(
        R.string.guild_template_header_school_club,
        R.drawable.drawable_guild_template_community,
        StockGuildTemplate.HUB_SCHOOL_CLUB,
    ),
    Class(
        R.string.guild_template_header_class,
        R.drawable.drawable_guild_template_study,
        StockGuildTemplate.CLASS,
    ),
    Social(
        R.string.guild_template_header_social,
        R.drawable.drawable_guild_template_gaming,
        StockGuildTemplate.SOCIAL,
    ),
    SubjectOrMajor(
        R.string.guild_template_header_major,
        R.drawable.drawable_guild_template_club,
        StockGuildTemplate.MAJOR,
    ),
    Dorm(
        R.string.guild_template_header_dorm,
        R.drawable.drawable_guild_template_creator,
        StockGuildTemplate.DORM,
    ),
}
