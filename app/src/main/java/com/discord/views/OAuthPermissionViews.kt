package com.discord.views

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.auth.OAuthScope
import com.discord.utilities.views.SimpleRecyclerAdapter

/**
 * Rendering for the OAuth2 scope rows shown on the authorization screen and on the authorized
 * applications settings screen.
 *
 * Each scope maps to a human-readable permission sentence. A scope newer than this build has none,
 * and the two screens want opposite things: the settings list shows the raw wire name rather than
 * hiding a permission the user has already granted, while the authorization screen throws
 * [InvalidScopeException] and refuses to grant what it cannot describe.
 */
object OAuthPermissionViews {

    /** Thrown when a scope has no user-facing description, i.e. the client does not understand it. */
    class InvalidScopeException(val scope: String) : IllegalArgumentException("invalid scope: $scope")

    /** Falls back to the raw wire name, so a scope newer than this build still lists something. */
    fun setScopePermissionText(textView: TextView, scopeName: String) {
        val description = scopePermissionStringResOrNull(scopeName)
        if (description != null) textView.setText(description) else textView.text = scopeName
    }

    fun setScopePermissionText(textView: TextView, scope: OAuthScope) =
        setScopePermissionText(textView, scope.wireName)

    /** @throws InvalidScopeException when the scope is not one this client version knows about. */
    @StringRes
    fun scopePermissionStringRes(scope: OAuthScope): Int =
        scopePermissionStringRes(scope.wireName)

    /** @throws InvalidScopeException when the scope is not one this client version knows about. */
    @StringRes
    fun scopePermissionStringRes(scopeName: String): Int =
        scopePermissionStringResOrNull(scopeName) ?: throw InvalidScopeException(scopeName)

    /** Null when this build has no sentence for [scopeName]. */
    @StringRes
    fun scopePermissionStringResOrNull(scopeName: String): Int? = when (scopeName) {
        "identify" -> R.string.scope_identify
        "email" -> R.string.scope_email
        "connections" -> R.string.scope_connections
        "guilds" -> R.string.scope_guilds
        "guilds.join" -> R.string.scope_guilds_join
        "guilds.members.read" -> R.string.scope_guilds_members_read
        "gdm.join" -> R.string.scope_gdm_join
        "bot" -> R.string.scope_bot
        "webhook.incoming" -> R.string.scope_webhook_incoming
        "rpc" -> R.string.scope_rpc
        "rpc.notifications.read" -> R.string.scope_rpc_notifications_read
        "rpc.voice.read" -> R.string.scope_rpc_voice_read
        "rpc.voice.write" -> R.string.scope_rpc_voice_write
        "rpc.activities.write" -> R.string.scope_rpc_activities_write
        "messages.read" -> R.string.scope_messages_read
        "applications.builds.upload" -> R.string.scope_applications_builds_upload
        "applications.builds.read" -> R.string.scope_applications_builds_read
        "applications.commands" -> R.string.scope_applications_commands
        "applications.commands.update" -> R.string.scope_applications_commands_update
        "applications.store.update" -> R.string.scope_applications_store_update
        "applications.entitlements" -> R.string.scope_applications_entitlements
        "activities.read" -> R.string.scope_activities_read
        "activities.write" -> R.string.scope_activities_write
        "relationships.read" -> R.string.scope_relationships_read
        "voice" -> R.string.scope_voice
        "dm_channels.read" -> R.string.scope_dm_channels_read
        "identify.premium" -> R.string.scope_identify_premium
        "guilds.channels.read" -> R.string.scope_guilds_channels_read
        "rpc.video.read" -> R.string.scope_rpc_video_read
        "rpc.video.write" -> R.string.scope_rpc_video_write
        "rpc.screenshare.read" -> R.string.scope_rpc_screenshare_read
        "rpc.screenshare.write" -> R.string.scope_rpc_screenshare_write
        "applications.commands.permissions.update" -> R.string.scope_applications_commands_permissions_update
        "application_identities.write" -> R.string.scope_application_identities_write
        "managed_platform.application_identities.write" ->
            R.string.scope_managed_platform_application_identities_write
        "activities.invites.write" -> R.string.scope_activities_invites_write
        "relationships.write" -> R.string.scope_relationships_write
        "presences.read" -> R.string.scope_presences_read
        "presences.write" -> R.string.scope_presences_write
        "dm_channels.messages.read" -> R.string.scope_dm_channels_messages_read
        "dm_channels.messages.write" -> R.string.scope_dm_channels_messages_write
        "role_connections.write" -> R.string.scope_role_connections_write
        "gateway.connect" -> R.string.scope_gateway_connect
        "openid" -> R.string.scope_openid
        "payment_sources.country_code" -> R.string.scope_payment_sources_country_code
        // `account.global_name.update`, `lobbies.write` and the umbrella scopes have no single
        // sentence; the first two fall through to their wire name, the umbrellas expand below.
        else -> null
    }

