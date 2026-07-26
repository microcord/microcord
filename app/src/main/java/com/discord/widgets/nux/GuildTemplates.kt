package com.discord.widgets.nux

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.discord.R

/**
 * A row in the "what is your server for?" picker.
 *
 * Each entry pairs the label and icon the user sees with the [StockGuildTemplate] that decides which
 * channels the created guild starts with. Several entries deliberately share a title or an icon —
 * the hub variants reuse the NUX artwork with a different underlying template.
 */
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

/**
 * The two published orderings of [GuildTemplate].
 *
 * `CreateMyOwn` is not in either list — [GuildTemplatesAdapter] always prepends it, so it cannot be
 * omitted or reordered by a caller.
 */
object GuildTemplateTypes {

    /** Ordering used by post-registration onboarding. */
    val NUX: List<GuildTemplate> = listOf(
        GuildTemplate.Gaming,
        GuildTemplate.SchoolClub,
        GuildTemplate.StudyGroup,
        GuildTemplate.Friends,
        GuildTemplate.ArtistsAndCreators,
        GuildTemplate.LocalCommunity,
    )

    /** Ordering used when adding a server to a Student Hub. */
    val HUB: List<GuildTemplate> = listOf(
        GuildTemplate.HubStudyGroup,
        GuildTemplate.HubSchoolClub,
        GuildTemplate.Class,
        GuildTemplate.Social,
        GuildTemplate.SubjectOrMajor,
        GuildTemplate.Dorm,
    )
}

/**
 * Which starter channel set a newly created guild gets.
 *
 * **Temporary stand-in.** The reference declares this at
 * `com.discord.widgets.guilds.create.StockGuildTemplate`, together with a
 * `getChannels(resources): List<RestAPIParams.CreateGuildChannel>` used by guild creation. That
 * package holds only `GuildTemplateChannelsView` in this project, and it belongs to another wave, so
 * the enum lives here with its constants and declaration order transcribed verbatim. **Move it —
 * and add `getChannels` — when `widgets/guilds/create` is reconstructed**; every reference here is
 * by constant, so the move is mechanical.
 */
enum class StockGuildTemplate {
    FRIEND_GROUP,
    STUDY_GROUP,
    GAMING_GROUP,
    CONTENT_CREATOR,
    CLUB,
    LOCAL_COMMUNITY,
    HUB_SCHOOL_CLUB,
    HUB_STUDY_GROUP,
    CLASS,
    SOCIAL,
    MAJOR,
    DORM,
    CREATE,
}

/**
 * Where a guild-creation flow was entered from.
 *
 * **Temporary stand-in**, for the same reason and with the same declaration order as
 * [StockGuildTemplate]; the reference declares it at
 * `com.discord.widgets.guilds.create.CreateGuildTrigger`.
 */
enum class CreateGuildTrigger {
    NUF,
    IN_APP,
    DIRECTORY_CHANNEL,
}
