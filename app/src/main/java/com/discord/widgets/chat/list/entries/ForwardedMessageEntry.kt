package com.discord.widgets.chat.list.entries

import com.discord.api.message.MessageFlags
import com.discord.api.guildmember.GuildMember
import com.discord.api.user.User
import com.discord.models.message.Message

data class ForwardedMessageEntry(
    val parentMessage: Message,
    val snapshotIndex: Int,
    val state: State,
    val message: Message? = null,
    val rows: List<ChatListEntry> = emptyList(),
    override val isPendingReply: Boolean = false,
) : ChatListEntry() {
    enum class State { LOADING, CONTENT, DELETED, UNAVAILABLE, DEPTH_LIMIT }

    override val type = FORWARDED_MESSAGE
    override val key = "$type ${parentMessage.id} $snapshotIndex"
    private val sourceSnapshot
        get() = parentMessage.messageSnapshots?.getOrNull(snapshotIndex)?.message
    val sourceGuildId: Long?
        get() = parentMessage.messageReference?.guildId?.takeIf { it > 0L }
            ?: sourceSnapshot?.guildId?.takeIf { it > 0L }
    val sourceChannelId: Long?
        get() = parentMessage.messageReference?.channelId?.takeIf { it > 0L }
            ?: sourceSnapshot?.channelId?.takeIf { it > 0L }
    val sourceMessageId: Long?
        get() = parentMessage.messageReference?.messageId?.takeIf { it > 0L }
            ?: sourceSnapshot?.id?.takeIf { it > 0L }

    companion object {
        const val MAX_RENDER_DEPTH = 1

        fun resolve(parent: Message, depth: Int = 0): List<ForwardedMessageEntry> {
            if (!parent.hasSnapshot() || parent.messageReference?.type != FORWARD_REFERENCE_TYPE) return emptyList()
            if (depth >= MAX_RENDER_DEPTH) {
                return listOf(ForwardedMessageEntry(parent, 0, State.DEPTH_LIMIT))
            }
            val snapshots = parent.messageSnapshots
                ?: return listOf(ForwardedMessageEntry(parent, 0, State.LOADING))
            if (snapshots.isEmpty()) {
                val state = if (parent.hasFlag(MessageFlags.SOURCE_MESSAGE_DELETED)) State.DELETED else State.UNAVAILABLE
                return listOf(ForwardedMessageEntry(parent, 0, state))
            }
            return snapshots.take(MAX_SNAPSHOTS).mapIndexed { index, snapshot ->
                ForwardedMessageEntry(
                    parentMessage = parent,
                    snapshotIndex = index,
                    state = State.CONTENT,
                    message = parent.withSnapshot(Message(snapshot.message)),
                )
            }
        }

        private fun Message.withSnapshot(snapshot: Message): Message {
            val users = snapshot.resolved?.users.orEmpty()
            val members = snapshot.resolved?.members.orEmpty()
            val sourceGuildId = snapshot.guildId ?: messageReference?.guildId ?: guildId
            fun member(user: User, raw: GuildMember? = null): GuildMember? {
                val resolved = members[user.id]
                if (raw == null && resolved == null) return user.member
                return GuildMember(
                    guildId = sourceGuildId ?: 0L,
                    user = user,
                    roles = raw?.roles.orEmpty().ifEmpty { resolved?.roles.orEmpty() },
                    nick = raw?.nick ?: resolved?.nick,
                    premiumSince = raw?.premiumSince ?: resolved?.premiumSince,
                    joinedAt = raw?.joinedAt ?: resolved?.joinedAt,
                    pending = raw?.pending ?: resolved?.pending ?: false,
                    presence = raw?.presence,
                    userId = user.id,
                    avatar = raw?.avatar ?: resolved?.avatar,
                    bio = raw?.bio,
                    banner = raw?.banner,
                    communicationDisabledUntil = raw?.communicationDisabledUntil
                        ?: resolved?.communicationDisabledUntil,
                    avatarDecorationData = raw?.avatarDecorationData,
                    collectibles = raw?.collectibles,
                )
            }
            val mentions = (snapshot.mentions ?: users.values.toList()).map { user ->
                user.normalized(member(user))
            }
            val snapshotAuthor = snapshot.author ?: author
            val normalizedAuthor = snapshotAuthor?.normalized(
                member(snapshotAuthor, snapshot.member.takeIf { snapshot.author?.id == snapshotAuthor.id }),
            )
            return copy(
                id = snapshot.id.takeUnless { it == 0L } ?: messageReference?.messageId ?: id,
                channelId = snapshot.channelId.takeUnless { it == 0L } ?: messageReference?.channelId ?: channelId,
                guildId = sourceGuildId,
                author = normalizedAuthor,
                content = snapshot.content,
                timestamp = snapshot.timestamp,
                editedTimestamp = null,
                mentions = mentions,
                mentionRoles = snapshot.mentionRoles,
                attachments = snapshot.attachments,
                embeds = snapshot.embeds,
                reactions = emptyList(),
                flags = snapshot.flags,
                type = snapshot.type ?: 0,
                stickers = snapshot.stickers,
                stickerItems = snapshot.stickerItems,
                referencedMessage = null,
                messageReference = snapshot.messageReference,
                components = snapshot.components,
                messageSnapshots = snapshot.messageSnapshots,
                poll = snapshot.poll,
                resolved = snapshot.resolved,
            )
        }

        @Suppress("SENSELESS_COMPARISON")
        private fun User.normalized(member: GuildMember?) = User(
            id = id,
            username = username,
            avatar = avatar,
            banner = banner,
            discriminator = if (discriminator == null) "0000" else discriminator,
            publicFlags = publicFlags,
            flags = flags,
            bot = bot,
            system = system,
            token = token,
            email = email,
            verified = verified,
            locale = locale,
            nsfwAllowed = nsfwAllowed,
            mfaEnabled = mfaEnabled,
            phone = phone,
            analyticsToken = analyticsToken,
            premiumType = premiumType,
            approximateGuildCount = approximateGuildCount,
            member = member,
            bio = bio,
            bannerColor = bannerColor,
            globalName = globalName,
            collectibles = collectibles,
            avatarDecorationData = avatarDecorationData,
            primaryGuild = primaryGuild,
        )

        private const val FORWARD_REFERENCE_TYPE = 1
        private const val MAX_SNAPSHOTS = 1
    }
}

object ForwardedSourcePrivacy {
    enum class Visibility { CHANNEL, GUILD, HIDDEN }

    fun visibility(
        destinationGuildId: Long?,
        sourceGuildId: Long?,
        channelKnown: Boolean,
        channelAccessible: Boolean,
        guildKnown: Boolean,
    ): Visibility = when {
        channelKnown && channelAccessible -> Visibility.CHANNEL
        destinationGuildId == sourceGuildId && channelKnown -> Visibility.HIDDEN
        destinationGuildId == sourceGuildId && guildKnown -> Visibility.GUILD
        sourceGuildId != null && guildKnown -> Visibility.GUILD
        else -> Visibility.HIDDEN
    }

    fun isNavigable(
        visibility: Visibility,
        messageKnown: Boolean,
        sourceDeleted: Boolean,
    ): Boolean = visibility == Visibility.CHANNEL && messageKnown && !sourceDeleted

    fun label(visibility: Visibility, channelName: String?, guildName: String?): String? = when (visibility) {
        Visibility.CHANNEL -> guildName ?: channelName
        Visibility.GUILD -> guildName
        Visibility.HIDDEN -> null
    }
}
