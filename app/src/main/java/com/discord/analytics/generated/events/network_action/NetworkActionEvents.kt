package com.discord.analytics.generated.events.network_action

import com.discord.analytics.generated.traits.TrackLocationMetadata
import com.discord.analytics.generated.traits.TrackLocationMetadataReceiver
import com.discord.analytics.generated.traits.TrackNetworkMetadata
import com.discord.analytics.generated.traits.TrackNetworkMetadataReceiver
import com.discord.api.science.AnalyticsSchema

/*
 * The reference ships 1,862 generated schema classes under `com.discord.analytics.generated`, one
 * file each, emitted from Discord's internal schema catalogue. This project reconstructs only the
 * schemas that a reconstructed call site actually raises; adding a new one means copying the shape
 * (constructor fields + `analyticsSchemaTypeName` + the receiver interfaces it implements) out of
 * the reference for that one class, not importing the catalogue.
 *
 * A `network_action_*` schema is raised through `Observable<Response<T>>.logNetworkAction { ... }`:
 * the call site returns the schema, and the operator fills in `trackNetworkMetadata` from the
 * response (or the failure) before handing it to the tracker.
 */

/** `network_action_user_login` — raised by `StoreAuthentication.login`. */
data class TrackNetworkActionUserLogin(
    val inviteCode: CharSequence? = null,
    val isMultiAccount: Boolean? = null,
) : AnalyticsSchema, TrackLocationMetadataReceiver, TrackNetworkMetadataReceiver {

    override val analyticsSchemaTypeName: String = "network_action_user_login"

    override val trackLocationMetadata: TrackLocationMetadata? = null

    override var trackNetworkMetadata: TrackNetworkMetadata? = null
}

/** `network_action_directory_guild_entry_create` — raised by `WidgetHubDescriptionViewModel.addServer`. */
data class TrackNetworkActionDirectoryGuildEntryCreate(
    val directoryChannelId: Long? = null,
    val guildId: Long? = null,
    val primaryCategoryId: Long? = null,
) : AnalyticsSchema, TrackLocationMetadataReceiver, TrackNetworkMetadataReceiver {

    override val analyticsSchemaTypeName: String = "network_action_directory_guild_entry_create"

    override val trackLocationMetadata: TrackLocationMetadata? = null

    override var trackNetworkMetadata: TrackNetworkMetadata? = null
}

/** `network_action_hub_email_verify` — raised when a hub e-mail verification code is submitted. */
class TrackNetworkActionHubEmailVerify :
    AnalyticsSchema, TrackLocationMetadataReceiver, TrackNetworkMetadataReceiver {

    override val analyticsSchemaTypeName: String = "network_action_hub_email_verify"

    override val trackLocationMetadata: TrackLocationMetadata? = null

    override var trackNetworkMetadata: TrackNetworkMetadata? = null
}

/** `network_action_hub_email_verify_send` — raised when a hub verification e-mail is requested. */
data class TrackNetworkActionHubEmailVerifySend(
    val hasMatchingGuild: Boolean? = null,
) : AnalyticsSchema, TrackLocationMetadataReceiver, TrackNetworkMetadataReceiver {

    override val analyticsSchemaTypeName: String = "network_action_hub_email_verify_send"

    override val trackLocationMetadata: TrackLocationMetadata? = null

    override var trackNetworkMetadata: TrackNetworkMetadata? = null
}
