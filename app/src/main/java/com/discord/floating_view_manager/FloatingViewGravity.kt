package com.discord.floating_view_manager

/**
 * Where a floating view is placed relative to the view it is anchored to.
 *
 * Horizontal placement is always centred on the anchor; only the vertical relationship varies.
 */
enum class FloatingViewGravity {
    /** The floating view sits directly above the anchor, bottom edge on the anchor's top edge. */
    TOP,

    /** The floating view is vertically centred on the anchor, overlapping it. */
    CENTER,

    /** The floating view sits directly below the anchor, top edge on the anchor's bottom edge. */
    BOTTOM,
}
