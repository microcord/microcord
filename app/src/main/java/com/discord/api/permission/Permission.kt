package com.discord.api.permission

/**
 * A single Discord permission bit, or a bitmask of several of them.
 *
 * The reference declares this as `typealias PermissionBit = Long` in `Permission.kt`;
 * it exists purely for readability at call sites.
 */
typealias PermissionBit = Long

/**
 * Every permission bit understood by the 126.21 client, plus the composite masks it
 * derives from them.
 *
 * Bit values are load bearing: a wrong bit silently grants or denies access. Every
 * value here was decomposed against the reference `com.discord.api.permission.Permission`
 * and each composite mask below is exactly the OR of the individual bits named in its
 * documentation.
 */
object Permission {
    const val NONE: PermissionBit = 0L

    const val CREATE_INSTANT_INVITE: PermissionBit = 1L
    const val KICK_MEMBERS: PermissionBit = 2L
    const val BAN_MEMBERS: PermissionBit = 4L
    const val ADMINISTRATOR: PermissionBit = 8L
    const val MANAGE_CHANNELS: PermissionBit = 16L
    const val MANAGE_GUILD: PermissionBit = 32L
    const val ADD_REACTIONS: PermissionBit = 64L
    const val VIEW_AUDIT_LOG: PermissionBit = 128L
    const val PRIORITY_SPEAKER: PermissionBit = 256L
    const val STREAM: PermissionBit = 512L
    const val VIEW_CHANNEL: PermissionBit = 1024L
    const val SEND_MESSAGES: PermissionBit = 2048L
    const val SEND_TTS_MESSAGES: PermissionBit = 4096L
    const val MANAGE_MESSAGES: PermissionBit = 8192L
    const val EMBED_LINKS: PermissionBit = 16384L
    const val ATTACH_FILES: PermissionBit = 32768L
    const val READ_MESSAGE_HISTORY: PermissionBit = 65536L
    const val MENTION_EVERYONE: PermissionBit = 131072L
    const val USE_EXTERNAL_EMOJIS: PermissionBit = 262144L
    const val VIEW_GUILD_ANALYTICS: PermissionBit = 524288L
    const val CONNECT: PermissionBit = 1048576L
    const val SPEAK: PermissionBit = 2097152L
    const val MUTE_MEMBERS: PermissionBit = 4194304L
    const val DEAFEN_MEMBERS: PermissionBit = 8388608L
    const val MOVE_MEMBERS: PermissionBit = 16777216L
    const val USE_VAD: PermissionBit = 33554432L
    const val CHANGE_NICKNAME: PermissionBit = 67108864L
    const val MANAGE_NICKNAMES: PermissionBit = 134217728L
    const val MANAGE_ROLES: PermissionBit = 268435456L
    const val MANAGE_WEBHOOKS: PermissionBit = 536870912L
    const val MANAGE_EMOJIS_AND_STICKERS: PermissionBit = 1073741824L
    const val USE_APPLICATION_COMMANDS: PermissionBit = 2147483648L
    const val REQUEST_TO_SPEAK: PermissionBit = 4294967296L
    const val MANAGE_EVENTS: PermissionBit = 8589934592L
    const val MANAGE_THREADS: PermissionBit = 17179869184L
    const val CREATE_PUBLIC_THREADS: PermissionBit = 34359738368L
    const val CREATE_PRIVATE_THREADS: PermissionBit = 68719476736L
    const val USE_EXTERNAL_STICKERS: PermissionBit = 137438953472L
    const val SEND_MESSAGES_IN_THREADS: PermissionBit = 274877906944L
    const val USE_EMBEDDED_ACTIVITIES: PermissionBit = 549755813888L
    const val MODERATE_MEMBERS: PermissionBit = 1099511627776L
    const val SEND_POLLS: PermissionBit = 562949953421312L
    const val BYPASS_SLOWMODE: PermissionBit = 4503599627370496L

