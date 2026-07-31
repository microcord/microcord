package com.discord.utilities.auditlogs

import android.content.Context
import android.text.format.DateUtils
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.discord.R
import com.discord.app.AppLog
import com.discord.models.domain.ModelAuditLogEntry
import com.discord.models.domain.ModelAuditLogEntry.TargetType
import com.discord.stores.StoreStream
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.time.ClockFactory
import com.discord.widgets.settings.SettingsStrings
import java.util.concurrent.TimeUnit

/**
 * Turns one [ModelAuditLogEntry] into the sentence, icons and timestamp its row shows.
 *
 * The audit log is a single flat list of ~48 action types, and the reference chooses the sentence
 * purely from [ModelAuditLogEntry.actionTypeId] — the target's *kind* ([TargetType]) only picks the
 * left-hand icon. Most actions read `"{user} <verb> {target}"` and go through the shared tail of
 * [getHeaderString]; the handful that need a count, a channel or a resolved sticker name are the
 * explicit branches above it.
 *
 * Every string here carries Discord `{name}` placeholders and `$[…](hook)` emphasis runs, so they
 * are rendered through [SettingsStrings] rather than `Context.getString(id, args)`.
 */
object AuditLogUtils {

    /**
     * Every action type the client knows how to render, in the reference's declaration order.
     *
     * The action-filter list depends on both the order and exact membership. Future server actions
     * are intentionally absent from the filter but remain visible in the log as "Unknown action".
     *
     * Recovered from `AuditLogUtils$ALL_ACTION_TYPES$2` at bytecode level (48 entries).
     */
    val ALL_ACTION_TYPES: List<Int> = listOf(
        ACTION_ALL,
        1, // guild update
        10, 11, 12, // channel create/update/delete
        13, 14, 15, // channel overwrite create/update/delete
        20, 21, 22, 23, 24, 25, 26, 27, 28, // member kick…bot add
        110, 111, 112, // thread create/update/delete
        30, 31, 32, // role create/update/delete
        40, 41, 42, // invite create/update/delete
        50, 51, 52, // webhook create/update/delete
        60, 61, 62, // emoji create/update/delete
        72, 73, 74, 75, // message delete/bulk delete/pin/unpin
        80, 81, 82, // integration create/update/delete
        83, 84, 85, // stage instance create/update/delete
        90, 91, 92, // sticker create/update/delete
        100, 101, 102, // guild scheduled event create/update/delete
    )

