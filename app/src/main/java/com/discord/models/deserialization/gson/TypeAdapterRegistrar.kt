package com.discord.models.deserialization.gson

import com.discord.api.activity.ActivityPlatform
import com.discord.api.activity.ActivityPlatformTypeAdapter
import com.discord.api.activity.ActivityType
import com.discord.api.activity.ActivityTypeTypeAdapter
import com.discord.api.auth.RequiredAction
import com.discord.api.auth.RequiredActionTypeAdapter
import com.discord.api.friendsuggestions.FriendSuggestionReasonType
import com.discord.api.friendsuggestions.FriendSuggestionReasonTypeAdapter
import com.discord.api.guild.GuildExplicitContentFilter
import com.discord.api.guild.GuildExplicitContentFilterTypeAdapter
import com.discord.api.guild.GuildHubType
import com.discord.api.guild.GuildHubTypeTypeAdapter
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guild.GuildMaxVideoChannelUsersTypeAdapter
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.guild.GuildVerificationLevelTypeAdapter
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityTypeTypeAdapter
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.guildscheduledevent.GuildScheduledEventStatusTypeAdapter
import com.discord.api.premium.PremiumTier
import com.discord.api.premium.PremiumTierTypeAdapter
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.stageinstance.StageInstancePrivacyLevelTypeAdapter
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerFormatTypeTypeAdapter
import com.discord.api.sticker.StickerType
import com.discord.api.sticker.StickerTypeTypeAdapter
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.api.utcdatetime.UtcDateTimeTypeAdapter
import com.discord.api.user.NsfwAllowance
import com.discord.api.user.NsfwAllowanceTypeAdapter
import com.discord.api.user.Phone
import com.discord.api.user.PhoneTypeAdapter
import com.discord.models.experiments.dto.UserExperimentDto
import com.discord.nullserializable.NullSerializableTypeAdapterFactory
import com.discord.api.botuikit.ComponentTypeAdapterFactory
import com.google.gson.GsonBuilder

fun GsonBuilder.registerDiscordApiTypeAdapters(): GsonBuilder =
    registerTypeAdapterFactory(NullSerializableTypeAdapterFactory())
        .registerTypeAdapterFactory(ComponentTypeAdapterFactory())
        .registerTypeAdapterFactory(UserExperimentDto.TypeAdapterFactory)
        .registerTypeAdapter(ActivityPlatform::class.java, ActivityPlatformTypeAdapter())
        .registerTypeAdapter(ActivityType::class.java, ActivityTypeTypeAdapter())
        .registerTypeAdapter(RequiredAction::class.java, RequiredActionTypeAdapter())
        .registerTypeAdapter(FriendSuggestionReasonType::class.java, FriendSuggestionReasonTypeAdapter())
        .registerTypeAdapter(PremiumTier::class.java, PremiumTierTypeAdapter())
        .registerTypeAdapter(NsfwAllowance::class.java, NsfwAllowanceTypeAdapter())
        .registerTypeAdapter(Phone::class.java, PhoneTypeAdapter())
        .registerTypeAdapter(UtcDateTime::class.java, UtcDateTimeTypeAdapter())
        .registerTypeAdapter(StageInstancePrivacyLevel::class.java, StageInstancePrivacyLevelTypeAdapter())
        .registerTypeAdapter(StickerType::class.java, StickerTypeTypeAdapter())
        .registerTypeAdapter(StickerFormatType::class.java, StickerFormatTypeTypeAdapter())
        .registerTypeAdapter(GuildVerificationLevel::class.java, GuildVerificationLevelTypeAdapter())
        .registerTypeAdapter(GuildExplicitContentFilter::class.java, GuildExplicitContentFilterTypeAdapter())
        .registerTypeAdapter(GuildHubType::class.java, GuildHubTypeTypeAdapter())
        .registerTypeAdapter(GuildMaxVideoChannelUsers::class.java, GuildMaxVideoChannelUsersTypeAdapter())
        .registerTypeAdapter(GuildScheduledEventStatus::class.java, GuildScheduledEventStatusTypeAdapter())
        .registerTypeAdapter(GuildScheduledEventEntityType::class.java, GuildScheduledEventEntityTypeTypeAdapter())
