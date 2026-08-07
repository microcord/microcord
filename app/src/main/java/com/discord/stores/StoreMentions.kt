package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.isManaged
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isThread
import com.discord.api.notification.ReadStateFlags
import com.discord.api.thread.ThreadMemberFlags
import com.discord.api.guild.Guild
import com.discord.api.guildmember.GuildMember
import com.discord.api.permission.Permission
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.message.Message
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.channel.ChannelNotificationSettingsUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import rx.Observable

/**
 * The red mention badge: how many messages in each channel mention the current user.
 *
 * The count cannot come from the server alone. `READY` carries a per-channel mention *count* but
 * no message ids, while live `MESSAGE_CREATE` events carry messages but no count, and either one
 * on its own goes wrong: server counts never decrease as messages are read in-session, and live
 * counting alone loses everything that arrived while offline. [computeMentionCounts] therefore
 * merges both sources against the ack ledger every time a snapshot is taken — the server's
 * counts for channels the user has not read past, plus locally observed mentions newer than the
 * read marker.
 *
 * The other half of the job is deciding what *is* a mention, which is not just "my id is in the
 * mentions array": DMs count wholesale, `@everyone` and role mentions are suppressible per guild,
 * edits do not re-notify, and an unjoined thread never notifies. That is [hasMention].
 */
