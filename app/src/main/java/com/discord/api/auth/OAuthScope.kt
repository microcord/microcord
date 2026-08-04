package com.discord.api.auth

/**
 * A single OAuth2 scope requested by a third-party application.
 *
 * Modelled as a sealed hierarchy rather than an enum so that [Invalid] can carry the raw wire
 * value of a scope this build does not know about, which the authorize screen still has to show.
 */
sealed class OAuthScope(val scopeName: String) {
    object Identify : OAuthScope("identify")
    object IdentifyPremium : OAuthScope("identify.premium")
    object Email : OAuthScope("email")
    object Connections : OAuthScope("connections")
    object Guilds : OAuthScope("guilds")
    object GuildsJoin : OAuthScope("guilds.join")
    object GuildsMembersRead : OAuthScope("guilds.members.read")
    object GuildsChannelsRead : OAuthScope("guilds.channels.read")
    object GdmJoin : OAuthScope("gdm.join")
    object Rpc : OAuthScope("rpc")
    object RpcNotificationsRead : OAuthScope("rpc.notifications.read")
    object RpcVoiceRead : OAuthScope("rpc.voice.read")
    object RpcVoiceWrite : OAuthScope("rpc.voice.write")
    object RpcVideoRead : OAuthScope("rpc.video.read")
    object RpcVideoWrite : OAuthScope("rpc.video.write")
    object RpcScreenshareRead : OAuthScope("rpc.screenshare.read")
    object RpcScreenshareWrite : OAuthScope("rpc.screenshare.write")
    object RpcActivitiesWrite : OAuthScope("rpc.activities.write")
    object Bot : OAuthScope("bot")
    object WebhookIncoming : OAuthScope("webhook.incoming")
    object MessagesRead : OAuthScope("messages.read")
    object ApplicationsBuildsUpload : OAuthScope("applications.builds.upload")
    object ApplicationsBuildsRead : OAuthScope("applications.builds.read")
    object ApplicationsCommands : OAuthScope("applications.commands")
    object ApplicationsCommandsUpdate : OAuthScope("applications.commands.update")
    object ApplicationsCommandsPermissionsUpdate : OAuthScope("applications.commands.permissions.update")
    object ApplicationsStoreUpdate : OAuthScope("applications.store.update")
    object ApplicationsEntitlements : OAuthScope("applications.entitlements")
    object ApplicationIdentitiesWrite : OAuthScope("application_identities.write")
    object ManagedPlatformApplicationIdentitiesWrite :
        OAuthScope("managed_platform.application_identities.write")
    object ActivitiesRead : OAuthScope("activities.read")
    object ActivitiesWrite : OAuthScope("activities.write")
    object ActivitiesInvitesWrite : OAuthScope("activities.invites.write")
    object RelationshipsRead : OAuthScope("relationships.read")
    object RelationshipsWrite : OAuthScope("relationships.write")
    object PresencesRead : OAuthScope("presences.read")
    object PresencesWrite : OAuthScope("presences.write")
    object Voice : OAuthScope("voice")
    object DMChannelsRead : OAuthScope("dm_channels.read")
    object DMChannelsMessagesRead : OAuthScope("dm_channels.messages.read")
    object DMChannelsMessagesWrite : OAuthScope("dm_channels.messages.write")
    object RoleConnectionsWrite : OAuthScope("role_connections.write")
    object GatewayConnect : OAuthScope("gateway.connect")
    object OpenId : OAuthScope("openid")
    object PaymentSourcesCountryCode : OAuthScope("payment_sources.country_code")
    object LobbiesWrite : OAuthScope("lobbies.write")
    object AccountGlobalNameUpdate : OAuthScope("account.global_name.update")

    /** Umbrella scopes; each expands to several permission lines rather than one. */
    object SdkSocialLayer : OAuthScope("sdk.social_layer")
    object SdkSocialLayerPresence : OAuthScope("sdk.social_layer_presence")

    /** A scope name this build does not recognise; [rawValue] is what the server actually sent. */
    data class Invalid(val rawValue: String) : OAuthScope("invalid") {
        override val wireName: String get() = rawValue
    }

    /** What the server called this scope. Differs from [scopeName] only for [Invalid]. */
    open val wireName: String get() = scopeName
}
