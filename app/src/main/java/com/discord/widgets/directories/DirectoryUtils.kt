package com.discord.widgets.directories

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import android.content.Intent
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.dialogs.SimpleConfirmationDialog
import com.discord.dialogs.SimpleConfirmationDialogArgs
import com.discord.i18n.i18nFormat
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.discord.widgets.hubs.HubAddNameArgs
import com.discord.widgets.hubs.HubDescriptionArgs
import com.discord.widgets.hubs.WidgetHubAddName
import com.discord.widgets.hubs.WidgetHubAddServer
import com.discord.widgets.hubs.WidgetHubDescription
import com.discord.widgets.hubs.toArgsIntent
import com.discord.widgets.mobile_reports.WidgetMobileReports
import com.discord.utilities.view.recycler.MaxHeightRecyclerView
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.facebook.drawee.view.SimpleDraweeView

/** Analytics source recorded on a join started from a directory row. */
const val DIRECTORY_JOIN_GUILD_SOURCE: String = "Directory Channel Entry"

/** How many "recently added" servers the directory home page shows. */
private const val MAX_RECOMMENDATION_COUNT = 5

/** A server below this many members is never featured in the "Newest" section. */
private const val MINIMUM_MEMBER_COUNT = 5

/** Directory destinations, kept overridable for focused widget tests. */
object DirectoryNavigation {

    /** `widgets/hubs/WidgetHubAddServer` — the add-a-server flow, started for a result. */
    @JvmField
    var addServerToHub: ((Context, ActivityResultLauncher<Intent>) -> Unit)? = { context, launcher ->
        AppScreenRouter.launchForResult(context, launcher, WidgetHubAddServer::class.java)
    }

    /** `widgets/guilds/invite/WidgetGuildInviteShare` — invite people to the hub itself. */
    @JvmField
    var inviteToHub: ((AppFragment, guildId: Long) -> Unit)? = { fragment, guildId ->
        WidgetGuildInviteShare.launch(
            fragment.requireContext(),
            fragment.childFragmentManager,
            guildId,
            source = "Guild Header",
        )
    }

    /** `widgets/hubs/WidgetHubDescription` — edit a listing this user owns. */
    @JvmField
    var editHubEntry: ((Context, DirectoryEntryGuild, hubName: String) -> Unit)? = { context, entry, hubName ->
        AppScreenRouter.launch(
            context,
            WidgetHubDescription::class.java,
            HubDescriptionArgs(
                guildId = entry.guild.id,
                channelId = entry.directoryChannelId,
                isEditing = true,
                hubName = hubName,
                description = entry.description,
                primaryCategoryId = entry.primaryCategoryId,
            ).toArgsIntent(),
        )
    }

    /** `widgets/mobile_reports/WidgetMobileReports.launchDirectoryServerReport`. */
    @JvmField
    var reportHubEntry: ((Context, guildId: Long, entityId: Long, channelId: Long) -> Unit)? =
        WidgetMobileReports::launchDirectoryServerReport

    /** `widgets/hubs/WidgetHubAddName` — the "pick a nickname" prompt on first hub entry. */
    @JvmField
    var promptForHubName: ((Context, guildId: Long) -> Unit)? = { context, guildId ->
        AppScreenRouter.launch(context, WidgetHubAddName::class.java, HubAddNameArgs(guildId).toArgsIntent())
    }
}

/**
 * The long-press menu on a directory row.
 *
 * The option set, ordering, icons, and destructive confirmation match the reference action sheet.
 */
object DirectoryServerOptions {

    internal enum class Option(@StringRes val titleRes: Int, @DrawableRes val iconRes: Int) {
        Edit(R.string.hub_entry_update, R.drawable.ic_edit_24dp),
        Remove(R.string.hub_entry_remove, R.drawable.ic_delete_24dp),
        Report(R.string.report, R.drawable.ic_flag_24dp),
    }

    private val SERVER_OWNER_OPTIONS = listOf(Option.Edit, Option.Remove)
    private val NON_SERVER_OWNER_OPTIONS = listOf(Option.Report)

    fun show(
        fragment: AppFragment,
        directoryEntry: DirectoryEntryGuild,
        hubName: String,
        isServerOwner: Boolean,
        removeGuildListener: () -> Unit,
    ) {
        val context = fragment.context ?: return
        val options = (if (isServerOwner) SERVER_OWNER_OPTIONS else NON_SERVER_OWNER_OPTIONS)
            .filter { it.isAvailable() }
        if (options.isEmpty()) return

        DirectoryServerOptionsSheet.show(fragment, options) { option ->
                when (option) {
                    Option.Edit -> DirectoryNavigation.editHubEntry?.invoke(context, directoryEntry, hubName)
                    Option.Remove -> confirmRemove(fragment, directoryEntry, removeGuildListener)
                    Option.Report -> DirectoryNavigation.reportHubEntry?.invoke(
                        context,
                        directoryEntry.guild.id,
                        directoryEntry.entityId,
                        directoryEntry.directoryChannelId,
                    )
                }
        }
    }

