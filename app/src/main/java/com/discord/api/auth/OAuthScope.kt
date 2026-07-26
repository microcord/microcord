package com.discord.api.auth

/**
 * A single OAuth2 scope requested by a third-party application.
 *
 * Modelled as a sealed hierarchy rather than an enum so that [Invalid] can carry the raw wire
 * value of a scope this build does not know about, which the authorize screen still has to show.
 */
sealed class OAuthScope(val scopeName: String) {
    object Identify : OAuthScope("identify")
    object Email : OAuthScope("email")
    object Connections : OAuthScope("connections")
    object Guilds : OAuthScope("guilds")
    object GuildsJoin : OAuthScope("guilds.join")
    object GuildsMembersRead : OAuthScope("guilds.members.read")
    object GdmJoin : OAuthScope("gdm.join")
    object Rpc : OAuthScope("rpc")
    object RpcNotificationsRead : OAuthScope("rpc.notifications.read")
    object RpcVoiceRead : OAuthScope("rpc.voice.read")
    object RpcVoiceWrite : OAuthScope("rpc.voice.write")
    object RpcActivitiesWrite : OAuthScope("rpc.activities.write")
    object Bot : OAuthScope("bot")
    object WebhookIncoming : OAuthScope("webhook.incoming")
    object MessagesRead : OAuthScope("messages.read")
    object ApplicationsBuildsUpload : OAuthScope("applications.builds.upload")
    object ApplicationsBuildsRead : OAuthScope("applications.builds.read")
    object ApplicationsCommands : OAuthScope("applications.commands")
    object ApplicationsCommandsUpdate : OAuthScope("applications.commands.update")
    object ApplicationsStoreUpdate : OAuthScope("applications.store.update")
    object ApplicationsEntitlements : OAuthScope("applications.entitlements")
    object ActivitiesRead : OAuthScope("activities.read")
    object ActivitiesWrite : OAuthScope("activities.write")
    object RelationshipsRead : OAuthScope("relationships.read")
    object Voice : OAuthScope("voice")
    object DMChannelsRead : OAuthScope("dm_channels.read")

    /** A scope name this build does not recognise; [rawValue] is what the server actually sent. */
    data class Invalid(val rawValue: String) : OAuthScope("invalid")
}
