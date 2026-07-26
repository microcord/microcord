package com.discord.widgets.hubs

import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.I18nSpans
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.GuildView
import com.google.android.material.button.MaterialButton

/**
 * "Your server has been added" — shown after a server is listed in a hub.
 *
 * Binds `R.layout.hub_add_server_confirmation_dialog`. Purely informational: the single button
 * dismisses it.
 */
class WidgetHubAddServerConfirmationDialog : AppDialog(R.layout.hub_add_server_confirmation_dialog) {

    private val args: AddServerConfirmationArgs by hubDialogArgs()

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val view = requireView()
        requireDialog().setCanceledOnTouchOutside(true)

        view.findViewById<MaterialButton>(R.id.add_server_confirmation_dialog_positive)
            .setOnClickListener { dismiss() }

        view.findViewById<TextView>(R.id.add_server_confirmation_dialog_description)
            .setTextAndVisibilityBy(
                I18nSpans.render(
                    requireContext().getString(R.string.hub_add_server_confirmation_subtitle),
                    "guildName" to args.hubName,
                ),
            )

        val guildView = view.findViewById<GuildView>(R.id.add_server_confirmation_dialog_image)
        guildView.useGuildIconCorners()
        StoreStream.getGuilds().getGuild(args.guildId)?.let { guild ->
            guildView.configure(IconUtils.getForGuild(guild.id, guild.icon), guild.shortName)
        }
    }

    companion object {
        fun show(
            fragmentManager: FragmentManager,
            args: AddServerConfirmationArgs,
        ): WidgetHubAddServerConfirmationDialog = WidgetHubAddServerConfirmationDialog().apply {
            arguments = args.toArgsBundle()
            show(fragmentManager, WidgetHubAddServerConfirmationDialog::class.java.name)
        }
    }
}
