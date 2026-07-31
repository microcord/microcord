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

    /** The `{name, id}` shape a change value takes for roles and other named entities. */
    class ChangeNameId : Model {
        var name: String? = null
            private set
        var id: Long = 0
            private set

        override fun assignField(reader: Model.JsonReader) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString(name)
                "id" -> id = reader.nextLong(id)
                else -> reader.skipValue()
            }
        }

        override fun equals(other: Any?): Boolean = other is ChangeNameId &&
            name == other.name && id == other.id

        override fun hashCode(): Int = 31 * (name?.hashCode() ?: 0) + id.hashCode()

        override fun toString(): String = "ModelAuditLogEntry.ChangeNameId(name=$name, id=$id)"
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

        override fun assignField(reader: Model.JsonReader) {
            when (reader.nextName()) {
                "count" -> count = reader.nextInt(count)
                "channel_id" -> channelId = reader.nextLong(channelId)
                "role_name" -> roleName = reader.nextString(roleName)
                "delete_member_days" -> deleteMemberDays = reader.nextInt(deleteMemberDays)
                "members_removed" -> membersRemoved = reader.nextInt(membersRemoved)
                "type" -> type = reader.nextInt(type)
                "id" -> id = reader.nextLong(id)
                else -> reader.skipValue()
            }
        }

        override fun equals(other: Any?): Boolean = other is Options &&
            count == other.count && channelId == other.channelId && roleName == other.roleName &&
            deleteMemberDays == other.deleteMemberDays && membersRemoved == other.membersRemoved &&
            type == other.type && id == other.id

        override fun hashCode(): Int = listOf(
            count, channelId, roleName, deleteMemberDays, membersRemoved, type, id,
        ).hashCode()

        override fun toString(): String = "ModelAuditLogEntry.Options(count=$count, " +
            "channelId=$channelId, roleName=$roleName, deleteMemberDays=$deleteMemberDays, " +
            "membersRemoved=$membersRemoved, type=$type, id=$id)"
    }

    /** Reads one element of a change value: an object becomes a [ChangeNameId]. */
    class ValueFactory(private val reader: Model.JsonReader) : Model.JsonReader.ItemFactory<Any> {
        override fun get(): Any? = when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> reader.parse(ChangeNameId())
            JsonToken.STRING -> reader.nextStringOrNull()
            JsonToken.NUMBER -> reader.nextLongOrNull()
            else -> null
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
            ACTION_THREAD_DELETE,
            -> ActionType.DELETE

            else -> ActionType.ALL
        }

        @JvmStatic
        fun getTargetType(actionTypeId: Int): TargetType = when (actionTypeId) {
            ACTION_ALL -> TargetType.ALL
            ACTION_GUILD_UPDATE -> TargetType.GUILD
            ACTION_CHANNEL_CREATE,
            ACTION_CHANNEL_UPDATE,
            ACTION_CHANNEL_DELETE,
            ACTION_MESSAGE_BULK_DELETE,
            -> TargetType.CHANNEL
            ACTION_CHANNEL_OVERWRITE_CREATE,
            ACTION_CHANNEL_OVERWRITE_UPDATE,
            ACTION_CHANNEL_OVERWRITE_DELETE,
            -> TargetType.CHANNEL_OVERWRITE
            ACTION_MEMBER_KICK,
            ACTION_MEMBER_PRUNE,
            ACTION_MEMBER_BAN_ADD,
            ACTION_MEMBER_BAN_REMOVE,
            ACTION_MEMBER_UPDATE,
            ACTION_MEMBER_ROLE_UPDATE,
            ACTION_MEMBER_MOVE,
            ACTION_MEMBER_DISCONNECT,
            ACTION_BOT_ADD,
            ACTION_MESSAGE_DELETE,
            ACTION_MESSAGE_PIN,
            ACTION_MESSAGE_UNPIN,
            -> TargetType.USER
            ACTION_ROLE_CREATE, ACTION_ROLE_UPDATE, ACTION_ROLE_DELETE -> TargetType.ROLE
            ACTION_INVITE_CREATE, ACTION_INVITE_UPDATE, ACTION_INVITE_DELETE -> TargetType.INVITE
            ACTION_WEBHOOK_CREATE, ACTION_WEBHOOK_UPDATE, ACTION_WEBHOOK_DELETE -> TargetType.WEBHOOK
            ACTION_EMOJI_CREATE, ACTION_EMOJI_UPDATE, ACTION_EMOJI_DELETE -> TargetType.EMOJI
            ACTION_INTEGRATION_CREATE,
            ACTION_INTEGRATION_UPDATE,
            ACTION_INTEGRATION_DELETE,
            -> TargetType.INTEGRATION
            ACTION_STAGE_INSTANCE_CREATE,
            ACTION_STAGE_INSTANCE_UPDATE,
            ACTION_STAGE_INSTANCE_DELETE,
            -> TargetType.STAGE_INSTANCE
            ACTION_STICKER_CREATE, ACTION_STICKER_UPDATE, ACTION_STICKER_DELETE -> TargetType.STICKER
            ACTION_GUILD_SCHEDULED_EVENT_CREATE,
            ACTION_GUILD_SCHEDULED_EVENT_UPDATE,
            ACTION_GUILD_SCHEDULED_EVENT_DELETE,
            -> TargetType.GUILD_SCHEDULED_EVENT
            ACTION_THREAD_CREATE, ACTION_THREAD_UPDATE, ACTION_THREAD_DELETE -> TargetType.THREAD
            else -> TargetType.UNKNOWN
        }
    }
}
