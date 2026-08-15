package com.discord.utilities.soundboard

import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.permission.Permission
import com.discord.api.soundboard.SoundboardSound
import com.discord.utilities.permissions.PermissionUtils

object SoundboardUtils {

    /**
     * Whether [channel] accepts a sound owned by [soundGuildId].
     *
     * A call among friends has no permissions to consult. Elsewhere, a sound from another guild
     * needs [Permission.USE_EXTERNAL_SOUNDS]; the built-in sounds and the channel's own guild's
     * sounds are exempt.
     */
    fun hasPermissionToPlaySound(soundGuildId: Long?, channel: Channel?, channelPermissions: Long?): Boolean =
        channel == null || channel.isPrivate() || isLocalTo(soundGuildId, channel) ||
            PermissionUtils.can(Permission.USE_EXTERNAL_SOUNDS, channelPermissions)

    /**
     * Whether [sound] may be played into [channel].
     *
     * Carrying a sound out of the guild that owns it is a Nitro perk. A sound whose guild has since
     * dropped below the boost level that unlocked it reports `available: false` and works nowhere;
     * [checkAvailable] is off only where the caller has already accounted for that.
     */
    fun canUseSoundboardSound(
        sound: SoundboardSound,
        hasNitro: Boolean,
        channel: Channel?,
        channelPermissions: Long?,
        checkAvailable: Boolean = true,
    ): Boolean =
        (hasNitro || isLocalTo(sound.guildId, channel)) &&
            hasPermissionToPlaySound(sound.guildId, channel, channelPermissions) &&
            (!checkAvailable || sound.available != false)

    /** The `source_guild_id` to send a sound with; the reference omits it for the built-ins. */
    fun sourceGuildId(soundGuildId: Long?): Long? =
        soundGuildId?.takeIf { it != SoundboardConstants.DEFAULT_SOUND_GUILD_ID }

    /** A built-in sound, or one owned by the guild [channel] is in. */
    private fun isLocalTo(soundGuildId: Long?, channel: Channel?): Boolean =
        soundGuildId == null || soundGuildId == SoundboardConstants.DEFAULT_SOUND_GUILD_ID ||
            soundGuildId == channel?.guildId
}
