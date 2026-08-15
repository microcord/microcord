package com.discord.widgets.integrations

import android.content.Context
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.models.domain.ModelWebhook
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.settings.SettingsStrings

/** A channel's webhooks and the announcement channels it republishes. */
class WidgetChannelSettingsIntegrations : AppFragment(R.layout.widget_channel_settings_integrations) {

    private val channelId: Long get() = mostRecentIntent.getLongExtra(ARG_CHANNEL_ID, -1L)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.integrations)
        val description = view.findViewById<TextView>(R.id.channel_integrations_description)
        description.movementMethod = LinkMovementMethod.getInstance()
        description.text = SettingsStrings.render(
            requireContext(),
            R.string.integrations_overview_description_channel,
            "helpdeskArticle" to INTEGRATIONS_ARTICLE_URL,
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getChannels().observeChannel(channelId).ui(this)
            .appSubscribe(WidgetChannelSettingsIntegrations::class.java) { channel ->
                if (channel == null) {
                    appActivity?.finish()
                    return@appSubscribe
                }
                setActionBarSubtitle(channel.getDisplayNameOrDefault(requireContext()))
                val root = view ?: return@appSubscribe
                root.findViewById<View>(R.id.channel_integrations_webhooks).setOnClickListener {
                    WidgetWebhooks.launch(requireContext(), channel.guildId, channelId, ModelWebhook.TYPE_INCOMING)
                }
                root.findViewById<View>(R.id.channel_integrations_channels_followed).setOnClickListener {
                    WidgetWebhooks.launch(
                        requireContext(),
                        channel.guildId,
                        channelId,
                        ModelWebhook.TYPE_CHANNEL_FOLLOWER,
                    )
                }
            }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val INTEGRATIONS_ARTICLE_URL =
            "https://support.discord.com/hc/en-us/articles/360045093012"

        fun launch(context: Context, channelId: Long) = AppScreenRouter.launch(
            context,
            WidgetChannelSettingsIntegrations::class.java,
            Intent().putExtra(ARG_CHANNEL_ID, channelId),
        )
    }
}