    /**
     * The permission lines to show for a granted set, in the order the server returned them.
     *
     * Not one line per scope: the umbrella `sdk.social_layer*` scopes expand to several,
     * `applications.commands` implies being DMed, and `guilds.members.read` reads differently once
     * `voice` is also granted.
     */
    fun scopeDescriptions(context: Context, scopes: List<OAuthScope>): List<CharSequence> {
        val granted = scopes.map { it.wireName }
        return scopes.flatMap { scope ->
            describe(context, scope.wireName, granted) +
                if (scope.wireName == OAuthScope.ApplicationsCommands.scopeName) {
                    listOf(context.getString(R.string.scope_dm_you))
                } else {
                    emptyList()
                }
        }
    }

    private fun describe(
        context: Context,
        scopeName: String,
        granted: List<String>,
    ): List<CharSequence> = when (scopeName) {
        OAuthScope.GuildsMembersRead.scopeName -> listOf(
            context.getString(
                if (OAuthScope.Voice.scopeName in granted) {
                    R.string.scope_guilds_members_read_voice
                } else {
                    R.string.scope_guilds_members_read
                },
            ),
        )
        OAuthScope.SdkSocialLayerPresence.scopeName -> socialLayer(
            context,
            granted,
            R.string.scope_sdk_social_layer_1,
            R.string.scope_sdk_social_layer_3,
            R.string.scope_sdk_social_layer_4,
            R.string.scope_sdk_social_layer_game_invites,
        )
        OAuthScope.SdkSocialLayer.scopeName -> socialLayer(
            context,
            granted,
            R.string.scope_sdk_social_layer_1,
            R.string.scope_sdk_social_layer_2,
            R.string.scope_sdk_social_layer_3,
            R.string.scope_sdk_social_layer_4,
            R.string.scope_sdk_social_layer_5,
        )
        else -> listOf(scopePermissionStringResOrNull(scopeName)?.let(context::getString) ?: scopeName)
    }

    /** The umbrella covers `application_identities.write`; listing it twice would be noise. */
    private fun socialLayer(
        context: Context,
        granted: List<String>,
        @StringRes vararg lines: Int,
    ): List<CharSequence> = lines.map(context::getString) +
        if (OAuthScope.ApplicationIdentitiesWrite.scopeName in granted) {
            emptyList()
        } else {
            listOf(context.getString(R.string.scope_application_identities_write))
        }

    /**
     * Compact scope row (`oauth_token_permission_list_item`), a single check-marked line of text.
     * Used by the authorized applications list.
     */
    class ScopeViewHolder(private val scopeText: TextView) :
        SimpleRecyclerAdapter.ViewHolder<CharSequence>(scopeText) {

        override fun bind(data: CharSequence) {
            scopeText.text = data
        }

        companion object {
            fun create(inflater: LayoutInflater, parent: ViewGroup): ScopeViewHolder =
                ScopeViewHolder(
                    inflater.inflate(
                        R.layout.oauth_token_permission_list_item,
                        parent,
                        false,
                    ) as TextView,
                )
        }
    }
}
