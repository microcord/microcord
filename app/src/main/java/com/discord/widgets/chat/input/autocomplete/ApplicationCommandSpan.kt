package com.discord.widgets.chat.input.autocomplete

import com.discord.models.commands.ApplicationCommandOption
import com.discord.utilities.spans.SimpleRoundedBackgroundSpan

class ApplicationCommandSpan(
    val commandOption: ApplicationCommandOption,
    val commandValue: String?,
    startIndex: Int,
    endIndex: Int,
    edgeHorizontalPadding: Int,
    edgeHorizontalMargin: Int,
    backgroundColor: Int,
    cornerRadius: Float,
    textColor: Int? = null,
    isTrimEnabled: Boolean = true,
    transformSpannedText: ((String?) -> String?)? = null,
) : SimpleRoundedBackgroundSpan(
    startIndex,
    endIndex,
    edgeHorizontalPadding,
    edgeHorizontalMargin,
    backgroundColor,
    cornerRadius,
    textColor,
    isTrimEnabled,
    transformSpannedText,
)