    /**
     * The sentence template for [auditLogEntry]'s action.
     *
     * Channel and thread creation are the only actions whose template depends on a *change* rather
     * than on the action id: the wire carries one `channel_create` action for text, voice, category
     * and stage channels, and the concrete kind is the `type` change's new value.
     */
    @StringRes
    internal fun getEntryTitle(auditLogEntry: ModelAuditLogEntry): Int =
        when (val actionTypeId = auditLogEntry.actionTypeId) {
            1 -> R.string.guild_settings_audit_log_guild_update

            10 -> when (auditLogEntry.newValueOf("type") as? Long) {
                CHANNEL_TYPE_GUILD_VOICE -> R.string.guild_settings_audit_log_channel_voice_create
                CHANNEL_TYPE_GUILD_CATEGORY -> R.string.guild_settings_audit_log_channel_category_create
                CHANNEL_TYPE_GUILD_STAGE_VOICE -> R.string.guild_settings_audit_log_channel_stage_create
                else -> R.string.guild_settings_audit_log_channel_text_create
            }

            11 -> R.string.guild_settings_audit_log_channel_update
            12 -> R.string.guild_settings_audit_log_channel_delete
            13 -> R.string.guild_settings_audit_log_channel_overwrite_create
            14 -> R.string.guild_settings_audit_log_channel_overwrite_update
            15 -> R.string.guild_settings_audit_log_channel_overwrite_delete

            20 -> R.string.guild_settings_audit_log_member_kick
            21 -> R.string.guild_settings_audit_log_member_prune
            22 -> R.string.guild_settings_audit_log_member_ban_add
            23 -> R.string.guild_settings_audit_log_member_ban_remove
            24 -> R.string.guild_settings_audit_log_member_update
            25 -> R.string.guild_settings_audit_log_member_role_update
            26 -> R.string.guild_settings_audit_log_member_move
            27 -> R.string.guild_settings_audit_log_member_disconnect
            28 -> R.string.guild_settings_audit_log_bot_add

            30 -> R.string.guild_settings_audit_log_role_create
            31 -> R.string.guild_settings_audit_log_role_update
            32 -> R.string.guild_settings_audit_log_role_delete

            40 -> R.string.guild_settings_audit_log_invite_create
            41 -> R.string.guild_settings_audit_log_invite_update
            42 -> R.string.guild_settings_audit_log_invite_delete

            50 -> R.string.guild_settings_audit_log_webhook_create
            51 -> R.string.guild_settings_audit_log_webhook_update
            52 -> R.string.guild_settings_audit_log_webhook_delete

            60 -> R.string.guild_settings_audit_log_emoji_create
            61 -> R.string.guild_settings_audit_log_emoji_update
            62 -> R.string.guild_settings_audit_log_emoji_delete

            72 -> R.string.guild_settings_audit_log_message_delete
            73 -> R.string.guild_settings_audit_log_message_bulk_delete
            74 -> R.string.guild_settings_audit_log_message_pin
            75 -> R.string.guild_settings_audit_log_message_unpin

            80 -> R.string.guild_settings_audit_log_integration_create
            81 -> R.string.guild_settings_audit_log_integration_update
            82 -> R.string.guild_settings_audit_log_integration_delete

            83 -> R.string.guild_settings_audit_log_stage_instance_create
            84 -> R.string.guild_settings_audit_log_stage_instance_update
            // Discord itself ends an idle stage; that entry arrives with no acting user.
            85 -> if (auditLogEntry.userId == NO_USER) {
                R.string.guild_settings_audit_log_stage_instance_delete_no_user
            } else {
                R.string.guild_settings_audit_log_stage_instance_delete
            }

            90 -> R.string.guild_settings_audit_log_sticker_create
            91 -> R.string.guild_settings_audit_log_sticker_update
            92 -> R.string.guild_settings_audit_log_sticker_delete

            100 -> R.string.guild_settings_audit_log_scheduled_event_create
            101 -> R.string.guild_settings_audit_log_scheduled_event_update
            102 -> R.string.guild_settings_audit_log_scheduled_event_delete

            110 -> when (auditLogEntry.newValueOf("type") as? Long) {
                CHANNEL_TYPE_ANNOUNCEMENT_THREAD -> R.string.guild_settings_audit_log_announcement_thread_create
                CHANNEL_TYPE_PRIVATE_THREAD -> R.string.guild_settings_audit_log_private_thread_create
                else -> R.string.guild_settings_audit_log_thread_create
            }

            111 -> R.string.guild_settings_audit_log_thread_update
            112 -> R.string.guild_settings_audit_log_thread_delete

            else -> {
                AppLog.e("Unknown audit log action type: $actionTypeId")
                R.string.guild_settings_audit_log_unknown_action
            }
        }

