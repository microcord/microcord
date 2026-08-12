package com.discord.utilities.auditlogs

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionBit
import com.discord.app.AppLog
import com.discord.models.domain.ModelAuditLogEntry
import com.discord.models.domain.ModelAuditLogEntry.Change
import com.discord.models.domain.ModelAuditLogEntry.TargetType
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.time.TimeUtils
import com.discord.widgets.settings.SettingsStrings

/**
 * Renders the expandable "what actually changed" block under an audit-log row.
 *
 * An entry carries a list of raw `{key, old_value, new_value}` triples straight off the wire.
 * Turning one into a sentence takes two steps, which is why this file is two parallel tables:
 *
 * 1. **which sentence** — [getChangeSummary] dispatches on the entry's [TargetType] to one of the
 *    `get*ChangeString` functions, which map the change *key* (plus, often, whether the old or new
 *    value is null) to a string resource.
 * 2. **what to put in it** — [getChangeTextWithParams] substitutes `{oldValue}` / `{newValue}` /
 *    `{count}` / `{subtarget}`, converting the raw value first where the wire unit differs from the
 *    displayed one (bitrate is bits/s but shown in kbps, AFK timeout is seconds but shown in
 *    minutes, a permission bitfield is shown as a list of permission names, and so on).
 *
 * A change whose key this build does not know maps to resource `0` and is skipped with a warning
 * rather than rendered blank — the same fail-soft the reference has, and the reason a newer
 * server-side field degrades to "one line missing" instead of an exception.
 */
object AuditLogChangeUtils {

    /** Guild-level keys whose value is a channel id and should render as that channel's name. */
    private val CHANGE_KEYS_REFERENCING_CHANNEL = listOf(
        "channel_id",
        "afk_channel_id",
        "system_channel_id",
        "rules_channel_id",
        "public_updates_channel_id",
    )

    /**
     * Delete actions whose changes are still worth showing.
     *
     * Most deletions list every field of the vanished object, which is noise; a ban, kick or prune
     * carries the moderator's reason, which is the single most important thing on the row.
     */
    private val RENDERABLE_DELETE_ACTION_TYPES = listOf(
        ACTION_MEMBER_BAN_ADD,
        ACTION_MEMBER_KICK,
        ACTION_MEMBER_PRUNE,
        // Not a deletion of anything the row lists: its one change names the rule that fired.
        ACTION_AUTO_MODERATION_BLOCK_MESSAGE,
    )

    /** `system_channel_flags` bit to the change key [transformSystemChannelFlags] emits for it. */
    private val SYSTEM_CHANNEL_FLAG_KEYS = listOf(
        1L to KEY_SYSTEM_JOIN_NOTIFICATIONS,
        2L to KEY_SYSTEM_PREMIUM_SUBSCRIPTIONS,
        4L to KEY_SYSTEM_REMINDER_NOTIFICATIONS,
        8L to KEY_SYSTEM_JOIN_NOTIFICATION_REPLIES,
    )

    // region public API

    /**
     * Splits a permission *bitfield* change into one "granted" and one "denied" pseudo-change.
     *
     * `StoreAuditLog` calls this while ingesting a page, so the rest of the pipeline only ever sees
     * set-valued changes. A bit that is set in `new` and clear in `old` was granted; the reverse
     * was denied; a bit that did not move produces nothing.
     *
     * Values arrive as decimal *strings*, not numbers — permission bitfields exceed 2^53 and would
     * lose precision as JSON numbers.
     */
    fun transformPermissionChange(change: Change): List<Change> {
        val (granted, denied) = calculatePermissionChange(change)
        return buildList {
            if (granted.isNotEmpty()) add(Change(KEY_ALLOW, null, granted))
            if (denied.isNotEmpty()) add(Change(KEY_DENY, null, denied))
        }
    }

    /**
     * The channel-overwrite counterpart of [transformPermissionChange].
     *
     * An overwrite has three states per permission, not two, so the *removed* bits mean "reset to
     * inherit" rather than "explicitly denied", and the retained bits keep the original key
     * (`allow` or `deny`) to say which side of the overwrite they were on.
     */
    fun transformPermissionOverride(change: Change): List<Change> {
        val (granted, reset) = calculatePermissionChange(change)
        return buildList {
            if (granted.isNotEmpty()) add(Change(change.key, null, granted))
            if (reset.isNotEmpty()) add(Change(KEY_RESET, null, reset))
        }
    }

    /**
     * Splits a `system_channel_flags` bitfield change into one change per notification kind.
     *
     * Only the bits that actually moved produce a line, and each value is inverted on the way out:
     * a set flag *suppresses* the notification, so "flag added" is what a moderator reads as
     * "turned off".
     */
    fun transformSystemChannelFlags(change: Change): List<Change> {
        val old = asLong(change.oldValue) ?: 0L
        val new = asLong(change.newValue) ?: 0L
        return SYSTEM_CHANNEL_FLAG_KEYS.mapNotNull { (flag, key) ->
            val had = old and flag == flag
            val has = new and flag == flag
            if (had == has) null else Change(key, !had, !has)
        }
    }

    /**
     * Whether the row's disclosure arrow should be shown at all.
     *
     * An entry all of whose changes are suppressed by [shouldNotRenderChange] would expand to
     * nothing, so its arrow is hidden and tapping it does not toggle.
     */
    fun hasChangesToRender(log: ModelAuditLogEntry): Boolean =
        log.changes?.any { !shouldNotRenderChange(log, it) } == true

