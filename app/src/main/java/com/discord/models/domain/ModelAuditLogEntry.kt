package com.discord.models.domain

import com.google.gson.stream.JsonToken

/**
 * One row of a guild's audit log.
 *
 * The wire format only carries [actionTypeId]; [actionType] and [targetType] are derived from it so
 * the UI can pick a verb and an icon without a second lookup table. [guildId] and [timestampEnd]
 * are not part of the entry payload — the store stamps them from the request that produced the page.
 */
class ModelAuditLogEntry : Model {

    var id: Long = 0
        private set
    var actionTypeId: Int = 0
        private set
    var targetId: Long = 0
        private set
    var userId: Long = 0
        private set
    var changes: List<Change>? = null
        private set
    var options: Options? = null
        private set
    var reason: String? = null
        private set
    var guildId: Long? = null
        private set
    var timestampEnd: Long? = null
        private set

    constructor() {
        // Parsed field by field through assignField.
    }

    constructor(
        id: Long,
        actionTypeId: Int,
        targetId: Long,
        userId: Long,
        changes: List<Change>?,
        options: Options?,
        guildId: Long,
        timestampEnd: Long?,
    ) {
        this.id = id
        this.actionTypeId = actionTypeId
        this.targetId = targetId
        this.userId = userId
        this.changes = changes
        this.options = options
        this.guildId = guildId
        this.timestampEnd = timestampEnd
    }

    val actionType: ActionType get() = getActionType(actionTypeId)

    val targetType: TargetType get() = getTargetType(actionTypeId)

    override fun assignField(reader: Model.JsonReader) {
        when (reader.nextName()) {
            "id" -> id = reader.nextLong(id)
            "action_type" -> actionTypeId = reader.nextInt(actionTypeId)
            "target_id" -> targetId = reader.nextLong(targetId)
            "user_id" -> userId = reader.nextLong(userId)
            "changes" -> changes = reader.nextList { reader.parse(Change()) }
            "options" -> options = reader.parse(Options())
            "reason" -> reason = reader.nextString(reason)
            else -> reader.skipValue()
        }
    }

    override fun equals(other: Any?): Boolean = other is ModelAuditLogEntry &&
        id == other.id && actionTypeId == other.actionTypeId && targetId == other.targetId &&
        userId == other.userId && changes == other.changes && options == other.options &&
        reason == other.reason && guildId == other.guildId && timestampEnd == other.timestampEnd

    override fun hashCode(): Int = listOf(
        id, actionTypeId, targetId, userId, changes, options, reason, guildId, timestampEnd,
    ).hashCode()

    override fun toString(): String = "ModelAuditLogEntry(id=$id, actionTypeId=$actionTypeId, " +
        "targetId=$targetId, userId=$userId, changes=$changes, options=$options, reason=$reason, " +
        "guildId=$guildId, timestampEnd=$timestampEnd)"

    /** Which side of a create/update/delete pair an action sits on. */
    enum class ActionType { ALL, CREATE, UPDATE, DELETE }

    /** What kind of object an action was performed on. */
    enum class TargetType {
        ALL,
        UNKNOWN,
        GUILD,
        CHANNEL,
        CHANNEL_OVERWRITE,
        USER,
        ROLE,
        INVITE,
        WEBHOOK,
        EMOJI,
        INTEGRATION,
        STAGE_INSTANCE,
        GUILD_SCHEDULED_EVENT,
        STICKER,
        THREAD,
        APPLICATION_COMMAND,
        GUILD_SOUNDBOARD,
        AUTO_MODERATION_RULE,
        ONBOARDING_PROMPT,
        GUILD_ONBOARDING,
        GUILD_HOME,
        HOME_SETTINGS,
        VOICE_CHANNEL_STATUS,
        GUILD_SCHEDULED_EVENT_EXCEPTION,
        GUILD_MEMBER_VERIFICATION,
        GUILD_PROFILE,
    }

