@file:JvmName("PermissionOverwriteUtils")

package com.discord.utilities

import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildTextChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionBit
import com.discord.api.permission.PermissionOverwrite

/**
 * Bit tests and edit arithmetic for a single channel [PermissionOverwrite].
 *
 * Reference: `com.discord.utilities.PermissionOverwriteUtilsKt`. These are deliberately *not* in
 * `utilities/permissions/PermissionUtils` — the reference keeps them at the `utilities` root
 * because they inspect one overwrite in isolation rather than computing an effective permission.
 *
 * Note the tests are `!= 0`, not `== permission`: a mask matches when *any* of its bits are set.
 */

/** True when this overwrite explicitly allows any bit in [permission]. */
fun PermissionOverwrite.allows(permission: PermissionBit): Boolean =
    (permission and allow) != 0L

/** True when this overwrite explicitly denies any bit in [permission]. */
fun PermissionOverwrite.denies(permission: PermissionBit): Boolean =
    (permission and deny) != 0L

/**
 * The `(allow, deny)` pair this overwrite should be updated to when [bit] is toggled.
 *
 * Setting a bit on one side always clears it on the other, so the tri-state editor can never
 * produce an overwrite that both allows and denies the same permission. A `null` receiver means
 * "no overwrite exists yet" and starts from `(0, 0)`.
 */
fun PermissionOverwrite?.computeAllowDenyUpdateBits(
    bit: PermissionBit,
    isAllowed: Boolean,
): Pair<Long, Long> {
    val allow = this?.allow ?: 0L
    val deny = this?.deny ?: 0L
    return if (isAllowed) {
        (allow or bit) to (bit.inv() and deny)
    } else {
        (allow and bit.inv()) to (bit or deny)
    }
}

/**
 * True when this overwrite hides [channel] from whoever it applies to.
 *
 * A vocal channel is also hidden by denying `CONNECT`, because the channel list treats
 * "cannot connect" as "cannot see".
 */
fun PermissionOverwrite.deniesAccessTo(channel: Channel): Boolean =
    denies(Permission.VIEW_CHANNEL) ||
        (channel.isGuildVocalChannel() && denies(Permission.CONNECT))

/**
 * True when this overwrite is what *grants* access to [channel].
 *
 * Used by the private-channel member list to work out which roles and members were explicitly
 * added. A deny always wins, and the grant has to be the one that matters for the channel's kind:
 * `VIEW_CHANNEL` for text, `CONNECT` for voice.
 */
fun PermissionOverwrite.grantsAccessTo(channel: Channel): Boolean =
    !deniesAccessTo(channel) &&
        (
            (channel.isGuildTextChannel() && allows(Permission.VIEW_CHANNEL)) ||
                (channel.isGuildVocalChannel() && allows(Permission.CONNECT))
            )
