package com.discord.widgets.applicationdirectory

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.api.applicationdirectory.ApplicationDirectoryApplication.Companion.GUILD_INSTALL
import com.discord.api.applicationdirectory.ApplicationDirectoryApplication.Companion.USER_INSTALL
import com.discord.api.permission.Permission
import com.discord.dialogs.SelectorDialog
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.intent.ExternalUriPolicy
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.auth.WidgetOauth2Authorize
import com.discord.widgets.notice.WidgetNoticeDialog

object ApplicationInstallCoordinator {
    data class InstallRequest(
        val applicationId: Long,
        val scopes: List<String>?,
        val permissions: String?,
        val integrationType: Int,
        val guildId: Long? = null,
    ) {
        fun authorizeParameters(): Map<String, String> = buildMap {
            put("client_id", applicationId.toString())
            scopes?.let { put("scope", it.joinToString(" ")) }
            put("integration_type", integrationType.toString())
            put("internal_referrer", "true")
            permissions?.let { put("permissions", it) }
            guildId?.let {
                put("guild_id", it.toString())
                put("disable_guild_select", "true")
            }
        }

        fun authorizeUri(): Uri = WidgetOauth2Authorize.authorizeUri(authorizeParameters())
    }

    fun install(
        context: Context,
        fragmentManager: FragmentManager,
        application: ApplicationDirectoryApplication,
        oauthLauncher: ActivityResultLauncher<Intent>?,
    ) {
        application.customInstallUrl?.let { customUrl ->
            if (ExternalUriPolicy.isAllowed(customUrl)) UriHandler.handle(context, customUrl)
            else showMessage(context, fragmentManager, R.string.application_directory_install_url_invalid)
            return
        }
        val types = installTargets(application)
        when (types.size) {
            0 -> showMessage(context, fragmentManager, R.string.application_directory_not_installable)
            1 -> selectTarget(context, fragmentManager, application, types.single(), oauthLauncher)
            else -> SelectorDialog.show(
                fragmentManager,
                context.getString(R.string.application_directory_choose_install_type),
                Array<CharSequence>(types.size) { index ->
                    context.getString(
                        if (types[index] == USER_INSTALL) R.string.application_directory_install_user
                        else R.string.application_directory_install_guild,
                    )
                },
            ) { index -> selectTarget(context, fragmentManager, application, types[index], oauthLauncher) }
        }
    }

    /**
     * Installs from a bare `oauth2/authorize?client_id=` link or an app link card: like Discord, it
     * reads how the application installs from its public profile, then runs [install].
     */
    fun install(context: Context, fragmentManager: FragmentManager, applicationId: Long) {
        val api = RestAPI.applicationDirectoryApiOrNull ?: return
        api.publicApplication(applicationId).restSubscribeOn().ui().appSubscribe(
            errorClass = ApplicationInstallCoordinator::class.java,
            context = context,
        ) { application -> install(context, fragmentManager, application, null) }
    }

    internal fun installTargets(application: ApplicationDirectoryApplication): List<Int> =
        listOf(USER_INSTALL, GUILD_INSTALL).filter(application.supportedIntegrationTypes::contains)

    internal fun eligibleGuilds(
        guilds: Collection<Guild>,
        guildPermissions: Map<Long, Long>,
        userId: Long,
    ): List<Guild> = guilds.filter { guild ->
        guild.ownerId == userId || guildPermissions[guild.id]?.let {
            it and (Permission.ADMINISTRATOR or Permission.MANAGE_GUILD) != 0L
        } == true
    }.sortedBy { it.name.lowercase() }

    private fun selectTarget(
        context: Context,
        fragmentManager: FragmentManager,
        application: ApplicationDirectoryApplication,
        integrationType: Int,
        oauthLauncher: ActivityResultLauncher<Intent>?,
    ) {
        val params = application.installParams(integrationType)
        if (integrationType == USER_INSTALL) {
            authorize(
                context, oauthLauncher,
                InstallRequest(application.id, params?.scopes, params?.permissions, USER_INSTALL).authorizeUri(),
            )
            return
        }
        if (integrationType != GUILD_INSTALL) {
            showMessage(context, fragmentManager, R.string.application_directory_not_installable)
            return
        }
        val guilds = eligibleGuilds(
            StoreStream.getGuilds().getGuilds().values,
            StoreStream.getPermissions().getGuildPermissions(),
            StoreStream.getUsers().getMe().id,
        )
        if (guilds.isEmpty()) {
            showMessage(context, fragmentManager, R.string.application_directory_no_eligible_guilds)
            return
        }
        SelectorDialog.show(
            fragmentManager,
            context.getString(R.string.application_directory_choose_guild),
            Array<CharSequence>(guilds.size) { guilds[it].name },
        ) { index ->
            authorize(
                context, oauthLauncher,
                InstallRequest(
                    application.id,
                    params?.scopes,
                    params?.permissions,
                    GUILD_INSTALL,
                    guilds[index].id,
                ).authorizeUri(),
            )
        }
    }

    private fun authorize(context: Context, launcher: ActivityResultLauncher<Intent>?, uri: Uri) =
        if (launcher != null) WidgetOauth2Authorize.launch(context, launcher, uri)
        else WidgetOauth2Authorize.launch(context, uri)

    private fun showMessage(context: Context, fragmentManager: FragmentManager, message: Int) {
        WidgetNoticeDialog.Builder(context)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok)
            .setCancelable(true)
            .show(fragmentManager)
    }
}
