package com.discord.primitives

/**
 * Domain aliases for the raw ids that flow through the whole client.
 *
 * Every one of these erases to its underlying primitive, so they are free at runtime and can be
 * introduced into an existing signature without changing behaviour or binary layout. They exist for
 * exactly one reason: a function taking four `Long`s is impossible to call correctly by reading its
 * signature, and Discord's ids are almost always passed in groups.
 *
 * Kotlin metadata in the reference proves the original declared this set. Being aliases rather than
 * value classes, the compiler will not stop `channelId` being passed where a `GuildId` is wanted —
 * they document intent, they do not enforce it.
 */

/** A Discord snowflake: a 64-bit id whose high bits encode its creation time. */
typealias Snowflake = Long

typealias UserId = Long

typealias GuildId = Long

typealias ChannelId = Long

typealias MessageId = Long

typealias ApplicationId = Long

typealias RoleId = Long

typealias StickerId = Long

typealias StickerPackId = Long

/** Store SKU id. Distinct from the payment gateway's own sku string. */
typealias SkuId = Long

/** Subscription plan id. */
typealias PlanId = Long

/** Identifier of a `Channel.ForumTag` within a forum channel. */
typealias ForumTagId = Long

typealias GuildBoostSlotId = Long

typealias GuildScheduledEventId = Long

/** Gateway session identifier — a server-issued opaque string, not a snowflake. */
typealias SessionId = String

/**
 * Identifies one live stream, e.g. `guild:<guildId>:<channelId>:<userId>`.
 *
 * A structured string rather than an id: it is parsed, not looked up.
 */
typealias StreamKey = String
