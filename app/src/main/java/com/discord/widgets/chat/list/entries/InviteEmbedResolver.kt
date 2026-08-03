package com.discord.widgets.chat.list.entries

import com.discord.models.domain.ModelInvite

/**
 * Resolves an invite code found in a message body into the richer row it deserves.
 *
 * Temporary seam standing in for the reference's `utilities/embed/InviteEmbedModel`, which is built
 * from `StoreInstantInvites` and `StoreRequestedStageChannels`. Neither store exists yet, so the
 * default resolver answers "unknown" for everything and every invite renders as the ordinary
 * invite card — which is also the reference's behaviour before an invite resolves.
 */
interface InviteEmbedResolver {

    /** The resolved invite, regardless of its target type. */
    fun resolveInvite(inviteCode: String, eventId: String? = null): ModelInvite? = null

    /** The invite behind [inviteCode] when it points at a live public stage, otherwise null. */
    fun resolveInviteCodeForPublicStage(inviteCode: String): ModelInvite? = null

    /** The invite behind [inviteCode] when it carries the scheduled event [eventId]. */
    fun resolveInviteForGuildScheduledEvent(inviteCode: String, eventId: String): ModelInvite? = null

    companion object {
        /** Resolves nothing; every invite falls back to the ordinary guild-invite card. */
        val UNRESOLVED: InviteEmbedResolver = object : InviteEmbedResolver {}
    }
}
