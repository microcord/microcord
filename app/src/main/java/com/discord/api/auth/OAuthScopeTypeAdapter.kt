package com.discord.api.auth

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Maps a scope name onto the matching [OAuthScope] singleton.
 *
 * An unrecognised name becomes [OAuthScope.Invalid] rather than `null`, so the authorize screen
 * can still show a scope this build predates.
 *
 * The reference builds the lookup table by reflecting over the sealed hierarchy; the table is
 * listed explicitly here because this build does not ship `kotlin-reflect`.
 */
class OAuthScopeTypeAdapter : TypeAdapter<OAuthScope>() {
    override fun read(reader: JsonReader): OAuthScope? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val scopeName = reader.nextString()
        return NAME_TO_SCOPE[scopeName] ?: OAuthScope.Invalid(scopeName)
    }

    override fun write(writer: JsonWriter, value: OAuthScope?) {
        if (value == null) writer.nullValue() else writer.value(value.scopeName)
    }

    companion object {
        /**
         * Resolves a wire scope name.
         *
         * Returns [OAuthScope.Invalid] carrying the raw name for anything this build predates, so
         * the caller decides whether an unknown scope is fatal — the authorize screen treats it as
         * fatal, the settings list as a row it cannot describe.
         */
        fun scopeOf(scopeName: String): OAuthScope =
            NAME_TO_SCOPE[scopeName] ?: OAuthScope.Invalid(scopeName)

        private val NAME_TO_SCOPE: Map<String, OAuthScope> = listOf(
            OAuthScope.Identify,
            OAuthScope.IdentifyPremium,
            OAuthScope.Email,
            OAuthScope.Connections,
            OAuthScope.Guilds,
            OAuthScope.GuildsJoin,
            OAuthScope.GuildsMembersRead,
            OAuthScope.GuildsChannelsRead,
            OAuthScope.GdmJoin,
            OAuthScope.Rpc,
            OAuthScope.RpcNotificationsRead,
            OAuthScope.RpcVoiceRead,
            OAuthScope.RpcVoiceWrite,
            OAuthScope.RpcVideoRead,
            OAuthScope.RpcVideoWrite,
            OAuthScope.RpcScreenshareRead,
            OAuthScope.RpcScreenshareWrite,
            OAuthScope.RpcActivitiesWrite,
            OAuthScope.Bot,
            OAuthScope.WebhookIncoming,
            OAuthScope.MessagesRead,
            OAuthScope.ApplicationsBuildsUpload,
            OAuthScope.ApplicationsBuildsRead,
            OAuthScope.ApplicationsCommands,
            OAuthScope.ApplicationsCommandsUpdate,
            OAuthScope.ApplicationsCommandsPermissionsUpdate,
            OAuthScope.ApplicationsStoreUpdate,
            OAuthScope.ApplicationsEntitlements,
            OAuthScope.ApplicationIdentitiesWrite,
            OAuthScope.ManagedPlatformApplicationIdentitiesWrite,
            OAuthScope.ActivitiesRead,
            OAuthScope.ActivitiesWrite,
            OAuthScope.ActivitiesInvitesWrite,
            OAuthScope.RelationshipsRead,
            OAuthScope.RelationshipsWrite,
            OAuthScope.PresencesRead,
            OAuthScope.PresencesWrite,
            OAuthScope.Voice,
            OAuthScope.DMChannelsRead,
            OAuthScope.DMChannelsMessagesRead,
            OAuthScope.DMChannelsMessagesWrite,
            OAuthScope.RoleConnectionsWrite,
            OAuthScope.GatewayConnect,
            OAuthScope.OpenId,
            OAuthScope.PaymentSourcesCountryCode,
            OAuthScope.LobbiesWrite,
            OAuthScope.AccountGlobalNameUpdate,
            OAuthScope.SdkSocialLayer,
            OAuthScope.SdkSocialLayerPresence,
        ).associateBy { it.scopeName }
    }
}
