package com.discord.widgets.media

import com.discord.app.AppViewModel

/**
 * Playback state that has to outlive [WidgetMedia]'s view.
 *
 * The screen rebuilds its player from scratch on every resume and on every configuration change, so
 * without somewhere to park these three values a rotation would restart the video from zero and
 * flash the still cover frame back over an already-playing surface.
 *
 * There is no view state: the reference constructs this with a `null` initial state and never
 * publishes one, so nothing observes it. The fields are plain mutable properties read directly by
 * the fragment.
 */
class WidgetMediaViewModel : AppViewModel<Unit>() {

    /** Whether playback should resume automatically; starts `true` so a video autoplays on entry. */
    var isPlaying: Boolean = true

    /**
     * Whether the still preview is still covering the video surface.
     *
     * It stays up until the player reports its first decoded frame, which is what stops the black
     * gap between the screen opening and the video becoming visible.
     */
    var showCoverFrame: Boolean = true

    /** Where playback had reached, so a resume seeks back instead of restarting. */
    var currentPlayerPositionMs: Long = 0L

    /** Identifies the gallery page those playback fields belong to. */
    var currentMediaUri: String? = null
}
