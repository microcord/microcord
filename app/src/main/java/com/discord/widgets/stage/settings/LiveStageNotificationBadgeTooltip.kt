package com.discord.widgets.stage.settings

import com.discord.tooltips.TooltipManager

/**
 * One-time coach mark pointing at the guild-settings toggle for live-stage notifications.
 *
 * Acknowledging it is persisted, so it shows at most once per install.
 */
object LiveStageNotificationBadgeTooltip : TooltipManager.Tooltip(
    cacheKey = "CACHE_KEY_LIVE_STAGE_NOTIFICATION_BADGE",
    tooltipName = "LIVE_STAGE_NOTIFICATION_BADGE",
)
