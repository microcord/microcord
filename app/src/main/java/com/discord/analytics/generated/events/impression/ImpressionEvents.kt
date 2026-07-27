package com.discord.analytics.generated.events.impression

import com.discord.analytics.generated.traits.TrackChannel
import com.discord.analytics.generated.traits.TrackChannelReceiver
import com.discord.analytics.generated.traits.TrackGuild
import com.discord.analytics.generated.traits.TrackGuildReceiver
import com.discord.analytics.generated.traits.TrackImpressionMetadata
import com.discord.analytics.generated.traits.TrackImpressionMetadataReceiver
import com.discord.analytics.generated.traits.TrackLocationMetadata
import com.discord.analytics.generated.traits.TrackLocationMetadataReceiver
import com.discord.api.science.AnalyticsSchema

/*
 * An `impression_*` schema is raised by a screen's `LoggingConfig.impressionSchemaProvider`; the
 * `AppLogger` owned by `AppFragment` / `AppBottomSheet` decides whether the impression is new
 * enough to report. See the note in `network_action/NetworkActionEvents.kt` about why only the
 * schemas a reconstructed screen actually raises live here.
 */

/** `impression_hub_existing_guild_choose` — `WidgetHubAddServer.loggingConfig`. */
class TrackImpressionHubExistingGuildChoose :
    AnalyticsSchema,
    TrackGuildReceiver,
    TrackChannelReceiver,
    TrackLocationMetadataReceiver,
    TrackImpressionMetadataReceiver {

    override val analyticsSchemaTypeName: String = "impression_hub_existing_guild_choose"

    override var trackGuild: TrackGuild? = null

    override val trackChannel: TrackChannel? = null

    override val trackLocationMetadata: TrackLocationMetadata? = null

    override val trackImpressionMetadata: TrackImpressionMetadata? = null
}

/** `impression_hub_create_guild_customize` — `WidgetHubDescription.loggingConfig`, new guild. */
class TrackImpressionHubCreateGuildCustomize :
    AnalyticsSchema,
    TrackGuildReceiver,
    TrackChannelReceiver,
    TrackLocationMetadataReceiver,
    TrackImpressionMetadataReceiver {

    override val analyticsSchemaTypeName: String = "impression_hub_create_guild_customize"

    override var trackGuild: TrackGuild? = null

    override val trackChannel: TrackChannel? = null

    override val trackLocationMetadata: TrackLocationMetadata? = null

    override val trackImpressionMetadata: TrackImpressionMetadata? = null
}

/** `impression_hub_existing_guild_customize` — `WidgetHubDescription.loggingConfig`, existing guild. */
class TrackImpressionHubExistingGuildCustomize :
    AnalyticsSchema,
    TrackGuildReceiver,
    TrackChannelReceiver,
    TrackLocationMetadataReceiver,
    TrackImpressionMetadataReceiver {

    override val analyticsSchemaTypeName: String = "impression_hub_existing_guild_customize"

    override var trackGuild: TrackGuild? = null

    override val trackChannel: TrackChannel? = null

    override val trackLocationMetadata: TrackLocationMetadata? = null

    override val trackImpressionMetadata: TrackImpressionMetadata? = null
}

/** `impression_hub_email_signup` — `WidgetHubEmailFlow.loggingConfig`. */
data class TrackImpressionHubEmailSignup(
    val hasInvite: Boolean? = null,
) : AnalyticsSchema,
    TrackGuildReceiver,
    TrackChannelReceiver,
    TrackLocationMetadataReceiver,
    TrackImpressionMetadataReceiver {

    override val analyticsSchemaTypeName: String = "impression_hub_email_signup"

    override var trackGuild: TrackGuild? = null

    override val trackChannel: TrackChannel? = null

    override val trackLocationMetadata: TrackLocationMetadata? = null

    override val trackImpressionMetadata: TrackImpressionMetadata? = null
}

/** `impression_user_verify_phone` — `WidgetUserPhoneVerify.loggingConfig`. */
class TrackImpressionUserVerifyPhone :
    AnalyticsSchema,
    TrackGuildReceiver,
    TrackChannelReceiver,
    TrackLocationMetadataReceiver,
    TrackImpressionMetadataReceiver {

    override val analyticsSchemaTypeName: String = "impression_user_verify_phone"

    override var trackGuild: TrackGuild? = null

    override val trackChannel: TrackChannel? = null

    override val trackLocationMetadata: TrackLocationMetadata? = null

    override val trackImpressionMetadata: TrackImpressionMetadata? = null
}
