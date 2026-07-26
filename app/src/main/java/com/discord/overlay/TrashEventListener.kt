package com.discord.overlay

import com.discord.overlay.views.OverlayBubbleWrap

/**
 * Receives the two things that can happen to a bubble at the trash target.
 *
 * [OverlayManager] fans every event out to two listeners: the `TrashWrap` view, which animates
 * itself, and the host service, which owns the bubble's lifetime.
 */
interface TrashEventListener {

    /**
     * The bubble hovering the drop zone changed.
     *
     * [bubble] is `null` when the drag left the zone without dropping, so implementations must treat
     * this as "the hover state is now X", not as "a bubble entered".
     */
    fun onBubbleInTrashZoneChanged(bubble: OverlayBubbleWrap?)

    /**
     * [bubble] was dropped in the trash and has finished its dismissal animation, so it can now be
     * detached from the window.
     */
    fun onBubbleDismissed(bubble: OverlayBubbleWrap)
}
