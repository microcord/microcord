package com.discord.widgets.voice.sheet

import com.discord.tooltips.TooltipManager

/**
 * Shown once, over the voice sheet's noise-cancellation button, to explain what the button does
 * before it starts toggling. [WidgetVoiceBottomSheetViewModel.onNoiseCancellationPressed] uses it
 * as a one-shot gate: the first press opens [WidgetNoiseCancellationBottomSheet] and acknowledges
 * the tooltip, every later press toggles.
 */
object NoiseCancellationTooltip : TooltipManager.Tooltip(
    cacheKey = "CACHE_KEY_NOISE_CANCELLATION_TOOLTIP_ACKNOWLEDGED",
    tooltipName = "NOISE_CANCELLATION_TOOLTIP",
)
