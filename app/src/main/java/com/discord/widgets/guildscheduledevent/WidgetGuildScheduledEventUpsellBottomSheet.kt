package com.discord.widgets.guildscheduledevent

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.app.AppBottomSheet
import com.discord.stores.StoreStream
import com.discord.widgets.guilds.contextmenu.ContextMenuNavigation

class WidgetGuildScheduledEventUpsellBottomSheet : AppBottomSheet() {
    private val guildId: Long get() = requireArguments().getLong(EXTRA_GUILD_ID)

    override fun getContentViewResId(): Int = R.layout.widget_guild_scheduled_event_upsell_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.confirm_button).setOnClickListener {
            val guild = StoreStream.getGuilds().getGuild(guildId)
            if (guild?.hasFeature(GuildFeature.COMMUNITY) == true) {
                WidgetGuildScheduledEventListBottomSheet.show(parentFragmentManager, guildId)
            } else {
                ContextMenuNavigation.showGuildProfile(parentFragmentManager, guildId, true)
            }
            dismiss()
        }
        view.findViewById<View>(R.id.cancel_button).setOnClickListener { dismiss() }
    }

    companion object {
        const val GUILD_EVENT_UPSELL_CACHE_KEY = "GUILD_EVENT_UPSELL_CACHE_KEY"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"

        fun launch(fragmentManager: FragmentManager, guildId: Long) {
            WidgetGuildScheduledEventUpsellBottomSheet().apply {
                arguments = Bundle().apply { putLong(EXTRA_GUILD_ID, guildId) }
            }.show(fragmentManager, WidgetGuildScheduledEventUpsellBottomSheet::class.java.simpleName)
        }
    }
}
