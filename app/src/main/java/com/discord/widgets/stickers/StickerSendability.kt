package com.discord.widgets.stickers

import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.permission.Permission
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerType
import com.discord.models.user.User
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.user.UserUtils

/**
 * Whether a sticker can be sent into a channel, and if not, what is standing in the way.
 *
 * Ordinal order is the reference's (`SENDABLE`, `SENDABLE_WITH_PREMIUM`,
 * `SENDABLE_WITH_PREMIUM_GUILD`, `NONSENDABLE`) — the Vineflower decompile prints the constants
 * alphabetically, which is misleading; the `$VALUES` array is what was checked.
 */
enum class StickerSendability {
    /** Tapping it sends it. */
    SENDABLE,

    /** Tapping it opens the Nitro upsell. */
    SENDABLE_WITH_PREMIUM,

    /** A guild sticker whose server lost the boost level that unlocked it. Inert. */
    SENDABLE_WITH_PREMIUM_GUILD,

    /** Not usable here at all — the picker greys it out. */
    NONSENDABLE,
}

/**
 * Port of the reference's `StickerUtils.getStickerSendability`.
 *
 * Belongs in `utilities/stickers/StickerUtils`; it is here only because that file is concurrently
 * owned by the sticker-animation work. Move it when that lands.
 *
 * Rules: a standard (Discord-published) sticker needs Nitro. A guild sticker is free inside its own
 * guild; anywhere else it needs both Nitro and the USE_EXTERNAL_STICKERS permission — except in a
 * DM, where the permission does not apply.
 */
@JvmOverloads
fun getStickerSendability(
    sticker: Sticker,
    meUser: User,
    currentChannel: Channel? = null,
    currentChannelPermissions: Long? = null,
): StickerSendability {
    val canUsePremium = with(UserUtils) { meUser.canUsePremiumStickers }
    return when (sticker.type) {
        StickerType.STANDARD ->
            if (canUsePremium) StickerSendability.SENDABLE
            else StickerSendability.SENDABLE_WITH_PREMIUM

        StickerType.GUILD -> when {
            sticker.available == false -> StickerSendability.SENDABLE_WITH_PREMIUM_GUILD
            sticker.guildId == currentChannel?.guildId -> StickerSendability.SENDABLE
            currentChannel != null &&
                !currentChannel.isPrivate() &&
                !PermissionUtils.can(Permission.USE_EXTERNAL_STICKERS, currentChannelPermissions) ->
                StickerSendability.NONSENDABLE
            canUsePremium -> StickerSendability.SENDABLE
            else -> StickerSendability.SENDABLE_WITH_PREMIUM
        }

        StickerType.UNKNOWN -> StickerSendability.NONSENDABLE
    }
}
