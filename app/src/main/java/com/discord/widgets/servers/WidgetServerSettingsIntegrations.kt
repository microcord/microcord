package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.domain.ModelWebhook
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.integrations.WidgetWebhooks
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import rx.Observable

/**
 * Entry point for everything that posts into a guild from outside it: webhooks, followed
 * announcement channels, and the Twitch/YouTube subscriber sync.
 */
class WidgetServerSettingsIntegrations : AppFragment(R.layout.widget_server_settings_integrations) {

    private val guildId: Long
        get() = mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.integrations)
        val description = view.findViewById<TextView>(R.id.integrations_description)
        description.movementMethod = LinkMovementMethod.getInstance()
        description.text = SettingsStrings.render(
            requireContext(),
            R.string.integrations_overview_description_guild,
            "helpdeskArticle" to INTEGRATIONS_ARTICLE_URL,
        )
        view.findViewById<View>(R.id.integrations_webhooks).setOnClickListener {
            WidgetWebhooks.launch(requireContext(), guildId, 0L, ModelWebhook.TYPE_INCOMING)
        }
        view.findViewById<View>(R.id.integrations_channels_followed).setOnClickListener {
            WidgetWebhooks.launch(requireContext(), guildId, 0L, ModelWebhook.TYPE_CHANNEL_FOLLOWER)
        }
        view.findViewById<View>(R.id.integrations_twitch).setOnClickListener {
            WidgetServerSettingsIntegrationPlatform.launch(requireContext(), guildId, ModelGuildIntegration.TYPE_TWITCH)
        }
        view.findViewById<View>(R.id.integrations_youtube).setOnClickListener {
            WidgetServerSettingsIntegrationPlatform.launch(requireContext(), guildId, ModelGuildIntegration.TYPE_YOUTUBE)
        }
    }

    override fun onResume() {
        super.onResume()
        StoreStream.getGuildIntegrations().onIntegrationScreenOpened(guildId)
        Model.observe(guildId).ui(this).appSubscribe(WidgetServerSettingsIntegrations::class.java) {
            if (it == null) appActivity?.finish() else render(it)
        }
    }

    override fun onPause() {
        StoreStream.getGuildIntegrations().onIntegrationScreenClosed()
        super.onPause()
    }

    private fun render(model: Model) {
        val root = view ?: return
        setActionBarSubtitle(model.guildName)
        root.findViewById<View>(R.id.integrations_webhooks).isVisible = model.canManageWebhooks
        root.findViewById<View>(R.id.integrations_channels_followed).isVisible = model.canManageWebhooks
        bindPlatform(
            root.findViewById(R.id.integrations_twitch),
            root.findViewById(R.id.integrations_twitch_subtitle),
            model.canManageGuild && model.platforms.contains(ModelGuildIntegration.TYPE_TWITCH),
            R.string.integrations_twitch,
        )
        bindPlatform(
            root.findViewById(R.id.integrations_youtube),
            root.findViewById(R.id.integrations_youtube_subtitle),
            model.canManageGuild && model.platforms.contains(ModelGuildIntegration.TYPE_YOUTUBE),
            R.string.integrations_youtube,
        )
    }

    private fun bindPlatform(row: View, subtitle: TextView, visible: Boolean, platformNameRes: Int) {
        row.isVisible = visible
        if (visible) {
            subtitle.text = SettingsStrings.render(
                requireContext(),
                R.string.integration_connected_platform_accounts,
                "platformName" to getString(platformNameRes),
            )
        }
    }

    private data class Model(
        val guildName: String,
        val canManageWebhooks: Boolean,
        val canManageGuild: Boolean,
        val platforms: Set<String>,
    ) {
        companion object {
            fun observe(guildId: Long): Observable<Model?> = Observable.combineLatest(
                StoreStream.getGuilds().observeGuild(guildId),
                StoreStream.getUsers().observeMe(),
                StoreStream.getPermissions().observePermissionsForGuild(guildId),
                StoreStream.getGuildIntegrations().get(guildId),
            ) { guild: Guild?, me: MeUser, permissions: Long?, integrations: Map<Long, ModelGuildIntegration>? ->
                if (guild == null) return@combineLatest null
                val canManageWebhooks = PermissionUtils.canAndIsElevated(
                    Permission.MANAGE_WEBHOOKS, permissions, me.mfaEnabled, guild.mfaLevel,
                )
                val canManageGuild = PermissionUtils.canAndIsElevated(
                    Permission.MANAGE_GUILD, permissions, me.mfaEnabled, guild.mfaLevel,
                )
                if (!canManageWebhooks && !canManageGuild) return@combineLatest null
                Model(
                    guild.name,
                    canManageWebhooks,
                    canManageGuild,
                    integrations.orEmpty().values
                        .filter { it.type in ModelGuildIntegration.SUPPORTED_TYPES }
                        .mapTo(HashSet()) { it.type },
                )
            }.computationLatest().distinctUntilChanged()
        }
    }

    companion object {
        internal const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L
        private const val INTEGRATIONS_ARTICLE_URL =
            "https://support.discord.com/hc/en-us/articles/360045093012"

        @JvmStatic
        fun create(context: Context, guildId: Long) = launch(context, guildId)

        fun launch(context: Context, guildId: Long) {
            StoreStream.getAnalytics().onGuildSettingsPaneViewed("INTEGRATIONS", guildId)
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsIntegrations::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