class StoreMentions @JvmOverloads constructor(
    private val storeUserRelationships: StoreUserRelationships,
    private val storePermissions: StorePermissions,
    private val storeMessageAck: StoreMessageAck,
    private val storeUserGuildSettings: StoreUserGuildSettings,
    private val storeChannels: StoreChannels,
    private val observationDeck: ObservationDeck,
    /**
     * Whether the current user has joined thread [channelId].
     *
     * The reference reads `StoreThreadsJoined.hasJoined`, which does not exist in this target
     * yet. Defaulting to `false` is the conservative answer — an unjoined thread never mentions —
     * but it also means threads contribute no mentions until this is bound. See the handoff notes.
     */
    private val hasJoinedThread: (channelId: Long) -> Boolean = { false },
    /** Notification flags for a joined thread. Wired from `StoreThreadsJoined`. */
    private val getJoinedThreadFlags: (channelId: Long) -> Int = { 0 },
    /** A guild's own `default_message_notifications`. Wired from `StoreGuilds`. */
    private val getGuildDefaultMessageNotifications: (guildId: Long) -> Int =
        { ModelNotificationSettings.FREQUENCY_ALL },
) : StoreV2() {

    private val countsEmpty: Map<Long, Int> = HashMap()
    private val countsCache = Persister(MENTION_COUNTS_CACHE_KEY, countsEmpty)
    private var countsSnapshot: Map<Long, Int> = emptyMap()

    /** The `READY`/`MESSAGE_ACK` read states that reported a non-zero mention count. */
    private val serverInitReadStates = ArrayList<ModelReadState>()

    /** Mentions observed live this session, by message id. */
    private val mentionedMessages = HashMap<Long, Message>()

    /** Of [mentionedMessages], the ones that only count because of `MENTION_ON_ALL_MESSAGES`. */
    private val lowImportanceMessageIds = HashSet<Long>()

    private var lowImportanceChannelsSnapshot: Set<Long> = emptySet()

    private val privateChannels = HashSet<Long>()
    private val myRoleIds = HashMap<Long, List<Long>>()
    private val channelGuildIds = HashMap<Long, Long>()
    private var selectedChannelId = 0L
    private var myId = 0L

    fun getMentionCounts(): Map<Long, Int> = countsSnapshot

    fun observeMentionCounts(): Observable<Map<Long, Int>> =
        observationDeck.connectRx(this) { getMentionCounts() }.distinctUntilChanged()

    /**
     * Channels whose every mention came from an all-messages channel rather than a real ping.
     * Their badge is drawn muted, and they are left out of the total.
     */
    fun getLowImportanceMentionChannels(): Set<Long> = lowImportanceChannelsSnapshot

    fun observeLowImportanceMentionChannels(): Observable<Set<Long>> =
        observationDeck.connectRx(this) { getLowImportanceMentionChannels() }.distinctUntilChanged()

    /** Excludes low-importance mentions, so ordinary traffic never inflates the app badge. */
    fun observeTotalMentions(): Observable<Int> =
        observationDeck.connectRx(this) { getMentionCounts() to getLowImportanceMentionChannels() }
            .map { (counts, low) -> counts.entries.filterNot { it.key in low }.sumOf { it.value } }
            .distinctUntilChanged()

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        privateChannels.clear()
        mentionedMessages.clear()
        lowImportanceMessageIds.clear()
        channelGuildIds.clear()
        myRoleIds.clear()
        myId = payload.me?.id ?: 0L

        payload.guilds.orEmpty().forEach(::handleGuildAdd)

        privateChannels += payload.privateChannels.orEmpty()
            .filterNot { it.isManaged() }
            .map { it.id }

        val readState = payload.readState
        if (readState?.isPartial != true) {
            serverInitReadStates.clear()
        } else {
            // A partial read state replaces only the channels it mentions; everything else keeps
            // the counts it already had.
            val replaced = readState.entries.orEmpty().map { it.channelId }.toSet()
            serverInitReadStates.removeAll { it.channelId in replaced }
        }
        serverInitReadStates += readState?.entries.orEmpty()
            .filter { it.isChannelReadState && it.isMentioned }

        // Read states for channels the client cannot see would otherwise pin an unclearable badge.
        val knownChannels = storeChannels.getChannelNamesInternal()
        serverInitReadStates.removeAll { it.channelId !in knownChannels }
        markChanged()
    }

    @StoreThread
    fun handleGuildAdd(guild: Guild) {
        guild.members.orEmpty().firstOrNull { it.user.id == myId }?.let { member ->
            myRoleIds[guild.id] = member.roles
        }
        guild.channels.orEmpty().forEach { channelGuildIds[it.id] = guild.id }
        guild.threads.orEmpty().forEach { channelGuildIds[it.id] = guild.id }
    }

    @StoreThread
    fun handleGuildMemberAdd(member: GuildMember) {
        if (member.user.id == myId) myRoleIds[member.guildId] = member.roles
    }

    @StoreThread
    fun handleChannelOrThreadCreateOrUpdate(channel: Channel) {
        channelGuildIds[channel.id] = channel.guildId
        if (channel.isPrivate() && !channel.isManaged()) privateChannels += channel.id
    }

    @StoreThread
    fun handleChannelOrThreadDelete(channel: Channel) {
        channelGuildIds.remove(channel.id)
        privateChannels.remove(channel.id)
        removeAllMessagesForChannel(channel.id)
    }

    @StoreThread
    fun handleThreadListSync(payload: ModelThreadListSync) {
        payload.threads.forEach { channelGuildIds[it.id] = payload.guildId }
    }

    /** Leaving a thread discards its mentions; the badge must not outlive membership. */
    @StoreThread
    fun handleThreadMembersUpdate(payload: ThreadMembersUpdate) {
        if (payload.removedMemberIds?.contains(myId) == true) removeAllMessagesForChannel(payload.id)
    }

    @StoreThread
    fun handleChannelSelected(selectedChannelId: Long) {
        this.selectedChannelId = selectedChannelId
        markChanged()
    }

    /** An ack supersedes whatever server count the channel had. */
    @StoreThread
    fun handleMessageAck(readState: ModelReadState) {
        serverInitReadStates.removeAll { it.channelId == readState.channelId }
        serverInitReadStates += readState
        markChanged()
    }

    @StoreThread
    fun handleMessageCreateOrUpdate(message: com.discord.api.message.Message) {
        if (hasMention(message)) {
            mentionedMessages[message.id] = Message(message)
            lowImportanceMessageIds.remove(message.id)
            markChanged()
        } else if (isLowImportanceMention(message.channelId, message.author)) {
            mentionedMessages[message.id] = Message(message)
            lowImportanceMessageIds += message.id
            markChanged()
        }
    }

    /**
     * Deletions only clear DM mentions.
     *
     * In a guild the server's own count is authoritative and will correct itself on the next
     * read state; in a DM there is no such correction, so the client has to do it.
     */
    @StoreThread
    fun handleMessageDeleted(messageDeleteBulk: ModelMessageDelete) {
        if (messageDeleteBulk.channelId !in privateChannels) return
        messageDeleteBulk.messageIds.orEmpty().forEach {
            mentionedMessages.remove(it)
            lowImportanceMessageIds.remove(it)
        }
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        serverInitReadStates.clear()
        mentionedMessages.clear()
        lowImportanceMessageIds.clear()
        privateChannels.clear()
        myRoleIds.clear()
        channelGuildIds.clear()
        selectedChannelId = 0L
        markChanged()
    }

    /**
     * Re-counts mentions in [messagesAfter] when a channel is manually marked unread, returning
     * how many were re-created so the ack request can tell the server the same number.
     */
    @StoreThread
    internal fun processMarkUnread(channelId: Long, messagesAfter: List<Message>): Int {
        var count = 0
        messagesAfter.forEach { message ->
            if (message.id !in mentionedMessages && hasMention(message)) {
                mentionedMessages[message.id] = message
                count++
            }
        }
        serverInitReadStates.removeAll { it.channelId == channelId }
        markChanged()
        return count
    }

    @StoreThread
    override fun snapshotData() {
        val counts = computeMentionCounts()
        countsCache.set(counts)
        countsSnapshot = counts
        lowImportanceChannelsSnapshot = computeLowImportanceChannels(counts.keys)
    }

    /**
     * Merges server counts and locally observed mentions against the ack ledger.
     *
     * A server count only survives while the read marker is still at or behind the read state it
     * came with; a local mention only counts while it is newer than the read marker. Both checks
     * are what make the badge clear the moment a channel is acked, without waiting for the server
     * to echo a new read state back.
     */
    @StoreThread
    private fun computeMentionCounts(): Map<Long, Int> {
        if (serverInitReadStates.isEmpty() && mentionedMessages.isEmpty()) return countsEmpty

        val counts = HashMap<Long, Int>()
        val acks = storeMessageAck.getAllInternal()

        serverInitReadStates.forEach { readState ->
            if (readState.mentionCount < 1 || !isMentionableChannel(readState.channelId)) return@forEach
            val ack = acks[readState.channelId] ?: return@forEach
            if (ack.messageId <= readState.lastMessageId) {
                counts[readState.channelId] = (counts[readState.channelId] ?: 0) + readState.mentionCount
            }
        }

        val relationships = storeUserRelationships.getRelationships()
        mentionedMessages.values.forEach { message ->
            val authorId = message.author?.id
            if (relationships[authorId] == ModelUserRelationship.TYPE_BLOCKED) return@forEach
            if (!isMentionableChannel(message.channelId)) return@forEach
            val ack = acks[message.channelId]
            if (ack != null && ack.messageId >= message.id) return@forEach
            counts[message.channelId] = (counts[message.channelId] ?: 0) + 1
        }
        return counts
    }

    /**
     * Whether [channelId]'s mentions should show a badge at all.
     *
     * The currently open channel is excluded, since its mentions are being read right now — the
     * exception being a channel the user explicitly marked unread, which must keep its badge.
     */
    @StoreThread
    private fun isMentionableChannel(channelId: Long): Boolean {
        val isLockedAck = storeMessageAck.getAllInternal()[channelId]?.isLockedAck ?: false
        val visible = channelId in privateChannels ||
            PermissionUtils.can(Permission.VIEW_CHANNEL, storePermissions.getPermissionsByChannel()[channelId])
        return (channelId != selectedChannelId || isLockedAck) && visible
    }

    @StoreThread
    private fun hasMention(message: com.discord.api.message.Message): Boolean = hasMention(
        message.channelId,
        message.author,
        message.editedTimestamp,
        message.mentions,
        message.mentionRoles,
        message.mentionEveryone,
    )

    @StoreThread
    private fun hasMention(message: Message): Boolean = hasMention(
        message.channelId,
        message.author,
        message.editedTimestamp,
        message.mentions,
        message.mentionRoles,
        message.mentionEveryone,
    )

    /**
     * The mention rules, in the reference's order.
     *
     * Order matters because the checks are not independent: a DM short-circuits before role
     * suppression is consulted, and a direct `@user` mention beats a suppressed `@everyone` in
     * the same message.
     */
    @StoreThread
    private fun hasMention(
        channelId: Long,
        author: User?,
        editedTimestamp: UtcDateTime?,
        mentions: List<User>?,
        mentionRoles: List<Long>?,
        mentionEveryone: Boolean?,
    ): Boolean {
        // Own messages never mention, and an edit never re-notifies.
        if (author != null && author.id == myId) return false
        if ((editedTimestamp?.dateTimeMillis ?: 0L) > 0L) return false

        val guildId = channelGuildIds[channelId] ?: 0L
        val channel = storeChannels.getChannel(channelId)
        if (channel != null && channel.isThread() && !hasJoinedThread(channelId)) return false

        val guildSettings = storeUserGuildSettings.getGuildSettingsInternal()

        // Every message in a DM is a mention unless that DM is muted.
        if (channelId in privateChannels) {
            val privateSettings = guildSettings[0L] ?: return true
            val override = privateSettings.getChannelOverride(channelId)
            if (override == null || !override.isMuted) return true
        }

        if (mentions?.any { it.id == myId } == true) return true

        if (guildSettings[guildId]?.isSuppressRoles != true) {
            val roles = myRoleIds[guildId]
            if (roles != null && mentionRoles != null && mentionRoles.any { it in roles }) return true
        }

        if (mentionEveryone == true) return guildSettings[guildId]?.isSuppressEveryone != true
        return false
    }

    @StoreThread
    private fun removeAllMessagesForChannel(channelId: Long) {
        val removable = mentionedMessages.filterValues { it.channelId == channelId }.keys.toList()
        removable.forEach {
            mentionedMessages.remove(it)
            lowImportanceMessageIds.remove(it)
            markChanged()
        }
    }

    /**
     * A channel is low importance only while every mention it holds is, matching the reference
     * latch: one real ping promotes the badge until the channel is acked.
     */
    @StoreThread
    private fun computeLowImportanceChannels(channelsWithCounts: Set<Long>): Set<Long> {
        if (channelsWithCounts.isEmpty()) return emptySet()
        val highImportance = HashSet<Long>()
        val lowImportance = HashSet<Long>()
        mentionedMessages.values.forEach { message ->
            if (message.id in lowImportanceMessageIds) {
                lowImportance += message.channelId
            } else {
                highImportance += message.channelId
            }
        }
        serverInitReadStates.forEach { readState ->
            if (!readState.isMentioned) return@forEach
            if (readState.isMentionLowImportance) {
                lowImportance += readState.channelId
            } else {
                highImportance += readState.channelId
            }
        }
        return channelsWithCounts.filterTo(HashSet()) { it in lowImportance && it !in highImportance }
    }

    /**
     * Whether `MENTION_ON_ALL_MESSAGES` turns an ordinary message into a badge.
     *
     * Only consulted for messages that are not already mentions: an unmuted all-messages channel
     * badges everything, but as a lesser badge than a real ping.
     */
    @StoreThread
    private fun isLowImportanceMention(channelId: Long, author: User?): Boolean {
        if (author != null && author.id == myId) return false
        if (!storeUserGuildSettings.getAccountNotificationSettingsInternal().mentionOnAllMessages) return false
        val channel = storeChannels.getChannel(channelId) ?: return false
        if (channel.isPrivate()) return false

        if (channel.isThread()) {
            if (!hasJoinedThread(channelId)) return false
            val flags = getJoinedThreadFlags(channelId)
            return flags and ThreadMemberFlags.ALL_MESSAGES == ThreadMemberFlags.ALL_MESSAGES
        }

        if (channel.isGuildVocalChannel()) return false
        val guildSettings = storeUserGuildSettings.getGuildSettingsInternal()[channel.guildId]
        // The channel's own override only. The reference deliberately does not consult the guild
        // or the category here, unlike the DM branch above it, and the counts are filtered again
        // at guild-rollup time.
        if (guildSettings?.getChannelOverride(channelId)?.isMuted == true) return false

        val resolved = ChannelNotificationSettingsUtils.resolveMessageNotifications(
            channel,
            guildSettings,
            getGuildDefaultMessageNotifications(channel.guildId),
        )
        return resolved == ModelNotificationSettings.FREQUENCY_ALL
    }

    companion object {
        /** Persistence key; a version bump discards incompatible counts. */
        const val MENTION_COUNTS_CACHE_KEY = "CHANNEL_MENTION_COUNTS_V6"
    }
}