    /** The name of the human-readable action, for the action-filter list. */
    @StringRes
    fun getActionName(actionTypeId: Int): Int = when (actionTypeId) {
        ACTION_ALL -> R.string.guild_settings_filter_all_actions
        1 -> R.string.guild_settings_action_filter_guild_update

        10 -> R.string.guild_settings_action_filter_channel_create
        11 -> R.string.guild_settings_action_filter_channel_update
        12 -> R.string.guild_settings_action_filter_channel_delete
        13 -> R.string.guild_settings_action_filter_channel_overwrite_create
        14 -> R.string.guild_settings_action_filter_channel_overwrite_update
        15 -> R.string.guild_settings_action_filter_channel_overwrite_delete

        20 -> R.string.guild_settings_action_filter_member_kick
        21 -> R.string.guild_settings_action_filter_member_prune
        22 -> R.string.guild_settings_action_filter_member_ban_add
        23 -> R.string.guild_settings_action_filter_member_ban_remove
        24 -> R.string.guild_settings_action_filter_member_update
        25 -> R.string.guild_settings_action_filter_member_role_update
        26 -> R.string.guild_settings_action_filter_member_move
        27 -> R.string.guild_settings_action_filter_member_disconnect
        28 -> R.string.guild_settings_action_filter_bot_add

        30 -> R.string.guild_settings_action_filter_role_create
        31 -> R.string.guild_settings_action_filter_role_update
        32 -> R.string.guild_settings_action_filter_role_delete

        40 -> R.string.guild_settings_action_filter_invite_create
        41 -> R.string.guild_settings_action_filter_invite_update
        42 -> R.string.guild_settings_action_filter_invite_delete

        50 -> R.string.guild_settings_action_filter_webhook_create
        51 -> R.string.guild_settings_action_filter_webhook_update
        52 -> R.string.guild_settings_action_filter_webhook_delete

        60 -> R.string.guild_settings_action_filter_emoji_create
        61 -> R.string.guild_settings_action_filter_emoji_update
        62 -> R.string.guild_settings_action_filter_emoji_delete

        72 -> R.string.guild_settings_action_filter_message_delete
        73 -> R.string.guild_settings_action_filter_message_bulk_delete
        74 -> R.string.guild_settings_action_filter_message_pin
        75 -> R.string.guild_settings_action_filter_message_unpin

        80 -> R.string.guild_settings_action_filter_integration_create
        81 -> R.string.guild_settings_action_filter_integration_update
        82 -> R.string.guild_settings_action_filter_integration_delete
        83 -> R.string.guild_settings_action_filter_stage_instance_create
        84 -> R.string.guild_settings_action_filter_stage_instance_update
        85 -> R.string.guild_settings_action_filter_stage_instance_delete

        90 -> R.string.guild_settings_action_filter_sticker_create
        91 -> R.string.guild_settings_action_filter_sticker_update
        92 -> R.string.guild_settings_action_filter_sticker_delete

        100 -> R.string.guild_settings_action_filter_guild_scheduled_event_create
        101 -> R.string.guild_settings_action_filter_guild_scheduled_event_update
        102 -> R.string.guild_settings_action_filter_guild_scheduled_event_delete

        110 -> R.string.guild_settings_action_filter_thread_create
        111 -> R.string.guild_settings_action_filter_thread_update
        112 -> R.string.guild_settings_action_filter_thread_delete

        else -> 0
    }

    /** The small create/update/delete badge overlaid on the target icon. */
    @DrawableRes
    fun getActionTypeImage(actionTypeId: Int): Int =
        when (ModelAuditLogEntry.getActionType(actionTypeId)) {
            ModelAuditLogEntry.ActionType.CREATE -> R.drawable.ic_audit_audit_create_24dp
            ModelAuditLogEntry.ActionType.UPDATE -> R.drawable.ic_audit_audit_update_24dp
            ModelAuditLogEntry.ActionType.DELETE -> R.drawable.ic_audit_audit_delete_24dp
            else -> 0
        }

    /**
     * The themed icon for what the action was performed *on*.
     *
     * Message deletion is special-cased ahead of the target type because its target is the *author*
     * — a user — yet the row should read as a message action.
     */
    @DrawableRes
    fun getTargetTypeImage(view: View, actionTypeId: Int): Int {
        if (actionTypeId == ACTION_MESSAGE_DELETE) {
            return view.getThemedDrawableRes(R.attr.ic_audit_message)
        }
        val attr = when (ModelAuditLogEntry.getTargetType(actionTypeId)) {
            TargetType.ALL -> R.attr.ic_audit_all
            TargetType.GUILD -> R.attr.ic_audit_server
            TargetType.CHANNEL, TargetType.CHANNEL_OVERWRITE -> R.attr.ic_audit_channel
            TargetType.USER -> R.attr.ic_audit_member
            TargetType.ROLE -> R.attr.ic_audit_role
            TargetType.INVITE -> R.attr.ic_audit_invite
            TargetType.WEBHOOK -> R.attr.ic_audit_webhook
            TargetType.EMOJI -> R.attr.ic_audit_emoji
            TargetType.INTEGRATION -> R.attr.ic_audit_integration
            TargetType.STAGE_INSTANCE -> R.attr.ic_audit_stage_instance
            TargetType.GUILD_SCHEDULED_EVENT -> R.attr.ic_audit_calendar
            TargetType.STICKER -> R.attr.ic_audit_sticker
            TargetType.THREAD -> R.attr.ic_audit_thread
            // UNKNOWN and anything a newer server adds render without a target icon.
            else -> return 0
        }
        return view.getThemedDrawableRes(attr)
    }

