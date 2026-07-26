package com.discord.widgets.voice.controls

import com.discord.tooltips.TooltipManager

/**
 * The one-time sparkle drawing attention to the screen-share button.
 *
 * Acknowledged permanently once tapped, so it appears on exactly one cold start per install.
 */
object ScreenShareButtonSparkleTooltip : TooltipManager.Tooltip(
    cacheKey = "CACHE_KEY_SCREEN_SHARE_BUTTON_SPARKLE_ACKNOWLEDGED",
    tooltipName = "SCREEN_SHARE_BUTTON_SPARKLE",
)
