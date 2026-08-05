package com.discord.widgets.chat.input

import com.discord.api.channel.Channel
import com.discord.api.channel.isAnnouncementChannel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.channel.isSystemDM
import com.discord.api.channel.isPendingMessageRequestChannel
import com.discord.api.channel.isThread
import com.discord.api.channel.getDMRecipient
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.permission.Permission
import com.discord.api.premium.PremiumTier
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.MeUser
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreChatInputEditing
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePendingReplies
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadDraft
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserRelationships
import com.discord.utilities.guilds.GuildVerificationLevelUtils
import com.discord.utilities.guilds.MemberVerificationUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.user.UserUtils.isStaff
import rx.Observable

/**
 * Everything the composer needs to know about the channel it is sitting in.
 *
 * The model exists to answer one question — *can this person type here, and if not, why not?* —
 * because the answer has seven mutually exclusive forms and the fragment must not be the place
 * that untangles them. [ViewState.Loaded.isInputShowing] is the composer; every other flag names
 * one specific reason it is hidden, and exactly one guard is shown for it.
 *
 * Reference: `com.discord.widgets.chat.input.ChatInputViewModel`. That class also owns sending,
 * thread creation, sticker sends and application-command dispatch; here those still live in
 * `WidgetChatInput` on top of `MessageSendCoordinator`, so this model is state-only. The fields
 * below keep the reference's names and declaration order so the two can be reconciled later.
 */