    /**
     * A single before/after pair. Values are untyped on the wire — a string, a number or a
     * `{name,id}` object depending on [key] — which is why they surface as `Any?`.
     */
    class Change : Model {
        var key: String? = null
            private set
        var oldValue: Any? = null
            private set
        var newValue: Any? = null
            private set

        constructor() {
            // Parsed field by field through assignField.
        }

        constructor(key: String?, oldValue: Any?, newValue: Any?) {
            this.key = key
            this.oldValue = oldValue
            this.newValue = newValue
        }

        /** The value to render: the new one, or the old one for deletions that only carry it. */
        val value: Any? get() = newValue ?: oldValue

        override fun assignField(reader: Model.JsonReader) {
            val values = ValueFactory(reader)
            when (reader.nextName()) {
                "key" -> key = reader.nextString(key)
                "old_value" -> oldValue = reader.parseUnknown(values)
                "new_value" -> newValue = reader.parseUnknown(values)
                else -> reader.skipValue()
            }
        }

        override fun equals(other: Any?): Boolean = other is Change &&
            key == other.key && oldValue == other.oldValue && newValue == other.newValue

        override fun hashCode(): Int = listOf(key, oldValue, newValue).hashCode()

        override fun toString(): String =
            "ModelAuditLogEntry.Change(key=$key, oldValue=$oldValue, newValue=$newValue)"
    }

    /**
     * The object shape a change value takes: `{name, id}` for roles and other named entities, and
     * `{id, type, permission}` for the per-target overrides an application-command permission
     * change carries.
     */
    class ChangeNameId : Model {
        var name: String? = null
            private set
        var id: Long = 0
            private set
        var type: Int = 0
            private set
        var permission: Boolean? = null
            private set

