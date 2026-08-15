package com.discord.widgets.integrations

import android.content.Context
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewFlipper
import com.discord.models.domain.ModelWebhook
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import com.discord.widgets.settings.SettingsStrings
import com.facebook.drawee.view.SimpleDraweeView
import rx.Observable

/**
 * The webhooks of one channel or of a whole guild, filtered to a single [ModelWebhook] type.
 *
 * Announcement follows are webhooks of type [ModelWebhook.TYPE_CHANNEL_FOLLOWER], so "Channels
 * Followed" is this same screen with the other filter; unfollowing is deleting the webhook.
 */
class WidgetWebhooks : AppFragment(R.layout.widget_webhooks) {

    private val guildId: Long get() = mostRecentIntent.getLongExtra(ARG_GUILD_ID, -1L)
    private val channelId: Long get() = mostRecentIntent.getLongExtra(ARG_CHANNEL_ID, 0L)
    private val webhookType: Int
        get() = mostRecentIntent.getIntExtra(ARG_TYPE, ModelWebhook.TYPE_INCOMING)

    private lateinit var flipper: AppViewFlipper
    private lateinit var list: LinearLayout
    private lateinit var empty: View
    private var createTargetChannelId: Long = 0L

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(
            if (webhookType == ModelWebhook.TYPE_CHANNEL_FOLLOWER) {
                R.string.integrations_channel_following
            } else {
                R.string.webhooks
            },
        )
        flipper = view.findViewById(R.id.webhooks_flipper)
        list = view.findViewById(R.id.webhooks_list)
        empty = view.findViewById(R.id.webhooks_empty)
        view.findViewById<TextView>(R.id.webhooks_empty_title).setText(
            if (webhookType == ModelWebhook.TYPE_CHANNEL_FOLLOWER) {
                R.string.settings_channels_followed_empty_title
            } else {
                R.string.settings_webhooks_empty_title
            },
        )
        val description = view.findViewById<TextView>(R.id.webhooks_description)
        description.movementMethod = LinkMovementMethod.getInstance()
        description.text =
            if (webhookType == ModelWebhook.TYPE_CHANNEL_FOLLOWER) {
                SettingsStrings.render(
                    requireContext(),
                    R.string.integrations_channel_following_description,
                    "helpdeskArticle" to CHANNEL_FOLLOWING_ARTICLE_URL,
                )
            } else {
                SettingsStrings.render(
                    requireContext(),
                    R.string.settings_webhooks_intro,
                    "articleURL" to WEBHOOKS_ARTICLE_URL,
                ).trim()
            }
        view.findViewById<TextView>(R.id.webhooks_create).apply {
            isVisible = webhookType == ModelWebhook.TYPE_INCOMING
            setOnClickListener { createWebhook() }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        observeCreateTarget().ui(this).appSubscribe(WidgetWebhooks::class.java) {
            createTargetChannelId = it
            view?.findViewById<TextView>(R.id.webhooks_create)?.isEnabled = it != 0L
        }
        fetch()
    }

    private fun fetch() {
        val request = if (channelId != 0L) {
            RestAPI.api.getChannelWebhooks(channelId)
        } else {
            RestAPI.api.getGuildWebhooks(guildId)
        }
        request.restSubscribeOn().ui(this)
            .appSubscribe(
                WidgetWebhooks::class.java,
                context = context,
                errorHandler = { render(emptyList()) },
            ) { render(it) }
    }

    private fun render(webhooks: List<ModelWebhook>) {
        val filtered = webhooks.filter { it.type == webhookType }
        flipper.displayedChild = FLIPPER_CONTENT
        empty.isVisible = filtered.isEmpty()
        list.removeAllViews()
        filtered.forEach { webhook -> list.addView(buildRow(webhook)) }
    }

    private fun buildRow(webhook: ModelWebhook): View {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.widget_webhooks_list_item, list, false)
        row.findViewById<SimpleDraweeView>(R.id.webhook_item_avatar).setImageURI(
            IconUtils.getForUser(webhook.id, webhook.avatar, isWebhook = true),
        )
        row.findViewById<TextView>(R.id.webhook_item_name).text = webhook.name
        val createdAt = TimeUtils.renderUtcDate(SnowflakeUtils.toTimestamp(webhook.id), requireContext())
        val author = webhook.user?.username
        row.findViewById<TextView>(R.id.webhook_item_subtitle).text = if (author != null) {
            SettingsStrings.render(
                requireContext(),
                R.string.integration_created_user_date,
                "timestamp" to createdAt,
                "user" to author,
            )
        } else {
            SettingsStrings.render(
                requireContext(),
                R.string.integration_created_date,
                "timestamp" to createdAt,
            )
        }
        row.setOnClickListener { WidgetWebhookEdit.launch(requireContext(), webhook) }
        return row
    }

    private fun createWebhook() {
        val target = createTargetChannelId.takeIf { it != 0L } ?: return
        RestAPI.api.createWebhook(target, RestAPIParams.CreateWebhook(DEFAULT_NAMES.random()))
            .restSubscribeOn().ui(this)
            .appSubscribe(WidgetWebhooks::class.java, context = context) { webhook ->
                WidgetWebhookEdit.launch(requireContext(), webhook)
            }
    }

    /** Channel settings creates in place; server settings falls back to the first channel it may use. */
    private fun observeCreateTarget(): Observable<Long> {
        if (channelId != 0L) return Observable.just(channelId)
        return Observable.combineLatest(
            StoreStream.getChannels().observeChannelsForGuild(guildId),
            StoreStream.getPermissions().observeChannelPermissionsForGuild(guildId),
        ) { channels: Map<Long, Channel>, permissions: Map<Long, Long> ->
            channels.values
                .filter { it.isGuildTextyChannel() || it.isGuildVocalChannel() }
                .sortedBy { it.position }
                .firstOrNull { PermissionUtils.can(Permission.MANAGE_WEBHOOKS, permissions[it.id]) }
                ?.id ?: 0L
        }.distinctUntilChanged()
    }

    companion object {
        private const val ARG_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val ARG_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val ARG_TYPE = "com.discord.intent.extra.EXTRA_WEBHOOK_TYPE"
        private const val FLIPPER_CONTENT = 1
        private const val WEBHOOKS_ARTICLE_URL = "https://support.discord.com/hc/en-us/articles/228383668"
        private const val CHANNEL_FOLLOWING_ARTICLE_URL =
            "https://support.discord.com/hc/en-us/articles/360028384531"
        private val DEFAULT_NAMES = listOf("Spidey Bot", "Captain Hook")

        fun launch(context: Context, guildId: Long, channelId: Long, type: Int) = AppScreenRouter.launch(
            context,
            WidgetWebhooks::class.java,
            Intent()
                .putExtra(ARG_GUILD_ID, guildId)
                .putExtra(ARG_CHANNEL_ID, channelId)
                .putExtra(ARG_TYPE, type),
        )
    }
}