class ChatInputViewModel(
    private val storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    private val storeUsers: StoreUser = StoreStream.getUsers(),
    private val storeUserRelationships: StoreUserRelationships = StoreStream.getUserRelationships(),
    private val storePermissions: StorePermissions = StoreStream.getPermissions(),
    private val storePendingReplies: StorePendingReplies = StoreStream.getPendingReplies(),
    private val storeChatInputEditing: StoreChatInputEditing = StoreStream.getChatInputEditing(),
    private val clock: Clock = ClockFactory.get(),
) : AppViewModel<ChatInputViewModel.ViewState>(ViewState.Loading) {

    sealed class ViewState {

        /** No channel resolved yet. Every guard and the composer itself stay hidden. */
        object Loading : ViewState()

        data class Loaded(
            val channel: Channel,
            val channelId: Long,
            val me: MeUser,
            val meGuildMember: GuildMember?,
            val editingMessage: Message?,
            val ableToSendMessage: Boolean,
            val verificationLevelTriggered: GuildVerificationLevel,
            val isLurking: Boolean,
            val isSystemDM: Boolean,
            val isOnCooldown: Boolean,
            val maxFileSizeMB: Int,
            val shouldShowFollow: Boolean,
            val pendingReplyState: PendingReplyState,
            val shouldBadgeChatInput: Boolean,
            val isBlocked: Boolean,
            val isInputShowing: Boolean,
            val isVerificationLevelTriggered: Boolean,
            val isEditing: Boolean,
            val isReplying: Boolean,
            val isCommunicationDisabled: Boolean,
            val timeoutLeftMs: Long,
            val selectedThreadDraft: StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft?,
            val shouldShowVerificationGate: Boolean,
            val threadDraftState: StoreThreadDraft.ThreadDraftState,
            val showCreateThreadOption: Boolean,
        ) : ViewState()

        /**
         * The reply bar above the composer.
         *
         * `Hide` rather than a nullable field because the bar is also used for editing, and the
         * two states must not both be representable at once.
         */
        sealed class PendingReplyState {
            object Hide : PendingReplyState()

            data class Replying(val pendingReply: StorePendingReplies.PendingReply) : PendingReplyState()
        }
    }

    init {
        observeStoreState()
            .ui(this)
            .subscribe({ updateViewState(it) }, { /* keep the last good state */ })
    }

    /**
     * The composed store state.
     *
     * `switchMap` on the selected channel rather than `combineLatest` across everything: the
     * per-channel sources (permissions, verification level, lurking) are only meaningful for one
     * channel at a time, and combining them flat would briefly pair a new channel with the
     * previous channel's permissions — which is visible as the guard flashing on every switch.
     */
    private fun observeStoreState(): Observable<ViewState> =
        storeChannelsSelected.observeResolvedSelectedChannel().switchMap { resolved ->
            val channel = resolved.channelOrParent ?: return@switchMap Observable.just<ViewState>(ViewState.Loading)
            val threadDraft = resolved as? StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft
            val guildId = channel.guildId

            // Two nested combines rather than one: RxJava 1 only overloads `combineLatest` up to
            // nine sources, and the reference reaches thirteen through its own arity helper.
            val identity = Observable.combineLatest(
                storeUsers.observeMe(true),
                storeChatInputEditing.observe(channel.id),
                storeUserRelationships.observe(channel.getDMRecipient()?.id ?: 0L),
                storeGuilds.observeComputedMember(guildId, storeUsers.getMe().id),
            ) { me, editing, relationshipType, meGuildMember ->
                IdentityState(me, editing, relationshipType, meGuildMember)
            }
            val access = Observable.combineLatest(
                storePermissions.observePermissionsForChannel(channel.id),
                verificationLevelTriggered(guildId),
                ChatInputStoreBindings.isLurking(guildId),
                ChatInputStoreBindings.slowModeCooldown(channel.id).map { it > 0 },
                storeGuilds.observeFromChannelId(channel.id),
                storePendingReplies.observePendingReply(channel.id),
                ChatInputStoreBindings.threadDraftState(),
            ) { permissions, verificationLevel, isLurking, isOnCooldown, guild, pendingReply, threadDraft ->
                AccessState(
                    permissions, verificationLevel, isLurking, isOnCooldown, guild, pendingReply, threadDraft,
                )
            }

            Observable.combineLatest(identity, access) { who, where ->
                buildViewState(
                    channel = channel,
                    selectedThreadDraft = threadDraft,
                    me = who.me,
                    editingMessage = who.editingMessage,
                    relationshipType = who.relationshipType,
                    channelPermissions = where.channelPermissions,
                    verificationLevelTriggered = where.verificationLevelTriggered,
                    isLurking = where.isLurking,
                    isOnCooldown = where.isOnCooldown,
                    guild = where.guild,
                    pendingReply = where.pendingReply,
                    meGuildMember = who.meGuildMember,
                    threadDraftState = where.threadDraftState,
                )
            }
        }.distinctUntilChanged()

    /** Who is typing. Grouped only to stay under RxJava 1's `combineLatest` arity ceiling. */
    private data class IdentityState(
        val me: MeUser,
        val editingMessage: Message?,
        val relationshipType: Int?,
        val meGuildMember: GuildMember?,
    )

    /** Where they are typing, and what that place currently permits. */
    private data class AccessState(
        val channelPermissions: Long?,
        val verificationLevelTriggered: GuildVerificationLevel,
        val isLurking: Boolean,
        val isOnCooldown: Boolean,
        val guild: Guild?,
        val pendingReply: StorePendingReplies.PendingReply?,
        val threadDraftState: StoreThreadDraft.ThreadDraftState,
    )

    private fun verificationLevelTriggered(guildId: Long): Observable<GuildVerificationLevel> =
        if (guildId == 0L) Observable.just(GuildVerificationLevel.NONE)
        else GuildVerificationLevelUtils.observeVerificationLevelTriggered(guildId, storeGuilds, storeUsers, clock)

    private fun buildViewState(
        channel: Channel,
        selectedThreadDraft: StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft?,
        me: MeUser,
        editingMessage: Message?,
        relationshipType: Int?,
        channelPermissions: Long?,
        verificationLevelTriggered: GuildVerificationLevel,
        isLurking: Boolean,
        isOnCooldown: Boolean,
        guild: Guild?,
        pendingReply: StorePendingReplies.PendingReply?,
        meGuildMember: GuildMember?,
        threadDraftState: StoreThreadDraft.ThreadDraftState,
    ): ViewState {
        // A thread draft is not yet a channel, so write access is decided by the thread permission
        // on the parent rather than by the parent's own write access.
        val canWrite = if (selectedThreadDraft == null) {
            PermissionUtils.hasAccessWrite(channel, channelPermissions)
        } else {
            PermissionUtils.can(Permission.SEND_MESSAGES_IN_THREADS, channelPermissions)
        }

        val isBlocked = relationshipType == ModelUserRelationship.TYPE_BLOCKED
        val archivedAndCannotUnarchive = channel.isThread() &&
            channel.threadMetadata?.archived == true &&
            !PermissionUtils.can(Permission.MANAGE_THREADS, channelPermissions)

        val canSendHere = !isBlocked && canWrite && !isLurking && !archivedAndCannotUnarchive
        // In a forum the composer only exists while a post is being drafted; the channel itself
        // has no message list to type into.
        val ableToSendMessage =
            if (channel.isThreadOnlyChannel()) canSendHere && selectedThreadDraft != null else canSendHere

        val shouldShowFollow = channel.isAnnouncementChannel() && !ableToSendMessage
        val isVerificationLevelTriggered = verificationLevelTriggered != GuildVerificationLevel.NONE

        val pendingReplyState = if (pendingReply != null) {
            ViewState.PendingReplyState.Replying(pendingReply)
        } else {
            ViewState.PendingReplyState.Hide
        }

        // "Pending" is the member-screening flag: the user is in the guild but has not accepted
        // the rules, so the gate is shown even though membership exists.
        val hasAcceptedGate = meGuildMember != null && !meGuildMember.pending
        val shouldShowVerificationGate = MemberVerificationUtils.hasVerificationGate(guild) && !hasAcceptedGate
        val isCommunicationDisabled = meGuildMember?.isCommunicationDisabled() == true

        val isInputShowing = !channel.isSystemDM() &&
            !channel.isPendingMessageRequestChannel() &&
            !isLurking &&
            !isVerificationLevelTriggered &&
            !shouldShowFollow &&
            !shouldShowVerificationGate &&
            !isCommunicationDisabled

        return ViewState.Loaded(
            channel = channel,
            channelId = channel.id,
            me = me,
            meGuildMember = meGuildMember,
            editingMessage = editingMessage,
            ableToSendMessage = ableToSendMessage,
            verificationLevelTriggered = verificationLevelTriggered,
            isLurking = isLurking,
            isSystemDM = channel.isSystemDM(),
            isOnCooldown = isOnCooldown,
            maxFileSizeMB = maxFileSizeMB(me, guild),
            shouldShowFollow = shouldShowFollow,
            pendingReplyState = pendingReplyState,
            // Reference passes a literal `false`: the expression-tray badge was retired but the
            // field was left in the state. Reproduced rather than repurposed.
            shouldBadgeChatInput = false,
            isBlocked = isBlocked,
            isInputShowing = isInputShowing,
            isVerificationLevelTriggered = isVerificationLevelTriggered,
            isEditing = ableToSendMessage && editingMessage != null && editingMessage.channelId == channel.id,
            isReplying = pendingReplyState is ViewState.PendingReplyState.Replying,
            isCommunicationDisabled = isCommunicationDisabled,
            timeoutLeftMs = calculateTimeoutLeftMs(meGuildMember),
            selectedThreadDraft = selectedThreadDraft,
            shouldShowVerificationGate = shouldShowVerificationGate,
            threadDraftState = threadDraftState,
            // Reference: `ThreadUtils.canCreateThread(permissions, channel, null, guild)`.
            // `utilities/threads/ThreadUtils` does not exist in this project, so the composer's
            // "create thread" attachment page stays hidden rather than leading nowhere.
            showCreateThreadOption = false,
        )
    }

    /**
     * Milliseconds left on this member's timeout, or 0 when they are not timed out.
     *
     * Deliberately not clamped: a negative value means the timeout expired but the member object
     * has not been refreshed yet, and the guard treats that as "not disabled" via
     * [GuildMember.isCommunicationDisabled].
     */
    private fun calculateTimeoutLeftMs(guildMember: GuildMember?): Long {
        val until = guildMember?.communicationDisabledUntil ?: return 0L
        return until.dateTimeMillis - clock.currentTimeMillis()
    }

    /**
     * Upload cap in megabytes: the larger of the user's own Nitro allowance and the guild's boost
     * allowance.
     *
     * Reference: `PremiumUtils.getMaxFileSizeMB` / `getGuildMaxFileSizeMB`. That object is not
     * reconstructed; the tiers are transcribed here and should move to
     * `utilities/premium/PremiumUtils` when it lands.
     */
    private fun maxFileSizeMB(me: MeUser, guild: Guild?): Int = maxOf(
        when {
            me.isStaff -> STAFF_MAX_FILE_SIZE_MB
            me.premiumTier == PremiumTier.TIER_1 -> 50
            me.premiumTier == PremiumTier.TIER_2 -> 100
            me.premiumTier == PremiumTier.TIER_0 -> 20
            else -> DEFAULT_MAX_FILE_SIZE_MB
        },
        when (guild?.premiumTier ?: 0) {
            0, 1 -> DEFAULT_MAX_FILE_SIZE_MB
            2 -> 50
            else -> 100
        },
    )

    private companion object {
        private const val DEFAULT_MAX_FILE_SIZE_MB = 8
        private const val STAFF_MAX_FILE_SIZE_MB = 200
    }
}