        override fun assignField(reader: Model.JsonReader) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString(name)
                "id" -> id = reader.nextLong(id)
                "type" -> type = reader.nextInt(type)
                "permission" -> permission = reader.nextBooleanOrNull()
                else -> reader.skipValue()
            }
        }

        override fun equals(other: Any?): Boolean = other is ChangeNameId &&
            name == other.name && id == other.id && type == other.type &&
            permission == other.permission

        override fun hashCode(): Int = listOf(name, id, type, permission).hashCode()

        override fun toString(): String =
            "ModelAuditLogEntry.ChangeNameId(name=$name, id=$id, type=$type, permission=$permission)"
    }

    /** Action-specific extras: the pruned member count, the moved-to channel, and so on. */
    class Options : Model {
        var count: Int = 0
            private set
        var channelId: Long = 0
            private set
        var roleName: String? = null
            private set
        var deleteMemberDays: Int = 0
            private set
        var membersRemoved: Int = 0
            private set
        var type: Int = 0
            private set
        var id: Long = 0
            private set
        var applicationId: Long = 0
            private set
        var autoModerationRuleName: String? = null
            private set
        var autoModerationRuleTriggerType: Int = 0
            private set
        var integrationType: String? = null
            private set
        var messageId: Long = 0
            private set
        var status: String? = null
            private set
        var eventExceptionId: Long = 0
            private set

        override fun assignField(reader: Model.JsonReader) {
            when (reader.nextName()) {
                "count" -> count = reader.nextInt(count)
                "channel_id" -> channelId = reader.nextLong(channelId)
                "role_name" -> roleName = reader.nextString(roleName)
                "delete_member_days" -> deleteMemberDays = reader.nextInt(deleteMemberDays)
                "members_removed" -> membersRemoved = reader.nextInt(membersRemoved)
                "type" -> type = reader.nextInt(type)
                "id" -> id = reader.nextLong(id)
                "application_id" -> applicationId = reader.nextLong(applicationId)
                "auto_moderation_rule_name" ->
                    autoModerationRuleName = reader.nextString(autoModerationRuleName)
                "auto_moderation_rule_trigger_type" ->
                    autoModerationRuleTriggerType = reader.nextInt(autoModerationRuleTriggerType)
                "integration_type" -> integrationType = reader.nextString(integrationType)
                "message_id" -> messageId = reader.nextLong(messageId)
                "status" -> status = reader.nextString(status)
                "event_exception_id" -> eventExceptionId = reader.nextLong(eventExceptionId)
                else -> reader.skipValue()
            }
        }

        override fun equals(other: Any?): Boolean = other is Options &&
            count == other.count && channelId == other.channelId && roleName == other.roleName &&
            deleteMemberDays == other.deleteMemberDays && membersRemoved == other.membersRemoved &&
            type == other.type && id == other.id && applicationId == other.applicationId &&
            autoModerationRuleName == other.autoModerationRuleName &&
            autoModerationRuleTriggerType == other.autoModerationRuleTriggerType &&
            integrationType == other.integrationType && messageId == other.messageId &&
            status == other.status && eventExceptionId == other.eventExceptionId

        override fun hashCode(): Int = listOf(
            count, channelId, roleName, deleteMemberDays, membersRemoved, type, id, applicationId,
            autoModerationRuleName, autoModerationRuleTriggerType, integrationType, messageId,
            status, eventExceptionId,
        ).hashCode()

        override fun toString(): String = "ModelAuditLogEntry.Options(count=$count, " +
            "channelId=$channelId, roleName=$roleName, deleteMemberDays=$deleteMemberDays, " +
            "membersRemoved=$membersRemoved, type=$type, id=$id, applicationId=$applicationId, " +
            "autoModerationRuleName=$autoModerationRuleName, " +
            "autoModerationRuleTriggerType=$autoModerationRuleTriggerType, " +
            "integrationType=$integrationType, messageId=$messageId, status=$status, " +
            "eventExceptionId=$eventExceptionId)"
    }

    /**
     * Reads one element of a change value: an object becomes a [ChangeNameId].
     *
     * Anything else is consumed and dropped rather than left in place — an element that is skipped
     * without being read never advances the reader, and the array loop around it would not end.
     */
    class ValueFactory(private val reader: Model.JsonReader) : Model.JsonReader.ItemFactory<Any> {
        override fun get(): Any? = when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> reader.parse(ChangeNameId())
            JsonToken.STRING -> reader.nextStringOrNull()
            JsonToken.NUMBER -> reader.nextLongOrNull()
            JsonToken.BOOLEAN -> reader.nextBooleanOrNull()
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    companion object {
        const val ACTION_ALL = 0
        const val ACTION_GUILD_UPDATE = 1
        const val ACTION_CHANNEL_CREATE = 10
        const val ACTION_CHANNEL_UPDATE = 11
        const val ACTION_CHANNEL_DELETE = 12
        const val ACTION_CHANNEL_OVERWRITE_CREATE = 13
        const val ACTION_CHANNEL_OVERWRITE_UPDATE = 14
        const val ACTION_CHANNEL_OVERWRITE_DELETE = 15
        const val ACTION_MEMBER_KICK = 20
        const val ACTION_MEMBER_PRUNE = 21
        const val ACTION_MEMBER_BAN_ADD = 22
        const val ACTION_MEMBER_BAN_REMOVE = 23
        const val ACTION_MEMBER_UPDATE = 24
        const val ACTION_MEMBER_ROLE_UPDATE = 25
        const val ACTION_MEMBER_MOVE = 26
        const val ACTION_MEMBER_DISCONNECT = 27
        const val ACTION_BOT_ADD = 28
        const val ACTION_ROLE_CREATE = 30
        const val ACTION_ROLE_UPDATE = 31
        const val ACTION_ROLE_DELETE = 32
        const val ACTION_INVITE_CREATE = 40
        const val ACTION_INVITE_UPDATE = 41
        const val ACTION_INVITE_DELETE = 42
        const val ACTION_WEBHOOK_CREATE = 50
        const val ACTION_WEBHOOK_UPDATE = 51
        const val ACTION_WEBHOOK_DELETE = 52
        const val ACTION_EMOJI_CREATE = 60
        const val ACTION_EMOJI_UPDATE = 61
        const val ACTION_EMOJI_DELETE = 62
        const val ACTION_MESSAGE_DELETE = 72
        const val ACTION_MESSAGE_BULK_DELETE = 73
        const val ACTION_MESSAGE_PIN = 74
        const val ACTION_MESSAGE_UNPIN = 75
        const val ACTION_INTEGRATION_CREATE = 80
        const val ACTION_INTEGRATION_UPDATE = 81
        const val ACTION_INTEGRATION_DELETE = 82
        const val ACTION_STAGE_INSTANCE_CREATE = 83
        const val ACTION_STAGE_INSTANCE_UPDATE = 84
        const val ACTION_STAGE_INSTANCE_DELETE = 85
        const val ACTION_STICKER_CREATE = 90
        const val ACTION_STICKER_UPDATE = 91
        const val ACTION_STICKER_DELETE = 92
        const val ACTION_GUILD_SCHEDULED_EVENT_CREATE = 100
        const val ACTION_GUILD_SCHEDULED_EVENT_UPDATE = 101
        const val ACTION_GUILD_SCHEDULED_EVENT_DELETE = 102
        const val ACTION_THREAD_CREATE = 110
        const val ACTION_THREAD_UPDATE = 111
        const val ACTION_THREAD_DELETE = 112
        const val ACTION_APPLICATION_COMMAND_PERMISSION_UPDATE = 121
        const val ACTION_SOUNDBOARD_SOUND_CREATE = 130
        const val ACTION_SOUNDBOARD_SOUND_UPDATE = 131
        const val ACTION_SOUNDBOARD_SOUND_DELETE = 132
        const val ACTION_AUTO_MODERATION_RULE_CREATE = 140
        const val ACTION_AUTO_MODERATION_RULE_UPDATE = 141
        const val ACTION_AUTO_MODERATION_RULE_DELETE = 142
        const val ACTION_AUTO_MODERATION_BLOCK_MESSAGE = 143
        const val ACTION_AUTO_MODERATION_FLAG_TO_CHANNEL = 144
        const val ACTION_AUTO_MODERATION_USER_COMMUNICATION_DISABLED = 145
        const val ACTION_AUTO_MODERATION_QUARANTINE_USER = 146
        const val ACTION_CREATOR_MONETIZATION_REQUEST_CREATED = 150
        const val ACTION_CREATOR_MONETIZATION_TERMS_ACCEPTED = 151
        const val ACTION_ONBOARDING_PROMPT_CREATE = 163
        const val ACTION_ONBOARDING_PROMPT_UPDATE = 164
        const val ACTION_ONBOARDING_PROMPT_DELETE = 165
        const val ACTION_ONBOARDING_CREATE = 166
        const val ACTION_ONBOARDING_UPDATE = 167
        const val ACTION_GUILD_HOME_FEATURE_ITEM = 171
        const val ACTION_GUILD_HOME_REMOVE_ITEM = 172

        /** Retired server-side; kept because [getTargetType] still has to place it. */
        const val ACTION_HARMFUL_LINKS_BLOCKED_MESSAGE = 180
        const val ACTION_HOME_SETTINGS_CREATE = 190
        const val ACTION_HOME_SETTINGS_UPDATE = 191
        const val ACTION_VOICE_CHANNEL_STATUS_CREATE = 192
        const val ACTION_VOICE_CHANNEL_STATUS_DELETE = 193
        const val ACTION_GUILD_SCHEDULED_EVENT_EXCEPTION_CREATE = 200
        const val ACTION_GUILD_SCHEDULED_EVENT_EXCEPTION_UPDATE = 201
        const val ACTION_GUILD_SCHEDULED_EVENT_EXCEPTION_DELETE = 202
        const val ACTION_GUILD_MEMBER_VERIFICATION_UPDATE = 210
        const val ACTION_GUILD_PROFILE_UPDATE = 211
        const val ACTION_GUILD_MIGRATE_PIN_PERMISSION = 212
        const val ACTION_GUILD_MIGRATE_BYPASS_SLOWMODE_PERMISSION = 213

        const val CHANGE_KEY_AFK_CHANNEL_ID = "afk_channel_id"
        const val CHANGE_KEY_AFK_TIMEOUT = "afk_timeout"
        const val CHANGE_KEY_APPLICATION_ID = "application_id"
        const val CHANGE_KEY_ARCHIVED = "archived"
        const val CHANGE_KEY_ASSET = "asset"
        const val CHANGE_KEY_AUTO_ARCHIVE_DURATION = "auto_archive_duration"
        const val CHANGE_KEY_AVAILABLE = "available"
        const val CHANGE_KEY_AVATAR_HASH = "avatar_hash"
        const val CHANGE_KEY_BANNER_HASH = "banner_hash"
        const val CHANGE_KEY_BITRATE = "bitrate"
        const val CHANGE_KEY_CHANNEL_ID = "channel_id"
        const val CHANGE_KEY_CODE = "code"
        const val CHANGE_KEY_COLOR = "color"
        const val CHANGE_KEY_DEAF = "deaf"
        const val CHANGE_KEY_DEFAULT_AUTO_ARCHIVE_DURATION = "default_auto_archive_duration"
        const val CHANGE_KEY_DEFAULT_MESSAGE_NOTIFICATIONS = "default_message_notifications"
        const val CHANGE_KEY_DESCRIPTION = "description"
        const val CHANGE_KEY_DISCOVERY_SPLASH_HASH = "discovery_splash_hash"
        const val CHANGE_KEY_ENABLE_EMOTICONS = "enable_emoticons"
        const val CHANGE_KEY_ENTITY_TYPE = "entity_type"
        const val CHANGE_KEY_EXPIRE_BEHAVIOR = "expire_behavior"
        const val CHANGE_KEY_EXPIRE_GRACE_PERIOD = "expire_grace_period"
        const val CHANGE_KEY_EXPLICIT_CONTENT_FILTER = "explicit_content_filter"
        const val CHANGE_KEY_FORMAT_TYPE = "format_type"
        const val CHANGE_KEY_GUILD_COMMUNICATION_DISABLED = "communication_disabled_until"
        const val CHANGE_KEY_GUILD_ID = "guild_id"
        const val CHANGE_KEY_HOIST = "hoist"
        const val CHANGE_KEY_ICON_HASH = "icon_hash"
        const val CHANGE_KEY_ID = "id"
        const val CHANGE_KEY_IMAGE_HASH = "image_hash"
        const val CHANGE_KEY_INVITER_ID = "inviter_id"
        const val CHANGE_KEY_LOCATION = "location"
        const val CHANGE_KEY_LOCKED = "locked"
        const val CHANGE_KEY_MAX_AGE = "max_age"
        const val CHANGE_KEY_MAX_USES = "max_uses"
        const val CHANGE_KEY_MENTIONABLE = "mentionable"
        const val CHANGE_KEY_MFA_LEVEL = "mfa_level"
        const val CHANGE_KEY_MUTE = "mute"
        const val CHANGE_KEY_NAME = "name"
        const val CHANGE_KEY_NICK = "nick"
        const val CHANGE_KEY_NSFW = "nsfw"
        const val CHANGE_KEY_OWNER_ID = "owner_id"
        const val CHANGE_KEY_PERMISSION_OVERWRITES = "permission_overwrites"
        const val CHANGE_KEY_PERMISSIONS = "permissions"
        const val CHANGE_KEY_PERMISSIONS_DENIED = "deny"
        const val CHANGE_KEY_PERMISSIONS_GRANTED = "allow"
        const val CHANGE_KEY_PERMISSIONS_RESET = "reset"
        const val CHANGE_KEY_POSITION = "position"
        const val CHANGE_KEY_PREFERRED_LOCALE = "preferred_locale"
        const val CHANGE_KEY_PREMIUM_PROGRESS_BAR_ENABLED = "premium_progress_bar_enabled"
        const val CHANGE_KEY_PRIVACY_LEVEL = "privacy_level"
        const val CHANGE_KEY_PRUNE_DELETE_DAYS = "prune_delete_days"
        const val CHANGE_KEY_RATE_LIMIT_PER_USER = "rate_limit_per_user"
        const val CHANGE_KEY_REASON = "reason"
        const val CHANGE_KEY_REGION = "region"
        const val CHANGE_KEY_REGION_OVERRIDE = "rtc_region"
        const val CHANGE_KEY_ROLES_ADD = "\$add"
        const val CHANGE_KEY_ROLES_REMOVE = "\$remove"
        const val CHANGE_KEY_RULES_CHANNEL_ID = "rules_channel_id"
        const val CHANGE_KEY_SPLASH_HASH = "splash_hash"
        const val CHANGE_KEY_STATUS = "status"
        const val CHANGE_KEY_SYSTEM_CHANNEL_FLAGS = "system_channel_flags"
        const val CHANGE_KEY_SYSTEM_CHANNEL_ID = "system_channel_id"
        const val CHANGE_KEY_TAGS = "tags"
        const val CHANGE_KEY_TEMPORARY = "temporary"
        const val CHANGE_KEY_TOPIC = "topic"
        const val CHANGE_KEY_TYPE = "type"
        const val CHANGE_KEY_UNICODE_EMOJI = "unicode_emoji"
        const val CHANGE_KEY_UPDATES_CHANNEL_ID = "public_updates_channel_id"
        const val CHANGE_KEY_USES = "uses"
        const val CHANGE_KEY_VANITY_URL_CODE = "vanity_url_code"
        const val CHANGE_KEY_VERIFICATION_LEVEL = "verification_level"
        const val CHANGE_KEY_VIDEO_QUALITY_MODE = "video_quality_mode"
        const val CHANGE_KEY_WIDGET_CHANNEL_ID = "widget_channel_id"
        const val CHANGE_KEY_WIDGET_ENABLED = "widget_enabled"

        @JvmStatic
        fun getActionType(actionTypeId: Int): ActionType = when (actionTypeId) {
            ACTION_CHANNEL_CREATE,
            ACTION_CHANNEL_OVERWRITE_CREATE,
            ACTION_MEMBER_BAN_REMOVE,
            ACTION_BOT_ADD,
            ACTION_ROLE_CREATE,
            ACTION_INVITE_CREATE,
            ACTION_WEBHOOK_CREATE,
            ACTION_EMOJI_CREATE,
            ACTION_MESSAGE_PIN,
            ACTION_INTEGRATION_CREATE,
            ACTION_STAGE_INSTANCE_CREATE,
            ACTION_STICKER_CREATE,
            ACTION_GUILD_SCHEDULED_EVENT_CREATE,
            ACTION_THREAD_CREATE,
            ACTION_AUTO_MODERATION_RULE_CREATE,
            ACTION_CREATOR_MONETIZATION_REQUEST_CREATED,
            ACTION_ONBOARDING_PROMPT_CREATE,
            ACTION_ONBOARDING_CREATE,
            ACTION_GUILD_HOME_FEATURE_ITEM,
            ACTION_SOUNDBOARD_SOUND_CREATE,
            ACTION_HOME_SETTINGS_CREATE,
            ACTION_VOICE_CHANNEL_STATUS_CREATE,
            -> ActionType.CREATE

            ACTION_GUILD_UPDATE,
            ACTION_CHANNEL_UPDATE,
            ACTION_CHANNEL_OVERWRITE_UPDATE,
            ACTION_MEMBER_UPDATE,
            ACTION_MEMBER_ROLE_UPDATE,
            ACTION_MEMBER_MOVE,
            ACTION_ROLE_UPDATE,
            ACTION_INVITE_UPDATE,
            ACTION_WEBHOOK_UPDATE,
            ACTION_EMOJI_UPDATE,
            ACTION_INTEGRATION_UPDATE,
            ACTION_STAGE_INSTANCE_UPDATE,
            ACTION_STICKER_UPDATE,
            ACTION_GUILD_SCHEDULED_EVENT_UPDATE,
            ACTION_THREAD_UPDATE,
            // A recurrence exception is an edit to an existing event even when it is "created".
            ACTION_GUILD_SCHEDULED_EVENT_EXCEPTION_CREATE,
            ACTION_GUILD_SCHEDULED_EVENT_EXCEPTION_UPDATE,
            ACTION_APPLICATION_COMMAND_PERMISSION_UPDATE,
            ACTION_CREATOR_MONETIZATION_TERMS_ACCEPTED,
            ACTION_AUTO_MODERATION_RULE_UPDATE,
            ACTION_AUTO_MODERATION_FLAG_TO_CHANNEL,
            ACTION_AUTO_MODERATION_USER_COMMUNICATION_DISABLED,
            ACTION_AUTO_MODERATION_QUARANTINE_USER,
            ACTION_ONBOARDING_PROMPT_UPDATE,
            ACTION_ONBOARDING_UPDATE,
            ACTION_SOUNDBOARD_SOUND_UPDATE,
            ACTION_HOME_SETTINGS_UPDATE,
            ACTION_GUILD_MEMBER_VERIFICATION_UPDATE,
            ACTION_GUILD_PROFILE_UPDATE,
            ACTION_GUILD_MIGRATE_PIN_PERMISSION,
            ACTION_GUILD_MIGRATE_BYPASS_SLOWMODE_PERMISSION,
            -> ActionType.UPDATE

            ACTION_CHANNEL_DELETE,
            ACTION_CHANNEL_OVERWRITE_DELETE,
            ACTION_MEMBER_KICK,
            ACTION_MEMBER_PRUNE,
            ACTION_MEMBER_BAN_ADD,
            ACTION_MEMBER_DISCONNECT,
            ACTION_ROLE_DELETE,
            ACTION_INVITE_DELETE,
            ACTION_WEBHOOK_DELETE,
            ACTION_EMOJI_DELETE,
            ACTION_MESSAGE_DELETE,
            ACTION_MESSAGE_BULK_DELETE,
            ACTION_MESSAGE_UNPIN,
            ACTION_INTEGRATION_DELETE,
            ACTION_STAGE_INSTANCE_DELETE,
            ACTION_STICKER_DELETE,
            ACTION_GUILD_SCHEDULED_EVENT_DELETE,
            ACTION_GUILD_SCHEDULED_EVENT_EXCEPTION_DELETE,
            ACTION_THREAD_DELETE,
            ACTION_AUTO_MODERATION_BLOCK_MESSAGE,
            ACTION_AUTO_MODERATION_RULE_DELETE,
            ACTION_ONBOARDING_PROMPT_DELETE,
            ACTION_GUILD_HOME_REMOVE_ITEM,
            ACTION_SOUNDBOARD_SOUND_DELETE,
            ACTION_VOICE_CHANNEL_STATUS_DELETE,
            -> ActionType.DELETE

            else -> ActionType.ALL
        }

        /**
         * The kind of object [actionTypeId] acts on.
         *
         * Written as ordered ranges rather than an exhaustive list of ids, the way the reference
         * has it: action numbers are allocated in blocks, so an id this build has never heard of
         * still lands on the right target as long as the server keeps to the block it belongs to.
         * The order of the branches is the table — several of them overlap, and the earlier one
         * wins. Message delete/pin/unpin target the *author*, while bulk delete targets the
         * channel, which is why those four sit outside their own block.
         */
        @JvmStatic
        fun getTargetType(actionTypeId: Int): TargetType = when {
            actionTypeId == ACTION_ALL -> TargetType.ALL
            actionTypeId <= ACTION_GUILD_UPDATE -> TargetType.GUILD
            actionTypeId <= ACTION_CHANNEL_DELETE ||
                actionTypeId == ACTION_MESSAGE_BULK_DELETE -> TargetType.CHANNEL
            actionTypeId <= ACTION_CHANNEL_OVERWRITE_DELETE -> TargetType.CHANNEL_OVERWRITE
            actionTypeId <= ACTION_BOT_ADD ||
                actionTypeId == ACTION_MESSAGE_DELETE ||
                actionTypeId == ACTION_MESSAGE_PIN ||
                actionTypeId == ACTION_MESSAGE_UNPIN -> TargetType.USER
            actionTypeId <= ACTION_ROLE_DELETE -> TargetType.ROLE
            actionTypeId <= ACTION_INVITE_DELETE -> TargetType.INVITE
            actionTypeId <= ACTION_WEBHOOK_DELETE -> TargetType.WEBHOOK
            actionTypeId <= ACTION_EMOJI_DELETE -> TargetType.EMOJI
            actionTypeId <= ACTION_INTEGRATION_DELETE -> TargetType.INTEGRATION
            actionTypeId <= ACTION_STAGE_INSTANCE_DELETE -> TargetType.STAGE_INSTANCE
            actionTypeId <= ACTION_STICKER_DELETE -> TargetType.STICKER
            actionTypeId <= ACTION_GUILD_SCHEDULED_EVENT_DELETE -> TargetType.GUILD_SCHEDULED_EVENT
            actionTypeId <= ACTION_THREAD_DELETE -> TargetType.THREAD
            actionTypeId == ACTION_APPLICATION_COMMAND_PERMISSION_UPDATE ->
                TargetType.APPLICATION_COMMAND
            actionTypeId <= ACTION_SOUNDBOARD_SOUND_DELETE -> TargetType.GUILD_SOUNDBOARD
            actionTypeId < ACTION_AUTO_MODERATION_BLOCK_MESSAGE -> TargetType.AUTO_MODERATION_RULE
            // The AutoMod *enforcement* actions name the member they were applied to.
            actionTypeId <= ACTION_AUTO_MODERATION_QUARANTINE_USER -> TargetType.USER
            actionTypeId <= ACTION_CREATOR_MONETIZATION_TERMS_ACCEPTED -> TargetType.GUILD
            actionTypeId <= ACTION_ONBOARDING_PROMPT_DELETE -> TargetType.ONBOARDING_PROMPT
            actionTypeId <= ACTION_ONBOARDING_UPDATE -> TargetType.GUILD_ONBOARDING
            actionTypeId <= ACTION_GUILD_HOME_REMOVE_ITEM -> TargetType.GUILD_HOME
            actionTypeId <= ACTION_HARMFUL_LINKS_BLOCKED_MESSAGE -> TargetType.GUILD
            actionTypeId <= ACTION_HOME_SETTINGS_UPDATE -> TargetType.HOME_SETTINGS
            actionTypeId <= ACTION_VOICE_CHANNEL_STATUS_DELETE -> TargetType.VOICE_CHANNEL_STATUS
            actionTypeId <= ACTION_GUILD_SCHEDULED_EVENT_EXCEPTION_DELETE ->
                TargetType.GUILD_SCHEDULED_EVENT_EXCEPTION
            actionTypeId <= ACTION_GUILD_MEMBER_VERIFICATION_UPDATE ->
                TargetType.GUILD_MEMBER_VERIFICATION
            actionTypeId <= ACTION_GUILD_PROFILE_UPDATE -> TargetType.GUILD_PROFILE
            actionTypeId <= ACTION_GUILD_MIGRATE_BYPASS_SLOWMODE_PERMISSION -> TargetType.GUILD
            else -> TargetType.UNKNOWN
        }
    }
}
