package com.discord.views

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.auth.OAuthScope

/**
 * Rendering for the OAuth2 scope rows shown on the authorization screen and on the authorized
 * applications settings screen.
 *
 * Each scope maps to a human-readable permission sentence. An unrecognized scope is a hard error
 * rather than a silently skipped row: the authorization screen catches [InvalidScopeException] and
 * refuses to authorize an application whose permissions it cannot describe.
 *
 * Scopes are addressed by their wire name (`identify`, `guilds.join`, ...); the [OAuthScope]
 * overloads below are the type-safe entry point and simply unwrap `scopeName`, matching the
 * reference's own signature. [OAuthScope.Invalid] is rejected using the raw value it carries, not
 * the literal `"invalid"` its `scopeName` reports, so the error names the scope the server sent.
 */
object OAuthPermissionViews {

    /** Thrown when a scope has no user-facing description, i.e. the client does not understand it. */
    class InvalidScopeException(val scope: String) : IllegalArgumentException("invalid scope: $scope")

    /**
     * Sets [textView] to the permission sentence for [scopeName].
     *
     * @throws InvalidScopeException when the scope is not one this client version knows about.
     */
    fun setScopePermissionText(textView: TextView, scopeName: String) {
        textView.setText(scopePermissionStringRes(scopeName))
    }

    /** @throws InvalidScopeException when the scope is not one this client version knows about. */
    fun setScopePermissionText(textView: TextView, scope: OAuthScope) {
        textView.setText(scopePermissionStringRes(scope))
    }

    /** @throws InvalidScopeException when the scope is not one this client version knows about. */
    @StringRes
    fun scopePermissionStringRes(scope: OAuthScope): Int = when (scope) {
        is OAuthScope.Invalid -> throw InvalidScopeException(scope.rawValue)
        else -> scopePermissionStringRes(scope.scopeName)
    }

    /**
     * @throws InvalidScopeException when the scope is not one this client version knows about.
     */
    @StringRes
    fun scopePermissionStringRes(scopeName: String): Int = when (scopeName) {
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
        else -> throw InvalidScopeException(scopeName)
    }

    /**
     * Compact scope row (`oauth_token_permission_list_item`), a single check-marked line of text.
     * Used by the authorized applications list.
     */
    class ScopeViewHolder(private val scopeText: TextView) : RecyclerView.ViewHolder(scopeText) {

        fun bind(scopeName: String) =
            OAuthPermissionViews.setScopePermissionText(scopeText, scopeName)

        fun bind(scope: OAuthScope) =
            OAuthPermissionViews.setScopePermissionText(scopeText, scope)

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
