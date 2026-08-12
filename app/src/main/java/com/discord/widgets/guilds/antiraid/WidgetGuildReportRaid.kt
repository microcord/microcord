package com.discord.widgets.guilds.antiraid

import android.content.Context
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.View
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetGuildReportRaidBinding
import com.discord.i18n.I18nSpans
import com.discord.i18n.withClickableHook
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler

class WidgetGuildReportRaid : AppFragment(R.layout.widget_guild_report_raid) {
    private var _binding: WidgetGuildReportRaidBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val guildId get() = requireArguments().getLong(ARG_GUILD_ID)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetGuildReportRaidBinding.bind(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.guild_report_raid_modal_title)
        binding.reportRaidDescription.apply {
            text = I18nSpans.render(getString(R.string.guild_report_raid_modal_description))
                .withClickableHook(LEARN_MORE_HOOK, ContextCompat.getColor(context, R.color.link)) {
                    UriHandler.handle(context, SERVER_SAFETY_URL)
                }
            movementMethod = LinkMovementMethod.getInstance()
        }
        binding.reportRaidSubmit.setOnClickListener { submit() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /**
     * The chosen behaviours are never sent: the reference only reads them for analytics, and the
     * endpoint takes no body.
     */
    private fun submit() {
        val guild = StoreStream.getGuilds().getGuild(guildId)
        val alertsChannelId = guild?.safetyAlertsChannelId ?: guild?.publicUpdatesChannelId
        if (alertsChannelId == null) {
            close()
            return
        }
        RestAPI.api.reportRaid(guildId).restSubscribeOn().ui(this)
            .appSubscribe(WidgetGuildReportRaid::class.java) { close() }
    }

    private fun close() = requireActivity().onBackPressed()

    companion object {
        private const val ARG_GUILD_ID = "ARG_GUILD_ID"
        private const val LEARN_MORE_HOOK = "learnMoreHook"
        private const val SERVER_SAFETY_URL = "https://discord.com/community/securing-your-server"

        fun launch(context: Context, guildId: Long) {
            AppScreenRouter.launch(
                context,
                WidgetGuildReportRaid::class.java,
                Intent().putExtra(ARG_GUILD_ID, guildId),
            )
        }
    }
}
