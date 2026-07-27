package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.stageinstance.StageInstance
import com.discord.models.domain.ModelInvite
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.api.guild.Guild as ApiGuild

/**
 * Everything the invite-share surfaces need about "the invite as it stands right now".
 *
 * @param isValidInvite the current [invite] really belongs to [targetChannel] — a *fresh* link. It
 *   is false while a link for a different channel is still on screen, which is what drives the
 *   automatic regeneration in [WidgetGuildInviteShareViewModel].
 * @param isInviteFromStore the invite came from [com.discord.stores.StoreInstantInvites] rather
 *   than from this screen's generator. Such an invite is displayed as-is: no channel picker, no
 *   settings, no regeneration.
 */
data class WidgetInviteModel(
    val targetChannel: Channel?,
    val settings: ModelInvite.Settings?,
    val invite: ModelInvite?,
    val isGeneratingInvite: Boolean = false,
    val isValidInvite: Boolean = false,
    val invitableChannels: List<Channel>,
    val me: MeUser,
    val dms: List<Channel>,
    val isInviteFromStore: Boolean = false,
) {

    companion object {

        /**
         * Composes the model. The branch order matters:
         *
         * 1. an invite handed in by the store short-circuits everything;
         * 2. otherwise the target channel is chosen, and the generated invite counts as valid only
         *    while it points at that same channel;
         * 3. if there is no target channel at all, or generation failed, the guild's *static*
         *    invite is used — a stage instance's invite code first, then the vanity URL — and that
         *    substitute is reported as valid, because there is nothing to regenerate;
         * 4. finally, a scheduled event belonging to the target channel is attached so
         *    [ModelInvite.toLink] appends `?event=`.
         */
        fun create(
            settings: ModelInvite.Settings,
            invitableChannels: Map<Long, Channel>,
            inviteGenerationState: InviteGenerator.InviteGenerationState,
            selectedChannelId: Long?,
            me: MeUser,
            dms: List<Channel>,
            guild: Guild?,
            guildStageInstances: Map<Long, StageInstance>,
            guildScheduledEvent: GuildScheduledEvent?,
            existingInviteFromStore: ModelInvite?,
        ): WidgetInviteModel {
            if (existingInviteFromStore != null) {
                return WidgetInviteModel(
                    targetChannel = null,
                    settings = null,
                    invite = existingInviteFromStore,
                    isGeneratingInvite = false,
                    isValidInvite = true,
                    invitableChannels = emptyList(),
                    me = me,
                    dms = dms,
                    isInviteFromStore = true,
                )
            }

            val sortedChannels = invitableChannels.values.sortedWith(Channel.getSortByNameAndType())
            val targetChannel = invitableChannels[selectedChannelId] ?: sortedChannels.firstOrNull()
            val generatedInvite = inviteGenerationState.lastGeneratedInvite

            var isValidInvite = generatedInvite != null &&
                targetChannel != null &&
                generatedInvite.channel?.id == targetChannel.id

            var invite = generatedInvite
            if (targetChannel == null ||
                inviteGenerationState.state == InviteGenerator.GenerationState.FAILURE
            ) {
                val staticInvite = tryGetStaticInvite(selectedChannelId, guild, guildStageInstances)
                if (staticInvite != null) {
                    invite = staticInvite
                    isValidInvite = true
                }
            }

            if (targetChannel != null &&
                guildScheduledEvent != null &&
                guildScheduledEvent.isInChannel(targetChannel.id)
            ) {
                invite?.setGuildScheduledEvent(guildScheduledEvent)
            }

            return WidgetInviteModel(
                targetChannel = targetChannel,
                settings = settings,
                invite = invite,
                isGeneratingInvite =
                    inviteGenerationState.state == InviteGenerator.GenerationState.GENERATING,
                isValidInvite = isValidInvite,
                invitableChannels = sortedChannels,
                me = me,
                dms = dms,
                isInviteFromStore = false,
            )
        }

        /**
         * A permanent link for the guild: the stage instance running in the selected channel
         * publishes one, and a community guild may have a vanity URL. Stage wins — it is the more
         * specific destination.
         */
        private fun tryGetStaticInvite(
            selectedChannelId: Long?,
            guild: Guild?,
            guildStageInstances: Map<Long, StageInstance>,
        ): ModelInvite? {
            if (guild == null) return null
            val stageInviteCode = guildStageInstances[selectedChannelId]?.inviteCode
            if (stageInviteCode != null) {
                return ModelInvite.createForStaticUrl(stageInviteCode, guild.toApiGuild())
            }
            val vanityUrlCode = guild.vanityUrlCode ?: return null
            return ModelInvite.createForStaticUrl(vanityUrlCode, guild.toApiGuild())
        }
    }
}

/**
 * Widens a cached [Guild] back into the wire shape [ModelInvite] holds.
 *
 * Members, presences, channels and voice states are deliberately dropped — the cached model does
 * not carry them, and the invite only ever reads name/icon/id off this. `approximateMemberCount`
 * is zeroed for the same reason: the cached guild's `memberCount` is the *exact* count and would
 * be rendered under an "approximately" label.
 *
 * Reference: `com.discord.utilities.guilds.GuildUtilsKt.createApiGuild`. **Belongs in
 * `utilities/guilds/GuildUtils.kt`** — it lives here only because that file is owned elsewhere.
 */
internal fun Guild.toApiGuild(): ApiGuild = ApiGuild(
    roles = roles,
    emojis = emojis,
    stickers = stickers,
    name = name,
    description = description,
    defaultMessageNotifications = defaultMessageNotifications,
    id = id,
    region = region,
    ownerId = ownerId,
    icon = icon,
    verificationLevel = verificationLevel,
    explicitContentFilter = explicitContentFilter,
    presences = null,
    channels = null,
    members = null,
    voiceStates = null,
    unavailable = unavailable,
    mfaLevel = mfaLevel,
    afkTimeout = afkTimeout,
    afkChannelId = afkChannelId,
    systemChannelId = systemChannelId,
    features = features.toList(),
    memberCount = memberCount,
    banner = banner,
    splash = splash,
    premiumTier = premiumTier,
    premiumSubscriptionCount = premiumSubscriptionCount,
    systemChannelFlags = systemChannelFlags,
    joinedAt = joinedAt,
    rulesChannelId = rulesChannelId,
    publicUpdatesChannelId = publicUpdatesChannelId,
    preferredLocale = preferredLocale,
    welcomeScreen = welcomeScreen,
    maxVideoChannelUsers = maxVideoChannelUsers,
    vanityUrlCode = vanityUrlCode,
    approximateMemberCount = 0,
    approximatePresenceCount = approximatePresenceCount,
    permissions = null,
    guildHashes = null,
    channelUpdates = null,
    threads = null,
    nsfw = nsfw,
    stageInstances = null,
    guildScheduledEvents = null,
    embeddedActivities = emptyList(),
    hubType = hubType,
)