    private fun Option.isAvailable(): Boolean = when (this) {
        Option.Remove -> true
        Option.Edit -> DirectoryNavigation.editHubEntry != null
        Option.Report -> DirectoryNavigation.reportHubEntry != null
    }

    private fun confirmRemove(
        fragment: AppFragment,
        directoryEntry: DirectoryEntryGuild,
        removeGuildListener: () -> Unit,
    ) {
        val context = fragment.context ?: return
        SimpleConfirmationDialog.show(
            fragment.childFragmentManager,
            SimpleConfirmationDialogArgs(
                title = context.getString(R.string.hub_entry_remove),
                description = context.i18nFormat(
                    R.string.hub_entry_remove_body,
                    "guildName" to directoryEntry.guild.name,
                ),
                positiveButtonText = context.getString(R.string.remove),
                negativeButtonText = context.getString(R.string.cancel),
            ),
        ) { removeGuildListener() }
    }
}

internal class DirectoryServerOptionsSheet : AppBottomSheet() {
    var options: List<DirectoryServerOptions.Option> = emptyList()
    var onSelected: ((DirectoryServerOptions.Option) -> Unit)? = null

    override fun getContentViewResId() = R.layout.bottom_sheet_simple_selector

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setBottomSheetCollapsedStateDisabled()
        view.findViewById<TextView>(R.id.widget_simple_bottom_sheet_selector_placeholder)
            .setText(R.string.server_settings)
        view.findViewById<MaxHeightRecyclerView>(R.id.widget_simple_bottom_sheet_selector_recycler).apply {
            adapter = SimpleRecyclerAdapter(options) { inflater, parent ->
                val row = inflater.inflate(R.layout.bottom_sheet_simple_selector_item, parent, false)
                OptionHolder(row)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        dismiss()
    }

    private inner class OptionHolder(view: View) : SimpleRecyclerAdapter.ViewHolder<DirectoryServerOptions.Option>(view) {
        override fun bind(data: DirectoryServerOptions.Option) {
            itemView.findViewById<TextView>(R.id.select_component_sheet_item_title).setText(data.titleRes)
            itemView.findViewById<TextView>(R.id.select_component_sheet_item_description).visibility = View.GONE
            itemView.findViewById<SimpleDraweeView>(R.id.select_component_sheet_item_icon)
                .setActualImageResource(data.iconRes)
            itemView.setOnClickListener {
                onSelected?.invoke(data)
                dismiss()
            }
        }
    }

    companion object {
        fun show(
            fragment: AppFragment,
            options: List<DirectoryServerOptions.Option>,
            onSelected: (DirectoryServerOptions.Option) -> Unit,
        ) = DirectoryServerOptionsSheet().apply {
            this.options = options
            this.onSelected = onSelected
        }.show(fragment.childFragmentManager, DirectoryServerOptionsSheet::class.java.name)
    }
}

/**
 * The "Newest" section of the directory home page: featurable servers that clear the member
 * threshold, most recently listed first, capped at [MAX_RECOMMENDATION_COUNT].
 *
 * Port of `com.discord.utilities.directories.DirectoryUtilsKt.sortByAdded`. It belongs in
 * `utilities/directories/`, which does not exist yet — see the handoff report.
 */
internal fun List<DirectoryEntryData>.sortByAdded(): List<DirectoryEntryData> =
    filter { it.entry.guild.featurableInDirectory == true && it.entry.hasMinimumMemberCount() }
        .sortedByDescending { it.entry.createdAt }
        .take(MAX_RECOMMENDATION_COUNT)

/** Port of `DirectoryUtilsKt.hasMinimumMemberCount`. */
internal fun DirectoryEntryGuild.hasMinimumMemberCount(): Boolean =
    (guild.approximateMemberCount ?: 0) >= MINIMUM_MEMBER_COUNT

/**
 * Initials of a guild name, used as the avatar fallback when a listed server has no icon.
 *
 * Port of `com.discord.utilities.guilds.GuildUtilsKt.computeShortName`. `models/guild/Guild.shortName`
 * in this project only performs the first of the three steps, so it cannot be reused — see the
 * handoff report.
 */
internal fun computeShortName(name: String): String {
    val withoutPossessive = name.replace(POSSESSIVE, " ")
    val initials = WORD.replace(withoutPossessive) { it.groupValues[1] }
    return initials.replace(WHITESPACE, "")
}

private val POSSESSIVE = Regex("'s ")
private val WORD = Regex("(\\w)(\\w*)")
private val WHITESPACE = Regex("\\s")
