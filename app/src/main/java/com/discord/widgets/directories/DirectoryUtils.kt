package com.discord.widgets.directories

import android.content.Context
import android.util.Base64
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import android.content.Intent
import com.discord.R
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.app.AppFragment
import com.discord.dialogs.SimpleConfirmationDialog
import com.discord.dialogs.SimpleConfirmationDialogArgs
import com.discord.i18n.i18nFormat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/** Analytics source recorded on a join started from a directory row. */
const val DIRECTORY_JOIN_GUILD_SOURCE: String = "Directory Channel Entry"

/** How many "recently added" servers the directory home page shows. */
private const val MAX_RECOMMENDATION_COUNT = 5

/** A server below this many members is never featured in the "Newest" section. */
private const val MINIMUM_MEMBER_COUNT = 5

/**
 * Screens this package hands off to that do not exist yet.
 *
 * Each destination lives in another package and another wave (`widgets/hubs`,
 * `widgets/guilds/invite`, `widgets/mobile_reports`). Rather than open a dead screen or silently
 * swallow the tap, the corresponding affordance is **hidden** while its hook is `null`, so the user
 * is never offered an action that goes nowhere. The owning wave assigns the hook and the affordance
 * appears; nothing in this package changes.
 *
 * Same pattern as `ChatListRowNavigation` in the chat-row wave.
 */
object DirectoryNavigation {

    /** `widgets/hubs/WidgetHubAddServer` — the add-a-server flow, started for a result. */
    @JvmField
    var addServerToHub: ((Context, ActivityResultLauncher<Intent>) -> Unit)? = null

    /** `widgets/guilds/invite/WidgetGuildInviteShare` — invite people to the hub itself. */
    @JvmField
    var inviteToHub: ((AppFragment, guildId: Long) -> Unit)? = null

    /** `widgets/hubs/WidgetHubDescription` — edit a listing this user owns. */
    @JvmField
    var editHubEntry: ((Context, DirectoryEntryGuild, hubName: String) -> Unit)? = null

    /** `widgets/mobile_reports/WidgetMobileReports.launchDirectoryServerReport`. */
    @JvmField
    var reportHubEntry: ((Context, guildId: Long, entityId: Long, channelId: Long) -> Unit)? = null

    /** `widgets/hubs/WidgetHubAddName` — the "pick a nickname" prompt on first hub entry. */
    @JvmField
    var promptForHubName: ((Context, guildId: Long) -> Unit)? = null
}

/**
 * The long-press menu on a directory row.
 *
 * The reference shows Discord's shared "simple actions" bottom sheet; that view does not exist here,
 * so this uses the same `MaterialAlertDialogBuilder` chooser the bot select-menu fallback uses. The
 * option set, the ordering, and the destructive confirmation are the reference's.
 */
object DirectoryServerOptions {

    private enum class Option(@StringRes val titleRes: Int) {
        Edit(R.string.hub_entry_update),
        Remove(R.string.hub_entry_remove),
        Report(R.string.report),
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

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.server_settings)
            .setItems(options.map { context.getString(it.titleRes) }.toTypedArray()) { _, index ->
                when (options[index]) {
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
            .show()
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

/**
 * Builds an `X-Context-Properties` header value: base64 (no wrap) of a flat JSON object.
 *
 * Port of the reference's `RestAPI.jsonObjectOf`. It belongs on `utilities/rest/RestAPI`, which is
 * currently only a publication point for the Retrofit interface — see the handoff report.
 */
internal fun contextPropertiesOf(vararg properties: Pair<String, Any?>): String {
    val json = JSONObject()
    properties.forEach { (key, value) -> json.put(key, value) }
    return Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