    /**
     * The expanded block: one numbered line per renderable change.
     *
     * Lines are numbered `01 — `, `02 — ` … in the action's colour and in the theme's monospace
     * face, so the numbers stay aligned regardless of the sentence beside them. A `color` change
     * additionally appends a `●` tinted with the colour that was set, which is the only way to see
     * a role colour in a log.
     */
    fun getChangeSummary(
        context: Context,
        auditLogEntry: ModelAuditLogEntry,
        targets: Map<TargetType, out Map<Long, CharSequence?>>,
    ): CharSequence {
        val builder = SpannableStringBuilder()
        var renderedCount = 0

        for (change in auditLogEntry.changes.orEmpty()) {
            if (shouldNotRenderChange(auditLogEntry, change)) continue

            val textId = when (auditLogEntry.targetType) {
                TargetType.GUILD -> getGuildChangeString(change)
                TargetType.CHANNEL, TargetType.CHANNEL_OVERWRITE -> getChannelChangeString(change)
                TargetType.USER -> getUserChangeString(change)
                TargetType.ROLE -> getRoleChangeString(change)
                TargetType.INVITE -> getInviteChangeString(change)
                TargetType.WEBHOOK -> getWebhookChangeString(change)
                TargetType.EMOJI -> getEmojiChangeString(change)
                TargetType.INTEGRATION -> getIntegrationChangeString(change)
                TargetType.STAGE_INSTANCE -> getStageInstanceChangeString(change)
                TargetType.GUILD_SCHEDULED_EVENT -> getGuildScheduledEventChangeString(change)
                TargetType.STICKER -> getStickerChangeString(change)
                TargetType.THREAD -> getThreadChangeString(change)
                TargetType.APPLICATION_COMMAND -> getCommandPermissionChangeString(change)
                TargetType.AUTO_MODERATION_RULE -> getAutoModerationRuleChangeString(change)
                TargetType.GUILD_SOUNDBOARD -> getSoundboardChangeString(change)
                TargetType.ONBOARDING_PROMPT -> getOnboardingPromptChangeString(change)
                TargetType.GUILD_ONBOARDING -> getOnboardingChangeString(change)
                TargetType.HOME_SETTINGS -> getHomeSettingsChangeString(change)
                TargetType.VOICE_CHANNEL_STATUS -> getVoiceChannelStatusChangeString(change)
                TargetType.GUILD_MEMBER_VERIFICATION -> getMemberVerificationChangeString(change)
                TargetType.GUILD_PROFILE -> getGuildProfileChangeString(change)
                TargetType.GUILD_SCHEDULED_EVENT_EXCEPTION ->
                    getScheduledEventExceptionChangeString(change)
                // Every other target still shows a moderator's reason, which is the one change key
                // every action can carry.
                else -> if (change.key == "reason") R.string.guild_settings_audit_log_common_reason else 0
            }

            if (textId == 0) {
                AppLog.w(
                    LOG_TAG,
                    "No change text for actiontype: ${auditLogEntry.actionTypeId} key:${change.key}",
                )
                continue
            }

            renderedCount++
            if (renderedCount != 1) builder.append("\n")

            // The span starts one character before the number so the leading newline of every line
            // after the first is coloured too — matching the reference's `length - 1` origin.
            val numberStart = maxOf(builder.length - 1, 0)
            builder.append("${getChangeNumberString(renderedCount)} — ")
            val numberEnd = builder.length - 1
            builder.setSpan(
                ForegroundColorSpan(getTextColor(context, auditLogEntry)),
                numberStart,
                numberEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            // The reference wraps the number in a monospace TypefaceSpan resolved from the theme's
            // `font_monospace`. `FontUtils` has not been reconstructed, so the number renders in the
            // body face; alignment of multi-line blocks is the only thing lost.
            builder.append(getChangeTextWithParams(context, auditLogEntry, change, textId, targets))

            appendColorSwatch(builder, change)
        }

        return builder
    }

    // endregion

    /**
     * Which permission bits [change] granted and which it took away.
     *
     * @return `(granted, removed)`, each a set of single-bit masks.
     */
    private fun calculatePermissionChange(change: Change): Pair<Set<Long>, Set<Long>> {
        val old = (change.oldValue as? String)?.toLong() ?: 0L
        val new = (change.newValue as? String)?.toLong() ?: 0L
        val granted = LinkedHashSet<Long>()
        val removed = LinkedHashSet<Long>()
        for (bitIndex in 0..MAX_PERMISSION_BIT_INDEX) {
            val bit = 1L shl bitIndex
            if (old.inv() and new and bit == bit) granted.add(bit)
            if (old and new.inv() and bit == bit) removed.add(bit)
        }
        return granted to removed
    }

    /**
     * Whether a change should be hidden rather than rendered.
     *
     * Two independent reasons: the whole entry is a deletion whose field dump is noise (see
     * [RENDERABLE_DELETE_ACTION_TYPES]), or the individual key is an internal identifier that means
     * nothing to a moderator.
     */
    private fun shouldNotRenderChange(auditLogEntry: ModelAuditLogEntry, change: Change): Boolean {
        if (auditLogEntry.actionType == ModelAuditLogEntry.ActionType.DELETE &&
            auditLogEntry.actionTypeId !in RENDERABLE_DELETE_ACTION_TYPES
        ) {
            return true
        }
        return when (auditLogEntry.targetType) {
            TargetType.CHANNEL -> change.key in setOf("id", "permission_overwrites")
            TargetType.CHANNEL_OVERWRITE -> change.key in setOf("id", "type", "permission_overwrites")
            TargetType.INVITE -> change.key in setOf("uses", "inviter_id")
            TargetType.WEBHOOK -> change.key in setOf("type", "application_id")
            // A bot integration only ever reports `{type, name}`, and the header already names it.
            TargetType.INTEGRATION -> change.key in setOf("type", "name")
            TargetType.THREAD -> change.key in setOf("id", "type")
            TargetType.GUILD_HOME -> change.key == "entity_type"
            TargetType.GUILD_ONBOARDING -> change.key == "prompts"
            TargetType.GUILD_SOUNDBOARD -> change.key in setOf("id", "sound_id")
            TargetType.STICKER -> when (change.key) {
                // A sticker with no description is normal; an *empty* description line is not worth
                // a row, but a real one is.
                "description" -> change.value.toString().isBlank()
                "id", "type", "guild_id", "available", "asset", "format_type" -> true
                else -> false
            }
            else -> false
        }
    }

    /**
     * Fills [textId]'s placeholders from [change].
     *
     * Most keys need nothing but the raw old/new values. The branches below are the keys whose wire
     * representation is not what should be shown: ids that name something, bitfields that are a
     * list, and units that differ.
     *
     * A malformed value must not take the screen down — the reference catches
     * `MissingFormatArgumentException`/`ClassCastException` per change and drops the line. This
     * catches [RuntimeException] for the same reason and logs the offending key.
     */
    private fun getChangeTextWithParams(
        context: Context,
        auditLogEntry: ModelAuditLogEntry,
        change: Change,
        @StringRes textId: Int,
        targets: Map<TargetType, out Map<Long, CharSequence?>>,
    ): CharSequence = try {
        val key = change.key
        when {
            // A channel's `type` is a number on the wire; both sides must become channel-kind names
            // before they are substituted.
            key == "type" && change.newValue != null && auditLogEntry.targetType == TargetType.CHANNEL -> {
                val newText = context.getString(
                    getChannelTypeStringResIdFromValue((change.newValue as Long).toInt()),
                )
                val oldValue = change.oldValue as? Long
                if (oldValue == null) {
                    SettingsStrings.render(context, textId, "newValue" to newText)
                } else {
                    SettingsStrings.render(
                        context,
                        textId,
                        "oldValue" to context.getString(getChannelTypeStringResIdFromValue(oldValue.toInt())),
                        "newValue" to newText,
                    )
                }
            }

            // A channel overwrite names the role or member it applies to, so it needs the header's
            // whole target map; a role's own permission change does not.
            auditLogEntry.targetType == TargetType.CHANNEL_OVERWRITE &&
                key in setOf(KEY_ALLOW, KEY_DENY, KEY_RESET) ->
                renderPermissions(change, auditLogEntry, context, targets)

            change.newValue != null && (key == KEY_ALLOW || key == KEY_DENY) ->
                SettingsStrings.render(
                    context,
                    textId,
                    "count" to "${getPluralString(context, change, auditLogEntry)}\n" +
                        renderPermissionList(change, auditLogEntry, context),
                )

            change.newValue != null && (key == KEY_ROLES_ADD || key == KEY_ROLES_REMOVE) ->
                SettingsStrings.render(
                    context,
                    textId,
                    "count" to renderRoles(context, change),
                )

            change.newValue != null && key in CHANGE_KEYS_REFERENCING_CHANNEL ->
                SettingsStrings.render(
                    context,
                    textId,
                    "newValue" to channelName(targets, change.newValue),
                )

            key == "owner_id" && change.newValue != null ->
                SettingsStrings.render(
                    context,
                    textId,
                    "newValue" to (
                        targets[TargetType.USER]?.get(toId(change.newValue))
                            ?: change.newValue.toString()
                        ),
                )

            // An ISO-8601 timeout expiry; shown as a readable local date.
            key == "communication_disabled_until" && change.newValue != null ->
                SettingsStrings.render(
                    context,
                    textId,
                    "newValue" to TimeUtils.getReadableTimeString(context, change.newValue as String),
                )

            // Durations whose sentence only exists as a plural: the string resource is a bare
            // `{newValue}` slot and the wording, including the unit, lives in the plural.
            pluralValueFor(textId) != 0 -> SettingsStrings.render(
                context,
                textId,
                "newValue" to plural(
                    context,
                    pluralValueFor(textId),
                    (asLong(change.value) ?: 0L).toInt(),
                ),
            )

            key == "video_quality_mode" -> SettingsStrings.render(
                context,
                textId,
                "newValue" to context.getString(
                    if (asLong(change.value) == VIDEO_QUALITY_FULL) {
                        R.string.video_quality_mode_full
                    } else {
                        R.string.video_quality_mode_auto
                    },
                ),
            )

            // Seconds on the wire; the picker that set it only offers named spans. Zero is
            // "never expires", whose sentence has nothing to substitute.
            key == "max_age" && (asLong(change.value) ?: 0L) > 0L -> SettingsStrings.render(
                context,
                textId,
                "newValue" to maxAgeLabel(context, asLong(change.value) ?: 0L),
            )

            // A command-permission override names the role, member or channel it applies to; the
            // change's own key is that id.
            auditLogEntry.targetType == TargetType.APPLICATION_COMMAND && key != "reason" ->
                SettingsStrings.render(
                    context,
                    textId,
                    "subtarget" to commandPermissionSubtarget(auditLogEntry, change, targets, context),
                )

            auditLogEntry.targetType == TargetType.AUTO_MODERATION_RULE ->
                SettingsStrings.render(
                    context,
                    textId,
                    "newValue" to autoModerationValue(context, change, targets),
                )

            key == "default_channel_ids" -> SettingsStrings.render(
                context,
                textId,
                "count" to plural(
                    context,
                    R.plurals.guild_settings_audit_log_onboarding_default_channel_ids_count,
                    (change.value as? Collection<*>)?.size ?: 0,
                ),
            )

            // Seconds on the wire, minutes on screen.
            key == "afk_timeout" && change.newValue != null ->
                SettingsStrings.render(
                    context,
                    textId,
                    "newValue" to (asLong(change.newValue) ?: 0L) / SECONDS_PER_MINUTE,
                )

            // Bits per second on the wire, kbps on screen.
            key == "bitrate" && change.newValue != null ->
                SettingsStrings.render(
                    context,
                    textId,
                    "newValue" to (asLong(change.newValue) ?: 0L) / BITS_PER_KILOBIT,
                )

            key == "color" && change.value != null ->
                SettingsStrings.render(
                    context,
                    textId,
                    "newValue" to hexColor(asLong(change.value) ?: 0L),
                )

            else -> SettingsStrings.render(
                context,
                textId,
                "oldValue" to change.oldValue,
                "newValue" to change.newValue,
            )
        }
    } catch (e: RuntimeException) {
        AppLog.w(
            LOG_TAG,
            "${e.localizedMessage} for change: ${change.key} ${change.newValue} ${change.oldValue}",
        )
        ""
    }

    /**
     * The plural that carries [textId]'s wording, or `0` when the string stands on its own.
     *
     * These sentences are number-dependent all the way through ("Set slowmode to **a second**"),
     * so the whole thing lives in the plural and the string resource is only the slot it goes in.
     */
    @PluralsRes
    private fun pluralValueFor(@StringRes textId: Int): Int = when (textId) {
        R.string.guild_settings_audit_log_member_prune_delete_days ->
            R.plurals.guild_settings_audit_log_member_prune_delete_days_newValue
        R.string.guild_settings_audit_log_integration_expire_grace_period ->
            R.plurals.guild_settings_audit_log_integration_expire_grace_period_newValue
        R.string.guild_settings_audit_log_channel_rate_limit_per_user_create ->
            R.plurals.guild_settings_audit_log_channel_rate_limit_per_user_create_newValue
        R.string.guild_settings_audit_log_channel_rate_limit_per_user_change ->
            R.plurals.guild_settings_audit_log_channel_rate_limit_per_user_change_newValue
        R.string.guild_settings_audit_log_channel_default_auto_archive_duration_create ->
            R.plurals.guild_settings_audit_log_channel_default_auto_archive_duration_create_newValue
        R.string.guild_settings_audit_log_channel_default_auto_archive_duration_change ->
            R.plurals.guild_settings_audit_log_channel_default_auto_archive_duration_change_newValue
        R.string.guild_settings_audit_log_thread_auto_archive_duration_create ->
            R.plurals.guild_settings_audit_log_thread_auto_archive_duration_create_newValue
        R.string.guild_settings_audit_log_thread_auto_archive_duration_change ->
            R.plurals.guild_settings_audit_log_thread_auto_archive_duration_change_newValue
        else -> 0
    }

    /** An invite lifetime as the span the invite settings offer, rather than as raw seconds. */
    private fun maxAgeLabel(context: Context, seconds: Long): CharSequence = when {
        seconds % SECONDS_PER_WEEK == 0L ->
            plural(context, R.plurals.duration_weeks_weeks, (seconds / SECONDS_PER_WEEK).toInt(), "weeks")
        seconds % SECONDS_PER_DAY == 0L ->
            plural(context, R.plurals.duration_days_days, (seconds / SECONDS_PER_DAY).toInt(), "days")
        seconds % SECONDS_PER_HOUR == 0L ->
            plural(context, R.plurals.duration_hours_hours, (seconds / SECONDS_PER_HOUR).toInt(), "hours")
        else ->
            plural(context, R.plurals.duration_minutes_minutes, (seconds / SECONDS_PER_MINUTE).toInt(), "minutes")
    }

    /**
     * The role, member or channel a command-permission override applies to.
     *
     * The override's own id doubles as the change key. Discord uses `guildId - 1` to mean "every
     * channel", which is the only id that names nothing.
     */
    private fun commandPermissionSubtarget(
        auditLogEntry: ModelAuditLogEntry,
        change: Change,
        targets: Map<TargetType, out Map<Long, CharSequence?>>,
        context: Context,
    ): CharSequence {
        val id = change.key?.toLongOrNull() ?: return ""
        val guildId = auditLogEntry.guildId
        if (guildId != null && id == guildId - 1) return context.getString(R.string.all_channels)
        val override = change.newValue as? ModelAuditLogEntry.ChangeNameId
        val type = override?.type ?: 0
        val named = when (type) {
            COMMAND_PERMISSION_TYPE_ROLE -> targets[TargetType.ROLE]?.get(id)
            COMMAND_PERMISSION_TYPE_USER -> targets[TargetType.USER]?.get(id)
            COMMAND_PERMISSION_TYPE_CHANNEL -> targets[TargetType.CHANNEL]?.get(id)
            else -> null
        }
        return named ?: id.toString()
    }

    /**
     * An AutoMod rule field as text.
     *
     * Every field of a rule is either an enum, a list of ids or a list of keywords, so none of them
     * can be substituted raw. Exempt roles and channels resolve against the names the screen already
     * collected, and an empty list reads as "None" rather than as nothing at all.
     */
    private fun autoModerationValue(
        context: Context,
        change: Change,
        targets: Map<TargetType, out Map<Long, CharSequence?>>,
    ): CharSequence {
        val value = change.value
        return when (change.key) {
            "trigger_type" -> context.getString(automodTriggerName(asLong(value)))
            "event_type" -> context.getString(automodEventName(asLong(value)))
            "actions" -> namesOrNone(
                context,
                (value as? Collection<*>).orEmpty()
                    .map { context.getString(automodActionName((it as? ModelAuditLogEntry.ChangeNameId)?.type?.toLong())) },
            )
            "exempt_roles" -> namesOrNone(
                context,
                (value as? Collection<*>).orEmpty()
                    .mapNotNull { targets[TargetType.ROLE]?.get(toId(it)) },
            )
            "exempt_channels" -> namesOrNone(
                context,
                (value as? Collection<*>).orEmpty()
                    .mapNotNull { targets[TargetType.CHANNEL]?.get(toId(it)) },
            )
            else -> (value as? Collection<*>)?.joinToString { "'$it'" } ?: value.toString()
        }
    }

    private fun namesOrNone(context: Context, names: List<CharSequence>): CharSequence =
        if (names.isEmpty()) {
            context.getString(R.string.guild_settings_audit_log_automod_rule_none)
        } else {
            names.joinToString()
        }

    @StringRes
    private fun automodTriggerName(triggerType: Long?): Int = when (triggerType) {
        1L -> R.string.guild_automod_keyword_filter_name
        2L -> R.string.guild_automod_link_filter_name
        3L -> R.string.guild_automod_ml_spam_filter_name
        4L -> R.string.guild_automod_default_keyword_list_filter_name
        5L -> R.string.guild_automod_mention_spam_filter_name
        6L -> R.string.guild_automod_user_profile_filter_name
        else -> R.string.guild_automod_unknown
    }

    @StringRes
    private fun automodEventName(eventType: Long?): Int = when (eventType) {
        1L -> R.string.guild_automod_event_type_message_send
        2L -> R.string.guild_automod_event_type_guild_member_join_or_update
        else -> R.string.guild_automod_unknown
    }

    @StringRes
    private fun automodActionName(actionType: Long?): Int = when (actionType) {
        1L -> R.string.guild_automod_actions_block_message_name
        2L -> R.string.guild_automod_actions_flag_to_channel_name
        3L -> R.string.guild_automod_actions_user_communication_disabled
        4L -> R.string.guild_automod_actions_quarantine_user
        else -> R.string.guild_automod_unknown
    }

    /** `"Granted 3 permissions for @Moderators"` plus the permission names on the next line. */
    private fun renderPermissions(
        change: Change,
        auditLogEntry: ModelAuditLogEntry,
        context: Context,
        targets: Map<TargetType, out Map<Long, CharSequence?>>,
    ): String = getOverridesPluralString(context, change, auditLogEntry, targets) + "\n" +
        renderPermissionList(change, auditLogEntry, context)

    /** The affected permissions, comma separated, in bit order and skipping unknown bits. */
    private fun renderPermissionList(
        change: Change,
        auditLogEntry: ModelAuditLogEntry,
        context: Context,
    ): String {
        @Suppress("UNCHECKED_CAST")
        val bits = change.value as? Set<Long> ?: return ""
        return bits.sorted()
            .map { getStringForPermission(it, auditLogEntry) }
            .filter { it != 0 }
            .map { context.getString(it) }
            .filter { it.isNotEmpty() }
            .joinToString()
    }

    /** `"Added 2 roles\nModerator, Helper"`. */
    private fun renderRoles(context: Context, change: Change): String {
        @Suppress("UNCHECKED_CAST")
        val roles = change.newValue as List<ModelAuditLogEntry.ChangeNameId>
        return "${getPluralString(context, change)}\n" + roles.joinToString { it.name.orEmpty() }
    }

    /**
     * The count phrase alone, for a role or permission list whose subject is already known.
     *
     * A role's own permissions and a channel overwrite's read differently — "Granted 2 permissions"
     * against "Granted 2 permissions for @Moderators" — so the ramp is chosen by what the entry
     * targets rather than by the change key alone.
     */
    private fun getPluralString(
        context: Context,
        change: Change,
        entry: ModelAuditLogEntry? = null,
    ): CharSequence {
        val size = (change.value as? Collection<*>)?.size ?: return ""
        val isRole = entry?.targetType == TargetType.ROLE
        val pluralRes = when (change.key) {
            KEY_ROLES_ADD -> R.plurals.guild_settings_audit_log_member_roles_add_count
            KEY_ROLES_REMOVE -> R.plurals.guild_settings_audit_log_member_roles_remove_count
            KEY_ALLOW -> if (isRole) {
                R.plurals.guild_settings_audit_log_role_permissions_granted_count
            } else {
                R.plurals.guild_settings_audit_log_channel_permission_overrides_granted_count
            }
            KEY_DENY -> if (isRole) {
                R.plurals.guild_settings_audit_log_role_permissions_denied_count
            } else {
                R.plurals.guild_settings_audit_log_channel_permission_overrides_denied_count
            }
            else -> return ""
        }
        return plural(context, pluralRes, size)
    }

    /**
     * `"Granted 3 permissions for @Moderators"` — the count phrase plus the overwrite's subject.
     *
     * The subject is a member for a `type == 1` overwrite and a role for `type == 0`. The role name
     * comes from the entry's own options because a role deleted after the overwrite was written
     * cannot be looked up any more.
     */
    private fun getOverridesPluralString(
        context: Context,
        change: Change,
        entry: ModelAuditLogEntry,
        targets: Map<TargetType, out Map<Long, CharSequence?>>,
    ): String {
        val size = (change.value as? Collection<*>)?.size ?: return ""
        val options = entry.options
        val subtarget: CharSequence = when (options?.type) {
            OVERWRITE_TYPE_MEMBER -> targets[TargetType.USER]?.get(options.id) ?: ""
            OVERWRITE_TYPE_ROLE -> options.roleName ?: ""
            else -> ""
        }
        val (stringRes, pluralRes) = when (change.key) {
            KEY_ALLOW -> R.string.guild_settings_audit_log_channel_permission_overrides_granted to
                R.plurals.guild_settings_audit_log_channel_permission_overrides_granted_count
            KEY_DENY -> R.string.guild_settings_audit_log_channel_permission_overrides_denied to
                R.plurals.guild_settings_audit_log_channel_permission_overrides_denied_count
            KEY_RESET -> R.string.guild_settings_audit_log_channel_permission_overrides_reset to
                R.plurals.guild_settings_audit_log_channel_permission_overrides_reset_count
            else -> return ""
        }
        return SettingsStrings.render(
            context,
            stringRes,
            "count" to plural(context, pluralRes, size),
            "subtarget" to subtarget,
        ).toString()
    }

    // region change key → string resource, one table per target type

    @StringRes
    private fun getGuildChangeString(change: Change): Int = when (change.key) {
        "name" -> R.string.guild_settings_audit_log_guild_name_change
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "icon_hash" -> R.string.guild_settings_audit_log_guild_icon_hash_change
        "banner_hash" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_guild_banner_hash_remove,
            hasNewValue = R.string.guild_settings_audit_log_guild_banner_hash_change,
        )
        "splash_hash" -> R.string.guild_settings_audit_log_guild_splash_hash_change
        "discovery_splash_hash" -> R.string.guild_settings_audit_log_guild_discovery_splash_hash_change
        "region" -> R.string.guild_settings_audit_log_guild_region_change
        "preferred_locale" -> R.string.guild_settings_audit_log_guild_preferred_locale_change
        "owner_id" -> R.string.guild_settings_audit_log_guild_owner_id_change
        "afk_timeout" -> R.string.guild_settings_audit_log_guild_afk_timeout_change
        "system_channel_flags" -> R.string.guild_settings_audit_log_guild_system_channel_flags_change

        // The four keys `transformSystemChannelFlags` splits the bitfield into.
        KEY_SYSTEM_JOIN_NOTIFICATIONS ->
            R.string.guild_settings_audit_log_guild_system_channel_join_notifications
        KEY_SYSTEM_PREMIUM_SUBSCRIPTIONS ->
            R.string.guild_settings_audit_log_guild_system_channel_premium_subscriptions
        KEY_SYSTEM_REMINDER_NOTIFICATIONS ->
            R.string.guild_settings_audit_log_guild_system_channel_reminder_notifications
        KEY_SYSTEM_JOIN_NOTIFICATION_REPLIES ->
            R.string.guild_settings_audit_log_guild_system_channel_join_notification_replies

        "description" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_guild_description_clear,
            hasNewValue = R.string.guild_settings_audit_log_guild_description_change,
        )
        "afk_channel_id" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_guild_afk_channel_id_clear,
            hasNewValue = R.string.guild_settings_audit_log_guild_afk_channel_id_change,
        )
        "system_channel_id" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_guild_system_channel_id_disable,
            hasNewValue = R.string.guild_settings_audit_log_guild_system_channel_id_change,
        )
        "rules_channel_id" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_guild_rules_channel_id_clear,
            hasNewValue = R.string.guild_settings_audit_log_guild_rules_channel_id_change,
        )
        "public_updates_channel_id" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_guild_updates_channel_id_clear,
            hasNewValue = R.string.guild_settings_audit_log_guild_updates_channel_id_change,
        )
        "vanity_url_code" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_guild_vanity_url_code_delete,
            hasNewValue = R.string.guild_settings_audit_log_guild_vanity_url_code_change,
        )
        "widget_channel_id" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_guild_widget_channel_id_delete,
            hasNewValue = R.string.guild_settings_audit_log_guild_widget_channel_id_change,
        )

        "widget_enabled" -> if (change.value == true) {
            R.string.guild_settings_audit_log_guild_widget_enabled
        } else {
            R.string.guild_settings_audit_log_guild_widget_disabled
        }
        "premium_progress_bar_enabled" -> if (change.value == true) {
            R.string.guild_settings_audit_log_guild_premium_progress_bar_enabled_enabled
        } else {
            R.string.guild_settings_audit_log_guild_premium_progress_bar_enabled_disabled
        }
        // MFA level is a two-state enum on the wire; anything non-zero means "required".
        "mfa_level" -> if (asLong(change.value) == 0L) {
            R.string.guild_settings_audit_log_guild_mfa_level_disabled
        } else {
            R.string.guild_settings_audit_log_guild_mfa_level_enabled
        }
        "explicit_content_filter" -> when (asLong(change.value)) {
            0L -> R.string.guild_settings_audit_log_guild_explicit_content_filter_disable
            1L -> R.string.guild_settings_audit_log_guild_explicit_content_filter_members_without_roles
            else -> R.string.guild_settings_audit_log_guild_explicit_content_filter_all_members
        }
        "verification_level" -> when (asLong(change.value)) {
            0L -> R.string.guild_settings_audit_log_guild_verification_level_change_none
            1L -> R.string.guild_settings_audit_log_guild_verification_level_change_low
            2L -> R.string.guild_settings_audit_log_guild_verification_level_change_medium
            3L -> R.string.guild_settings_audit_log_guild_verification_level_change_high
            else -> R.string.guild_settings_audit_log_guild_verification_level_change_very_high
        }
        "default_message_notifications" -> if (asLong(change.value) == 0L) {
            R.string.guild_settings_audit_log_guild_default_message_notifications_change_all_messages
        } else {
            R.string.guild_settings_audit_log_guild_default_message_notifications_change_only_mentions
        }

        else -> 0
    }

    @StringRes
    private fun getChannelChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        KEY_ALLOW -> R.string.guild_settings_audit_log_channel_permission_overrides_granted
        KEY_DENY -> R.string.guild_settings_audit_log_channel_permission_overrides_denied
        KEY_RESET -> R.string.guild_settings_audit_log_channel_permission_overrides_reset

        "name" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_channel_name_create,
            hasOldValue = R.string.guild_settings_audit_log_channel_name_change,
        )
        "type" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_channel_type_create,
            hasOldValue = R.string.guild_settings_audit_log_channel_type_change,
        )
        "position" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_channel_position_create,
            hasOldValue = R.string.guild_settings_audit_log_channel_position_change,
        )
        "bitrate" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_channel_bitrate_create,
            hasOldValue = R.string.guild_settings_audit_log_channel_bitrate_change,
        )
        "video_quality_mode" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_channel_video_quality_mode_create,
            hasOldValue = R.string.guild_settings_audit_log_channel_video_quality_mode_change,
        )
        "user_limit" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_channel_user_limit_create,
            hasOldValue = R.string.guild_settings_audit_log_channel_user_limit_change,
        )
        // Zero turns each of these off entirely, which reads as its own sentence rather than as
        // "0 seconds"; Android plurals have no `=0` case to express that.
        "rate_limit_per_user" -> if (asLong(change.value) == 0L) {
            R.string.guild_settings_audit_log_channel_rate_limit_per_user_disable
        } else {
            change.nullableOldValueString(
                hasNoOldValue = R.string.guild_settings_audit_log_channel_rate_limit_per_user_create,
                hasOldValue = R.string.guild_settings_audit_log_channel_rate_limit_per_user_change,
            )
        }
        "default_auto_archive_duration" -> if (asLong(change.value) == 0L) {
            R.string.guild_settings_audit_log_channel_default_auto_archive_duration_remove
        } else {
            change.nullableOldValueString(
                hasNoOldValue = R.string.guild_settings_audit_log_channel_default_auto_archive_duration_create,
                hasOldValue = R.string.guild_settings_audit_log_channel_default_auto_archive_duration_change,
            )
        }

        // A topic and a voice region can each be set, changed or cleared, so both nulls matter.
        "topic" -> change.nullableNewOrOldValueString(
            hasBoth = R.string.guild_settings_audit_log_channel_topic_change,
            hasNoOldValue = R.string.guild_settings_audit_log_channel_topic_create,
            hasNoNewValue = R.string.guild_settings_audit_log_channel_topic_clear,
        )
        "rtc_region" -> change.nullableNewOrOldValueString(
            hasBoth = R.string.guild_settings_audit_log_channel_rtc_region_override_change,
            hasNoOldValue = R.string.guild_settings_audit_log_channel_rtc_region_override_create,
            hasNoNewValue = R.string.guild_settings_audit_log_channel_rtc_region_override_delete,
        )

        "nsfw" -> if (change.value == true) {
            R.string.guild_settings_audit_log_channel_nsfw_enabled
        } else {
            R.string.guild_settings_audit_log_channel_nsfw_disabled
        }

        else -> 0
    }

    @StringRes
    private fun getUserChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "prune_delete_days" -> R.string.guild_settings_audit_log_member_prune_delete_days
        // Synthesised for an AutoMod enforcement entry, whose target is the member it hit.
        "triggered_rule_name" ->
            R.string.guild_settings_audit_log_auto_moderation_triggered_rule_name_change
        KEY_ROLES_ADD -> R.string.guild_settings_audit_log_member_roles_add
        KEY_ROLES_REMOVE -> R.string.guild_settings_audit_log_member_roles_remove

        "nick" -> change.nullableNewOrOldValueString(
            hasBoth = R.string.guild_settings_audit_log_member_nick_change,
            hasNoOldValue = R.string.guild_settings_audit_log_member_nick_create,
            hasNoNewValue = R.string.guild_settings_audit_log_member_nick_delete,
        )
        "communication_disabled_until" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_communication_disabled_until_removed,
            hasNewValue = R.string.guild_settings_audit_log_communication_disabled_until,
        )

        "deaf" -> if (change.value == true) {
            R.string.guild_settings_audit_log_member_deaf_on
        } else {
            R.string.guild_settings_audit_log_member_deaf_off
        }
        "mute" -> if (change.value == true) {
            R.string.guild_settings_audit_log_member_mute_on
        } else {
            R.string.guild_settings_audit_log_member_mute_off
        }

        else -> 0
    }

    @StringRes
    private fun getRoleChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        KEY_ALLOW -> R.string.guild_settings_audit_log_role_permissions_granted
        KEY_DENY -> R.string.guild_settings_audit_log_role_permissions_denied
        "icon_hash" -> R.string.guild_settings_audit_log_role_icon_hash_change
        "unicode_emoji" -> R.string.guild_settings_audit_log_role_unicode_emoji_change

        "name" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_role_name_create,
            hasOldValue = R.string.guild_settings_audit_log_role_name_change,
        )

        // Colour 0 is "no colour" — the role inherits, and there is no swatch to show.
        "color" -> if (asLong(change.value) == 0L) {
            R.string.guild_settings_audit_log_role_color_none
        } else {
            R.string.guild_settings_audit_log_role_color
        }
        "hoist" -> if (change.value == true) {
            R.string.guild_settings_audit_log_role_hoist_on
        } else {
            R.string.guild_settings_audit_log_role_hoist_off
        }
        "mentionable" -> if (change.value == true) {
            R.string.guild_settings_audit_log_role_mentionable_on
        } else {
            R.string.guild_settings_audit_log_role_mentionable_off
        }

        else -> 0
    }

    @StringRes
    private fun getInviteChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "code" -> R.string.guild_settings_audit_log_invite_code_create
        "channel_id" -> R.string.guild_settings_audit_log_invite_channel_create

        // 0 means "never expires" / "unlimited", which reads as a different sentence entirely.
        "max_age" -> if (asLong(change.value) == 0L) {
            R.string.guild_settings_audit_log_invite_max_age_create_infinite
        } else {
            R.string.guild_settings_audit_log_invite_max_age_create
        }
        "max_uses" -> if (asLong(change.value) == 0L) {
            R.string.guild_settings_audit_log_invite_max_uses_create_infinite
        } else {
            R.string.guild_settings_audit_log_invite_max_uses_create
        }
        "temporary" -> if (change.value == true) {
            R.string.guild_settings_audit_log_invite_temporary_on
        } else {
            R.string.guild_settings_audit_log_invite_temporary_off
        }

        else -> 0
    }

    @StringRes
    private fun getWebhookChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "avatar_hash" -> R.string.guild_settings_audit_log_webhook_avatar
        "name" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_webhook_name_create,
            hasOldValue = R.string.guild_settings_audit_log_webhook_name_change,
        )
        "channel_id" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_webhook_channel_create,
            hasOldValue = R.string.guild_settings_audit_log_webhook_channel_change,
        )
        else -> 0
    }

    @StringRes
    private fun getEmojiChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "name" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_emoji_name_create,
            hasOldValue = R.string.guild_settings_audit_log_emoji_name_change,
        )
        else -> 0
    }

    @StringRes
    private fun getIntegrationChangeString(change: Change): Int = when (change.key) {
        "expire_grace_period" -> R.string.guild_settings_audit_log_integration_expire_grace_period
        "expire_behavior" -> if (asLong(change.value) == 0L) {
            R.string.guild_settings_audit_log_integration_expire_behavior_remove_synced_role
        } else {
            R.string.guild_settings_audit_log_integration_expire_behavior_kick_from_server
        }
        "enable_emoticons" -> if (change.value == true) {
            R.string.guild_settings_audit_log_integration_enable_emoticons_on
        } else {
            R.string.guild_settings_audit_log_integration_enable_emoticons_off
        }
        else -> 0
    }

    @StringRes
    private fun getStageInstanceChangeString(change: Change): Int = when (change.key) {
        "privacy_level" -> if (asLong(change.value) == STAGE_PRIVACY_GUILD_ONLY) {
            R.string.guild_settings_audit_log_stage_instance_privacy_level_guild_only
        } else {
            R.string.guild_settings_audit_log_stage_instance_privacy_level_public
        }
        // A stage's topic reuses the channel topic strings — same wording, same shape.
        "topic" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_channel_topic_create,
            hasOldValue = R.string.guild_settings_audit_log_channel_topic_change,
        )
        else -> 0
    }

    @StringRes
    private fun getGuildScheduledEventChangeString(change: Change): Int = when (change.key) {
        "name" -> R.string.guild_settings_audit_log_scheduled_event_name_create
        "description" -> R.string.guild_settings_audit_log_scheduled_event_description_create

        "channel_id" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_scheduled_event_channel_clear,
            hasNewValue = R.string.guild_settings_audit_log_scheduled_event_channel,
        )
        "location" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_scheduled_event_location_clear,
            hasNewValue = R.string.guild_settings_audit_log_scheduled_event_location,
        )
        "image_hash" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_scheduled_event_image_hash_remove,
            hasNewValue = R.string.guild_settings_audit_log_scheduled_event_image_hash_change,
        )

        "status" -> when (asLong(change.value)) {
            1L -> R.string.guild_settings_audit_log_scheduled_event_status_scheduled
            2L -> R.string.guild_settings_audit_log_scheduled_event_status_active
            3L -> R.string.guild_settings_audit_log_scheduled_event_status_completed
            else -> R.string.guild_settings_audit_log_scheduled_event_status_canceled
        }
        "entity_type" -> when (asLong(change.value)) {
            0L -> R.string.guild_settings_audit_log_scheduled_event_entity_type_none
            1L -> R.string.guild_settings_audit_log_scheduled_event_entity_type_stage_instance
            2L -> R.string.guild_settings_audit_log_scheduled_event_entity_type_voice
            else -> R.string.guild_settings_audit_log_scheduled_event_entity_type_external
        }
        "privacy_level" -> if (asLong(change.value) == STAGE_PRIVACY_GUILD_ONLY) {
            R.string.guild_settings_audit_log_stage_instance_privacy_level_guild_only
        } else {
            R.string.guild_settings_audit_log_stage_instance_privacy_level_public
        }

        else -> 0
    }

    @StringRes
    private fun getStickerChangeString(change: Change): Int = when (change.key) {
        "name" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_name_create,
            hasOldValue = R.string.guild_settings_audit_log_name_change,
        )
        "description" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_description_create,
            hasOldValue = R.string.guild_settings_audit_log_description_change,
        )
        "tags" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_tags_create,
            hasOldValue = R.string.guild_settings_audit_log_tags_change,
        )
        else -> 0
    }

    @StringRes
    private fun getThreadChangeString(change: Change): Int = when (change.key) {
        "name" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_thread_name_create,
            hasOldValue = R.string.guild_settings_audit_log_thread_name_change,
        )
        "rate_limit_per_user" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_channel_rate_limit_per_user_create,
            hasOldValue = R.string.guild_settings_audit_log_channel_rate_limit_per_user_change,
        )
        "auto_archive_duration" -> if (asLong(change.value) == 0L) {
            R.string.guild_settings_audit_log_thread_auto_archive_duration_remove
        } else {
            change.nullableOldValueString(
                hasNoOldValue = R.string.guild_settings_audit_log_thread_auto_archive_duration_create,
                hasOldValue = R.string.guild_settings_audit_log_thread_auto_archive_duration_change,
            )
        }
        "archived" -> if (change.value == true) {
            R.string.guild_settings_audit_log_thread_archived
        } else {
            R.string.guild_settings_audit_log_thread_unarchived
        }
        "locked" -> if (change.value == true) {
            R.string.guild_settings_audit_log_thread_locked
        } else {
            R.string.guild_settings_audit_log_thread_unlocked
        }
        "invitable" -> if (change.value == true) {
            R.string.guild_settings_audit_log_thread_invitable_enabled
        } else {
            R.string.guild_settings_audit_log_thread_invitable_disabled
        }
        else -> 0
    }

    /**
     * A command-permission change is keyed by the *snowflake* it applies to, not by a field name,
     * so the table cannot be a lookup: the sentence is chosen from the override itself. A change
     * with no new value removed the override; otherwise the `permission` flag says which way it went.
     */
    @StringRes
    private fun getCommandPermissionChangeString(change: Change): Int {
        if (change.key == "reason") return R.string.guild_settings_audit_log_common_reason
        val override = change.newValue as? ModelAuditLogEntry.ChangeNameId
            ?: return R.string.guild_settings_audit_log_command_permission_removed
        return if (override.permission == true) {
            R.string.guild_settings_audit_log_command_permission_granted
        } else {
            R.string.guild_settings_audit_log_command_permission_denied
        }
    }

    @StringRes
    private fun getAutoModerationRuleChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "name" -> R.string.guild_settings_audit_log_automod_rule_name_update
        "trigger_type" -> R.string.guild_settings_audit_log_automod_rule_trigger_type_update
        "event_type" -> R.string.guild_settings_audit_log_automod_rule_event_type_update
        "actions" -> R.string.guild_settings_audit_log_automod_rule_actions_update
        "trigger_metadata" -> R.string.guild_settings_audit_log_automod_rule_trigger_metadata_update
        "exempt_roles" -> R.string.guild_settings_audit_log_automod_rule_exempt_roles_update
        "exempt_channels" -> R.string.guild_settings_audit_log_automod_rule_exempt_channels_update
        "triggered_rule_name" ->
            R.string.guild_settings_audit_log_auto_moderation_triggered_rule_name_change
        KEY_AUTOMOD_ADD_KEYWORDS -> R.string.guild_settings_audit_log_automod_rule_add_keywords
        KEY_AUTOMOD_REMOVE_KEYWORDS -> R.string.guild_settings_audit_log_automod_rule_remove_keywords
        KEY_AUTOMOD_ADD_REGEX -> R.string.guild_settings_audit_log_automod_rule_add_regex_patterns
        KEY_AUTOMOD_REMOVE_REGEX ->
            R.string.guild_settings_audit_log_automod_rule_remove_regex_patterns
        KEY_AUTOMOD_ADD_ALLOW_LIST -> R.string.guild_settings_audit_log_automod_rule_add_allow_list
        KEY_AUTOMOD_REMOVE_ALLOW_LIST ->
            R.string.guild_settings_audit_log_automod_rule_remove_allow_list

        // A rule delete carries `enabled` as an old value only, so both sides are consulted.
        "enabled" -> if ((change.newValue ?: change.oldValue) == true) {
            R.string.guild_settings_audit_log_automod_rule_enabled_update
        } else {
            R.string.guild_settings_audit_log_automod_rule_disabled_update
        }
        else -> 0
    }

    @StringRes
    private fun getSoundboardChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "name" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_guild_soundboard_sound_name_create,
            hasOldValue = R.string.guild_settings_audit_log_guild_soundboard_sound_name_change,
        )
        "volume" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_guild_soundboard_sound_volume_create,
            hasOldValue = R.string.guild_settings_audit_log_guild_soundboard_sound_volume_change,
        )
        "emoji_name" -> change.nullableNewOrOldValueString(
            hasBoth = R.string.guild_settings_audit_log_guild_soundboard_sound_emoji_name_replace,
            hasNoOldValue = R.string.guild_settings_audit_log_guild_soundboard_sound_emoji_name_create,
            hasNoNewValue = R.string.guild_settings_audit_log_guild_soundboard_sound_emoji_name_remove,
        )
        // `emoji_id` names a custom emoji the row would have to draw; there is no span for that
        // here, so the change is left to the caller's skip-and-log path.
        else -> 0
    }

    @StringRes
    private fun getOnboardingPromptChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "options" -> R.string.guild_settings_audit_log_onboarding_prompt_changed_options
        "title" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_role_prompt_title_create,
            hasOldValue = R.string.guild_settings_audit_log_role_prompt_title_change,
        )
        "description" -> change.nullableOldValueString(
            hasNoOldValue = R.string.guild_settings_audit_log_role_prompt_description_create,
            hasOldValue = R.string.guild_settings_audit_log_role_prompt_description_change,
        )
        "single_select" -> if (change.newValue == true) {
            R.string.guild_settings_audit_log_role_prompt_single_select
        } else {
            R.string.guild_settings_audit_log_role_prompt_multi_select
        }
        "required" -> if (change.newValue == true) {
            R.string.guild_settings_audit_log_role_prompt_required
        } else {
            R.string.guild_settings_audit_log_role_prompt_optional
        }
        else -> 0
    }

    @StringRes
    private fun getOnboardingChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "default_channel_ids" -> R.string.guild_settings_audit_log_onboarding_default_channel_ids
        "enable_default_channels" -> if (change.newValue == true) {
            R.string.guild_settings_audit_log_onboarding_enable_default_channels
        } else {
            R.string.guild_settings_audit_log_onboarding_disable_default_channels
        }
        "enable_onboarding_prompts" -> if (change.newValue == true) {
            R.string.guild_settings_audit_log_onboarding_enable_prompts
        } else {
            R.string.guild_settings_audit_log_onboarding_disable_prompts
        }
        "enabled" -> if (change.newValue == true) {
            R.string.guild_settings_audit_log_onboarding_enable
        } else {
            R.string.guild_settings_audit_log_onboarding_disable
        }
        else -> 0
    }

    @StringRes
    private fun getHomeSettingsChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "welcome_message" -> R.string.guild_settings_audit_log_home_settings_changed_welcome_message
        "new_member_actions" ->
            R.string.guild_settings_audit_log_home_settings_changed_new_member_actions
        "resource_channels" ->
            R.string.guild_settings_audit_log_home_settings_changed_resource_channels
        else -> 0
    }

    @StringRes
    private fun getVoiceChannelStatusChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "status" -> R.string.guild_settings_audit_log_voice_channel_status_status_create
        else -> 0
    }

    @StringRes
    private fun getMemberVerificationChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "verification_enabled" -> if (change.newValue == true) {
            R.string.guild_settings_audit_log_verification_enabled
        } else {
            R.string.guild_settings_audit_log_verification_disabled
        }
        "manual_approval_enabled" -> if (change.newValue == true) {
            R.string.guild_settings_audit_log_manual_verification_enabled
        } else {
            R.string.guild_settings_audit_log_manual_verification_disabled
        }
        else -> 0
    }

    @StringRes
    private fun getGuildProfileChangeString(change: Change): Int = when (change.key) {
        "description" -> R.string.guild_settings_audit_log_guild_profile_description_update
        "brand_color_primary" -> R.string.guild_settings_audit_log_guild_profile_color_update
        "custom_banner_hash" -> R.string.guild_settings_audit_log_guild_profile_banner_update
        "traits" -> R.string.guild_settings_audit_log_guild_profile_traits_update
        "game_application_ids" -> R.string.guild_settings_audit_log_guild_profile_games_update
        "visibility" -> R.string.guild_settings_audit_log_guild_profile_visibility_update
        "server_tag" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_guild_tag_cleared,
            hasNewValue = R.string.guild_settings_audit_log_guild_tag_update,
        )
        else -> 0
    }

    @StringRes
    private fun getScheduledEventExceptionChangeString(change: Change): Int = when (change.key) {
        "reason" -> R.string.guild_settings_audit_log_common_reason
        "scheduled_start_time" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_scheduled_event_exception_scheduled_start_time_removed,
            hasNewValue = R.string.guild_settings_audit_log_scheduled_event_exception_scheduled_start_time_change,
        )
        "scheduled_end_time" -> change.nullableNewValueString(
            hasNoNewValue = R.string.guild_settings_audit_log_scheduled_event_exception_scheduled_end_time_removed,
            hasNewValue = R.string.guild_settings_audit_log_scheduled_event_exception_scheduled_end_time_change,
        )
        // Only an actual flip is worth a line: the field is present on every exception entry.
        "is_canceled" -> when {
            change.oldValue != true && change.newValue == true ->
                R.string.guild_settings_audit_log_scheduled_event_exception_is_canceled_canceled
            change.oldValue == true && change.newValue != true ->
                R.string.guild_settings_audit_log_scheduled_event_exception_is_canceled_restored
            else -> 0
        }
        else -> 0
    }

    // endregion

    /**
     * The display name of a single permission bit.
     *
     * `MANAGE_CHANNELS` is the only bit whose wording depends on context: on a channel or an
     * overwrite it governs *that* channel, elsewhere it governs all of them.
     */
    @StringRes
    private fun getStringForPermission(permission: PermissionBit, log: ModelAuditLogEntry): Int =
        when (permission) {
            Permission.CREATE_INSTANT_INVITE -> R.string.create_instant_invite
            Permission.KICK_MEMBERS -> R.string.kick_members
            Permission.BAN_MEMBERS -> R.string.ban_members
            Permission.ADMINISTRATOR -> R.string.administrator
            Permission.MANAGE_CHANNELS ->
                if (log.targetType == TargetType.CHANNEL || log.targetType == TargetType.CHANNEL_OVERWRITE) {
                    R.string.manage_channel
                } else {
                    R.string.manage_channels
                }
            Permission.MANAGE_GUILD -> R.string.manage_server
            Permission.ADD_REACTIONS -> R.string.add_reactions
            Permission.VIEW_AUDIT_LOG -> R.string.view_audit_log
            Permission.PRIORITY_SPEAKER -> R.string.priority_speaker
            Permission.STREAM -> R.string.video
            Permission.VIEW_CHANNEL -> R.string.read_messages
            Permission.SEND_MESSAGES -> R.string.send_messages
            Permission.SEND_TTS_MESSAGES -> R.string.send_tts_messages
            Permission.MANAGE_MESSAGES -> R.string.manage_messages
            Permission.EMBED_LINKS -> R.string.embed_links
            Permission.ATTACH_FILES -> R.string.attach_files
            Permission.READ_MESSAGE_HISTORY -> R.string.read_message_history
            Permission.MENTION_EVERYONE -> R.string.mention_everyone_android
            Permission.USE_EXTERNAL_EMOJIS -> R.string.use_external_emojis
            Permission.VIEW_GUILD_ANALYTICS -> R.string.view_guild_analytics
            Permission.CONNECT -> R.string.connect
            Permission.SPEAK -> R.string.speak
            Permission.MUTE_MEMBERS -> R.string.mute_members
            Permission.DEAFEN_MEMBERS -> R.string.deafen_members
            Permission.MOVE_MEMBERS -> R.string.move_members
            Permission.USE_VAD -> R.string.use_vad
            Permission.CHANGE_NICKNAME -> R.string.change_nickname
            Permission.MANAGE_NICKNAMES -> R.string.manage_nicknames
            Permission.MANAGE_ROLES -> R.string.manage_roles
            Permission.MANAGE_WEBHOOKS -> R.string.manage_webhooks
            Permission.MANAGE_GUILD_EXPRESSIONS -> R.string.manage_expressions
            Permission.USE_APPLICATION_COMMANDS -> R.string.use_application_commands
            Permission.REQUEST_TO_SPEAK -> R.string.request_to_speak
            Permission.MANAGE_EVENTS -> R.string.manage_events
            Permission.MANAGE_THREADS -> R.string.manage_threads
            Permission.CREATE_PUBLIC_THREADS -> R.string.create_public_threads
            Permission.CREATE_PRIVATE_THREADS -> R.string.create_private_threads
            Permission.USE_EXTERNAL_STICKERS -> R.string.role_permissions_use_external_stickers
            Permission.SEND_MESSAGES_IN_THREADS -> R.string.send_messages_in_threads
            Permission.MODERATE_MEMBERS -> R.string.moderate_member
            Permission.BYPASS_SLOWMODE -> R.string.bypass_slowmode
            Permission.USE_EMBEDDED_ACTIVITIES -> R.string.use_embedded_activities
            Permission.SEND_POLLS -> R.string.role_permissions_send_polls
            Permission.VIEW_CREATOR_MONETIZATION_ANALYTICS -> R.string.view_creator_monetization_analytics
            Permission.USE_SOUNDBOARD -> R.string.use_soundboard
            Permission.CREATE_GUILD_EXPRESSIONS -> R.string.create_expressions
            Permission.CREATE_EVENTS -> R.string.create_events
            Permission.USE_EXTERNAL_SOUNDS -> R.string.use_external_sounds
            Permission.SEND_VOICE_MESSAGES -> R.string.role_permissions_send_voice_message
            Permission.SET_VOICE_CHANNEL_STATUS -> R.string.voice_channel_status_permission_title
            Permission.USE_EXTERNAL_APPS -> R.string.role_permissions_use_external_apps
            Permission.PIN_MESSAGES -> R.string.pin_messages
            Permission.MANAGE_OFFICIAL_MESSAGES -> R.string.role_permissions_manage_official_messages
            else -> 0
        }

    /** The channel-kind noun for a raw channel `type`. */
    @StringRes
    private fun getChannelTypeStringResIdFromValue(value: Int?): Int = when (value) {
        1 -> R.string.dm
        3 -> R.string.group_dm
        0 -> R.string.text_channel
        2 -> R.string.voice_channel
        5 -> R.string.news_channel
        6 -> R.string.store_channel
        13 -> R.string.stage_channel
        4 -> R.string.category
        15 -> R.string.forum_channel
        16 -> R.string.media_channel
        else -> R.string.thread
    }

    /** Green for a creation, yellow for an update, red for a deletion. */
    private fun getTextColor(context: Context, auditLogEntry: ModelAuditLogEntry): Int =
        ColorCompat.getColor(
            context,
            when (auditLogEntry.actionType) {
                ModelAuditLogEntry.ActionType.CREATE -> R.color.status_green_500
                ModelAuditLogEntry.ActionType.UPDATE -> R.color.status_yellow_400
                ModelAuditLogEntry.ActionType.DELETE -> R.color.status_red_400
                else -> R.color.primary_300
            },
        )

    /**
     * Appends a `●` tinted with the colour a `color` change set.
     *
     * Nothing is appended for colour 0 — that is "no colour", and a black dot would read as a real
     * choice.
     */
    private fun appendColorSwatch(builder: SpannableStringBuilder, change: Change) {
        if (change.key != "color") return
        val color = asLong(change.value) ?: return
        if (color == 0L) return
        builder.append(COLOR_SWATCH)
        builder.setSpan(
            ForegroundColorSpan(Color.parseColor(hexColor(color))),
            builder.length - COLOR_SWATCH.length,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /** Zero-pads single-digit line numbers so a 10+ line block stays aligned. */
    private fun getChangeNumberString(number: Int): String =
        if (number in 0..9) "0$number" else number.toString()

    /** `#RRGGBB`, discarding any alpha the wire value carries. */
    private fun hexColor(color: Long): String = String.format("#%06X", RGB_MASK and color.toInt())

    private fun channelName(
        targets: Map<TargetType, out Map<Long, CharSequence?>>,
        value: Any?,
    ): CharSequence = targets[TargetType.CHANNEL]?.get(toId(value)) ?: value.toString()

    /** Snowflakes arrive as either a decimal string or a number depending on the field. */
    private fun toId(value: Any?): Long? = when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun asLong(value: Any?): Long? = toId(value)

    /**
     * A quantity phrase from a plural resource.
     *
     * Discord's plurals do not agree on what the quantity placeholder is called — `{count}`,
     * `{newValue}` and `{#}` all appear, and the shared duration plurals use their own unit name —
     * so every spelling is bound to the same number rather than each caller having to know which
     * one its resource happens to use.
     */
    private fun plural(
        context: Context,
        pluralRes: Int,
        quantity: Int,
        unitKey: String? = null,
    ): CharSequence = com.discord.i18n.FormatUtils.format(
        context.resources.getQuantityString(pluralRes, quantity),
        buildMap {
            put("count", quantity)
            put("newValue", quantity)
            put("#", quantity)
            unitKey?.let { put(it, quantity) }
        },
    )

    /** The string to use when only one of the two values is present. */
    private fun Change.nullableNewOrOldValueString(
        @StringRes hasBoth: Int = 0,
        @StringRes hasNoOldValue: Int = 0,
        @StringRes hasNoNewValue: Int = 0,
        @StringRes hasNeither: Int = 0,
    ): Int = when {
        newValue != null && oldValue != null -> hasBoth
        newValue != null -> hasNoOldValue
        oldValue != null -> hasNoNewValue
        else -> hasNeither
    }

    private fun Change.nullableNewValueString(
        @StringRes hasNoNewValue: Int,
        @StringRes hasNewValue: Int,
    ): Int = if (newValue != null) hasNewValue else hasNoNewValue

    private fun Change.nullableOldValueString(
        @StringRes hasNoOldValue: Int,
        @StringRes hasOldValue: Int,
    ): Int = if (oldValue != null) hasOldValue else hasNoOldValue

    private const val LOG_TAG = "Audit Logs"
    private const val COLOR_SWATCH = "●"
    private const val RGB_MASK = 0xFFFFFF

    private const val KEY_ALLOW = "allow"
    private const val KEY_DENY = "deny"
    private const val KEY_RESET = "reset"
    private const val KEY_ROLES_ADD = "\$add"
    private const val KEY_ROLES_REMOVE = "\$remove"
    private const val KEY_AUTOMOD_ADD_KEYWORDS = "\$add_keyword_filter"
    private const val KEY_AUTOMOD_REMOVE_KEYWORDS = "\$remove_keyword_filter"
    private const val KEY_AUTOMOD_ADD_REGEX = "\$add_regex_patterns"
    private const val KEY_AUTOMOD_REMOVE_REGEX = "\$remove_regex_patterns"
    private const val KEY_AUTOMOD_ADD_ALLOW_LIST = "\$add_allow_list"
    private const val KEY_AUTOMOD_REMOVE_ALLOW_LIST = "\$remove_allow_list"

    // Synthesised by `StoreAuditLog` out of the `system_channel_flags` bitfield; no such key
    // exists on the wire.
    const val KEY_SYSTEM_JOIN_NOTIFICATIONS = "join_notifications"
    const val KEY_SYSTEM_PREMIUM_SUBSCRIPTIONS = "premium_subscriptions"
    const val KEY_SYSTEM_REMINDER_NOTIFICATIONS = "reminder_notifications"
    const val KEY_SYSTEM_JOIN_NOTIFICATION_REPLIES = "join_notification_replies"

    private const val ACTION_MEMBER_KICK = 20
    private const val ACTION_MEMBER_PRUNE = 21
    private const val ACTION_MEMBER_BAN_ADD = 22
    private const val ACTION_AUTO_MODERATION_BLOCK_MESSAGE = 143

    /** A permission bitfield is a signed 64-bit value; bit 63 is the last one that can be set. */
    private const val MAX_PERMISSION_BIT_INDEX = 63

    private const val OVERWRITE_TYPE_ROLE = 0
    private const val OVERWRITE_TYPE_MEMBER = 1
    private const val STAGE_PRIVACY_GUILD_ONLY = 2L

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86400L
    private const val SECONDS_PER_WEEK = 604800L
    private const val BITS_PER_KILOBIT = 1000L

    private const val VIDEO_QUALITY_FULL = 2L

    private const val COMMAND_PERMISSION_TYPE_ROLE = 1
    private const val COMMAND_PERMISSION_TYPE_USER = 2
    private const val COMMAND_PERMISSION_TYPE_CHANNEL = 3
}