    /**
     * The one-line sentence at the top of a row, for example
     * `"alice#0001 banned bob#0002"`.
     *
     * @param username the acting user, already rendered with its discriminator, or `""` when the
     *   entry's actor is not in the guild any more.
     * @param targets deleted-and-live target names by [TargetType] and id, assembled by the screen.
     * @param prefix `"#"` when the entry's target is a live text channel, `""` otherwise. See the
     *   note on [getTargetText] — this is where the reference's `#` prefix actually comes from for
     *   channels that still exist.
     */
    fun getHeaderString(
        auditLogEntry: ModelAuditLogEntry,
        username: CharSequence,
        context: Context,
        targets: Map<TargetType, out Map<Long, CharSequence?>>,
        prefix: String = "",
    ): CharSequence {
        val options = auditLogEntry.options
        val title = getEntryTitle(auditLogEntry)

        /** The channel an action happened *in*, as opposed to the one it was performed *on*. */
        fun optionChannelName(): CharSequence? {
            val channelId = options?.channelId ?: return null
            return targets[TargetType.CHANNEL]?.get(channelId) ?: channelId.toString()
        }

        return when (auditLogEntry.actionTypeId) {
            // Prune reports how many members went, not who.
            21 -> SettingsStrings.render(
                context,
                title,
                "user" to username,
                "count" to plural(
                    context,
                    R.plurals.guild_settings_audit_log_member_prune_count,
                    options?.membersRemoved ?: 0,
                ),
            )

            // A revoked invite is gone by the time the log is read, so its code comes from the
            // change's *old* value rather than from any live object.
            42 -> SettingsStrings.render(
                context,
                title,
                "user" to username,
                "target" to auditLogEntry.changes.orEmpty().first { it.key == "code" }.oldValue.toString(),
            )

            26 -> SettingsStrings.render(
                context,
                title,
                "user" to username,
                "count" to plural(context, R.plurals.guild_settings_audit_log_member_move_count, options?.count ?: 0),
                "channel" to optionChannelName(),
            )

            27 -> SettingsStrings.render(
                context,
                title,
                "user" to username,
                "count" to plural(
                    context,
                    R.plurals.guild_settings_audit_log_member_disconnect_count,
                    options?.count ?: 0,
                ),
            )

            // Emoji actions name the emoji from the change list; unlike other targets there is no
            // store to resolve a deleted one from, so `first` is safe only because the server always
            // sends a `name` change for these three actions — the reference relies on the same.
            60, 61, 62 -> SettingsStrings.render(
                context,
                title,
                "user" to username,
                "target" to auditLogEntry.changes.orEmpty().first { it.key == "name" }.value.toString(),
            )

            72 -> SettingsStrings.render(
                context,
                title,
                "user" to username,
                "count" to plural(
                    context,
                    R.plurals.guild_settings_audit_log_message_delete_count,
                    options?.count ?: 0,
                ),
                "target" to getTargetText(auditLogEntry, targets, prefix),
                "channel" to optionChannelName(),
            )

            73 -> SettingsStrings.render(
                context,
                title,
                "user" to username,
                "count" to plural(
                    context,
                    R.plurals.guild_settings_audit_log_message_bulk_delete_count,
                    options?.count ?: 0,
                ),
                "target" to getTargetText(auditLogEntry, targets, prefix),
            )

            74, 75 -> SettingsStrings.render(
                context,
                title,
                "user" to username,
                "target" to getTargetText(auditLogEntry, targets, prefix),
                "channel" to optionChannelName(),
            )

            // A stage entry names the stage channel, and the "Discord ended this" variant has no
            // acting user at all — passing a `{user}` it does not contain would be harmless, but
            // the reference distinguishes the two and so does this.
            83, 84, 85 -> if (auditLogEntry.userId == NO_USER) {
                SettingsStrings.render(context, title, "channel" to optionChannelName())
            } else {
                SettingsStrings.render(
                    context,
                    title,
                    "user" to username,
                    "channel" to optionChannelName(),
                )
            }

            // A sticker keeps its name in the store even after the entry ages out of the payload,
            // so prefer the live one and fall back to the change list.
            90, 91, 92 -> SettingsStrings.render(
                context,
                title,
                "user" to username,
                "target" to (
                    StoreStream.getGuildStickers().getGuildSticker(auditLogEntry.targetId)?.name
                        ?: auditLogEntry.changes.orEmpty()
                            .firstOrNull { it.key == "name" }?.value.toString()
                    ),
            )

            else -> SettingsStrings.render(
                context,
                title,
                "user" to username,
                "target" to getTargetText(auditLogEntry, targets, prefix),
            )
        }
    }

