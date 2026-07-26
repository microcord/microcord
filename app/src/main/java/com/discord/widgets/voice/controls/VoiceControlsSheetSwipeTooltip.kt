package com.discord.widgets.voice.controls

import com.discord.tooltips.TooltipManager

/**
 * First-run coach mark telling the user they can swipe the fullscreen call's control sheet up for
 * the secondary controls.
 */
object VoiceControlsSheetSwipeTooltip : TooltipManager.Tooltip(
    cacheKey = "VOICE_CONTROLS_SHEET_SWIPE_ACKNOWLEDGED",
    tooltipName = "VOICE_CONTROLS_SHEET_SWIPE",
)
