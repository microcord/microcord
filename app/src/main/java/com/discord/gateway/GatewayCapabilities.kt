package com.discord.gateway

/**
 * Per-connection feature flags sent with IDENTIFY.
 *
 * Each one opts into a payload or dispatch change, so a bit may only be set once the client
 * actually handles what it asks for — the gateway reshapes what it sends and never announces it.
 * Only the bits this client enables are named; the rest exist but would change events nothing here
 * reads.
 */
object GatewayCapabilities {

    /** Drops `notes` from READY. */
    const val LAZY_USER_NOTES = 1L shl 0

    /** Stops member/presence syncing for implicit relationships. */
    const val NO_AFFINE_USER_IDS = 1L shl 1

    /** READY's `read_state` becomes `{version, partial, entries}`. */
    const val VERSIONED_READ_STATES = 1L shl 2

    /** READY's `user_guild_settings` becomes `{version, partial, entries}`. */
    const val VERSIONED_USER_GUILD_SETTINGS = 1L shl 3

    /** Users are hoisted into READY's `users`, referenced elsewhere by id, members merged. */
    const val DEDUPE_USER_OBJECTS = 1L shl 4

    /** Splits READY, with the rest following in READY_SUPPLEMENTAL. Ignored on its own. */
    const val PRIORITIZED_READY_PAYLOAD = (1L shl 5) or DEDUPE_USER_OBJECTS

    /** `guild_experiments` populations become an array. */
    const val MULTIPLE_GUILD_EXPERIMENT_POPULATIONS = 1L shl 6

    /** Includes read states for non-channel resources, such as guild scheduled events. */
    const val NON_CHANNEL_READ_STATES = 1L shl 7

    /** Allows READY to carry a replacement `auth_token`. */
    const val AUTH_TOKEN_REFRESH = 1L shl 8

    /** Drops `user_settings` from READY and stops USER_SETTINGS_UPDATE; the proto replaces both. */
    const val NO_LEGACY_USER_SETTINGS = 1L shl 9

    /** Versioned guild data: `data_mode`, `partial_updates` and per-guild `version`. */
    const val USE_GUILD_VERSIONING = 1L shl 10

    /** Connects to DM and group DM calls already running when the session starts. */
    const val PASSIVE_CALLS_V1 = 1L shl 12

    /** MESSAGE_REACTION_ADD_MANY in place of a burst of MESSAGE_REACTION_ADD. */
    const val REACTION_BATCHING = 1L shl 13

    /** Periodic PASSIVE_UPDATE_V2 carrying unread and voice state for unsubscribed guilds. */
    const val PASSIVE_UPDATE_V2 = 1L shl 14

    /** MESSAGE_POLL_VOTE_ADD_MANY in place of a burst of MESSAGE_POLL_VOTE_ADD. */
    const val POLL_VOTE_BATCHING = 1L shl 19

    /**
     * VOICE_STATE_UPDATE_BATCH in place of a burst of VOICE_STATE_UPDATE. Bit 18 is the withdrawn
     * first version and must stay clear.
     */
    const val VOICE_STATE_BATCHING_V2 = 1L shl 20

    val ENABLED: Long = LAZY_USER_NOTES or
        NO_AFFINE_USER_IDS or
        VERSIONED_READ_STATES or
        VERSIONED_USER_GUILD_SETTINGS or
        DEDUPE_USER_OBJECTS or
        PRIORITIZED_READY_PAYLOAD or
        MULTIPLE_GUILD_EXPERIMENT_POPULATIONS or
        NON_CHANNEL_READ_STATES or
        AUTH_TOKEN_REFRESH or
        NO_LEGACY_USER_SETTINGS or
        USE_GUILD_VERSIONING or
        PASSIVE_CALLS_V1 or
        REACTION_BATCHING or
        PASSIVE_UPDATE_V2 or
        POLL_VOTE_BATCHING or
        VOICE_STATE_BATCHING_V2
}