    /**
     * The name of the thing the action was performed on.
     *
     * Resolution order is: the name the screen already resolved (live object or scraped deleted
     * name), then a name recoverable from the entry's own changes, and finally the raw id — the
     * reference never renders an empty target.
     *
     * A channel-overwrite entry targets the *channel* it lives on, so its lookup is redirected.
     */
    private fun getTargetText(
        auditLogEntry: ModelAuditLogEntry,
        targets: Map<TargetType, out Map<Long, CharSequence?>>,
        channelPrefix: String = "",
    ): String {
        val targetType = auditLogEntry.targetType.let {
            if (it == TargetType.CHANNEL_OVERWRITE) TargetType.CHANNEL else it
        }
        val resolved = targets[targetType]?.get(auditLogEntry.targetId)
            ?: when (targetType) {
                TargetType.CHANNEL -> channelPrefix + auditLogEntry.valueOf("name")
                TargetType.ROLE -> auditLogEntry.options?.roleName ?: auditLogEntry.valueOf("name")
                TargetType.INVITE -> auditLogEntry.valueOf("code")
                TargetType.WEBHOOK -> auditLogEntry.valueOf("name")
                TargetType.INTEGRATION -> auditLogEntry.valueOf("type")
                else -> null
            }
        return (resolved ?: auditLogEntry.targetId).toString()
    }

    /** The `new_value ?: old_value` of the change named [key], if the entry carries one. */
    private fun ModelAuditLogEntry.valueOf(key: String): Any? =
        changes?.firstOrNull { it.key == key }?.value

    /** The `new_value` of the change named [key]. Distinct from [valueOf]: never falls back. */
    private fun ModelAuditLogEntry.newValueOf(key: String): Any? =
        changes?.firstOrNull { it.key == key }?.newValue

    /**
     * The instant an entry was created, decoded from its snowflake id.
     *
     * Audit log entries carry no explicit timestamp; the id is the only source.
     */
    val ModelAuditLogEntry.timestampStart: Long
        get() = SnowflakeUtils.toTimestamp(id)

    /**
     * The row's timestamp.
     *
     * The store merges a run of same-actor, same-action entries into one row and records when the
     * run ended; such a row shows a range rather than an instant.
     */
    fun getTimestampString(auditLogEntry: ModelAuditLogEntry, context: Context): CharSequence {
        val start = getTimestampText(auditLogEntry.timestampStart, context)
        val end = auditLogEntry.timestampEnd ?: return start
        return "$start - ${getTimestampText(end, context)}"
    }

    /**
     * "3 hours ago at 4:05 PM" for the last week, and a plain absolute date before that — a
     * relative span alone stops being useful once it reads "5 weeks ago".
     */
    private fun getTimestampText(timestamp: Long, context: Context): CharSequence {
        val now = ClockFactory.get().currentTimeMillis()
        val relative = DateUtils.getRelativeTimeSpanString(
            timestamp,
            now,
            DateUtils.DAY_IN_MILLIS,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_ALL,
        )
        if (TimeUnit.MILLISECONDS.toDays(now - timestamp) >= RELATIVE_TIME_CUTOFF_DAYS) return relative
        return SettingsStrings.render(
            context,
            R.string.guild_settings_audit_log_time_at_android,
            "date" to relative,
            "time" to DateUtils.formatDateTime(context, timestamp, DateUtils.FORMAT_SHOW_TIME),
        )
    }

    /**
     * A count phrase such as `"3 messages"`.
     *
     * The plural resource itself is a `{count}` template, so it needs the quantity twice: once to
     * select the `one`/`other` form and once to substitute into it.
     */
    private fun plural(context: Context, pluralRes: Int, quantity: Int): CharSequence =
        com.discord.i18n.FormatUtils.format(
            context.resources.getQuantityString(pluralRes, quantity),
            "count" to quantity,
        )

    private const val ACTION_ALL = 0
    private const val ACTION_MESSAGE_DELETE = 72

    /** An entry the server attributes to itself rather than to a member. */
    private const val NO_USER = 0L

    private const val RELATIVE_TIME_CUTOFF_DAYS = 7L

    // `channel_create` and `thread_create` both report the concrete kind as a `type` change.
    private const val CHANNEL_TYPE_GUILD_VOICE = 2L
    private const val CHANNEL_TYPE_GUILD_CATEGORY = 4L
    private const val CHANNEL_TYPE_GUILD_STAGE_VOICE = 13L
    private const val CHANNEL_TYPE_ANNOUNCEMENT_THREAD = 10L
    private const val CHANNEL_TYPE_PRIVATE_THREAD = 12L
}
