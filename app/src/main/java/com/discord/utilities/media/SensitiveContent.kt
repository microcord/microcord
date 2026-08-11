package com.discord.utilities.media

import com.discord.api.message.attachment.MessageAttachment

enum class Redaction { SHOW, BLUR, BLOCK }

enum class ContentSurface { GUILD, FRIEND_DM, NON_FRIEND_DM }

/** Unset means blocked in DMs from strangers and shown everywhere else, as the server reads it. */
data class SensitiveContentSetting(
    val guilds: Redaction = Redaction.SHOW,
    val friendDm: Redaction = Redaction.SHOW,
    val nonFriendDm: Redaction = Redaction.BLOCK,
) {
    fun forSurface(surface: ContentSurface): Redaction = when (surface) {
        ContentSurface.GUILD -> guilds
        ContentSurface.FRIEND_DM -> friendDm
        ContentSurface.NON_FRIEND_DM -> nonFriendDm
    }

    fun withSurface(surface: ContentSurface, redaction: Redaction): SensitiveContentSetting = when (surface) {
        ContentSurface.GUILD -> copy(guilds = redaction)
        ContentSurface.FRIEND_DM -> copy(friendDm = redaction)
        ContentSurface.NON_FRIEND_DM -> copy(nonFriendDm = redaction)
    }
}

data class SensitiveContentSettings(
    val explicit: SensitiveContentSetting = SensitiveContentSetting(),
    val gore: SensitiveContentSetting = SensitiveContentSetting(),
    val selfHarm: SensitiveContentSetting = SensitiveContentSetting(),
) {
    fun redactionFor(attachment: MessageAttachment, surface: ContentSurface): Redaction {
        val flags = attachment.flags ?: return Redaction.SHOW
        var strictest = Redaction.SHOW
        fun apply(flag: Int, setting: SensitiveContentSetting) {
            if (flags and flag != flag) return
            val redaction = setting.forSurface(surface)
            if (redaction > strictest) strictest = redaction
        }
        apply(MessageAttachment.FLAG_CONTAINS_EXPLICIT_MEDIA, explicit)
        apply(MessageAttachment.FLAG_CONTAINS_GORE_CONTENT, gore)
        apply(MessageAttachment.FLAG_CONTAINS_SELF_HARM_CONTENT, selfHarm)
        return strictest
    }
}