    /**
     * The permissions granted to a freshly created @everyone role.
     *
     * `CREATE_INSTANT_INVITE | ADD_REACTIONS | STREAM | VIEW_CHANNEL | SEND_MESSAGES |
     * SEND_TTS_MESSAGES | EMBED_LINKS | ATTACH_FILES | READ_MESSAGE_HISTORY |
     * MENTION_EVERYONE | USE_EXTERNAL_EMOJIS | CONNECT | SPEAK | USE_VAD |
     * CHANGE_NICKNAME | USE_APPLICATION_COMMANDS | REQUEST_TO_SPEAK |
     * CREATE_PUBLIC_THREADS | CREATE_PRIVATE_THREADS | USE_EXTERNAL_STICKERS |
     * SEND_MESSAGES_IN_THREADS`
     */
    const val DEFAULT: PermissionBit = 521942851137L

    /** Every bit above. Granted to guild owners and to administrators. */
    const val ALL: PermissionBit = 5068748603522815L

    /**
     * Permissions that require an elevated (MFA-verified) session when the guild has
     * `mfa_level != 0`.
     *
     * `KICK_MEMBERS | BAN_MEMBERS | ADMINISTRATOR | MANAGE_CHANNELS | MANAGE_GUILD |
     * MANAGE_MESSAGES | MANAGE_ROLES | MANAGE_EVENTS | MANAGE_THREADS | MODERATE_MEMBERS`
     */
    const val ELEVATED: PermissionBit = 1125549875262L

    /**
     * Permissions that surface the "server settings" affordances.
     *
     * `KICK_MEMBERS | BAN_MEMBERS | ADMINISTRATOR | MANAGE_CHANNELS | MANAGE_GUILD |
     * MUTE_MEMBERS | DEAFEN_MEMBERS | MANAGE_NICKNAMES | MANAGE_ROLES | MANAGE_WEBHOOKS |
     * MANAGE_EMOJIS_AND_STICKERS | MANAGE_EVENTS | MODERATE_MEMBERS`
     */
    const val MANAGEMENT_PERMISSIONS: PermissionBit = 1110127411262L

    /**
     * Permissions that identify a moderator for reporting/safety surfaces.
     *
     * `KICK_MEMBERS | BAN_MEMBERS | ADMINISTRATOR | MANAGE_CHANNELS | MANAGE_GUILD |
     * MANAGE_MESSAGES | MENTION_EVERYONE | MANAGE_ROLES | MANAGE_THREADS | MODERATE_MEMBERS`
     */
    const val MODERATOR_PERMISSIONS: PermissionBit = 1116960071742L

    /** `MANAGE_CHANNELS | MUTE_MEMBERS | MOVE_MEMBERS` */
    const val MODERATE_STAGE_CHANNEL: PermissionBit = 20971536L

    /** `MODERATE_STAGE_CHANNEL | MANAGE_EVENTS` */
    const val START_STAGE_EVENT: PermissionBit = 8610906128L

    /** `VIEW_CHANNEL | CONNECT | MANAGE_EVENTS` */
    const val START_VOICE_EVENT: PermissionBit = 8590984192L

    /**
     * Masks applied on top of a computed permission set when the member is not a full
     * member of the guild yet, or is timed out.
     */
    object AllowList {
        /** `VIEW_CHANNEL | READ_MESSAGE_HISTORY` */
        const val COMMUNICATIONS_DISABLED_DEFAULT: PermissionBit = 66560L

        /** `VIEW_CHANNEL | READ_MESSAGE_HISTORY` */
        const val LURKER_DEFAULT: PermissionBit = 66560L

        /**
         * `VIEW_CHANNEL | READ_MESSAGE_HISTORY | CONNECT | SPEAK | USE_VAD |
         * REQUEST_TO_SPEAK`
         */
        const val LURKER_STAGE_CHANNEL: PermissionBit = 4331734016L
    }
}
