package com.discord.widgets.botuikit.views

import com.discord.api.botuikit.ButtonStyle
import com.discord.models.botuikit.ButtonMessageComponent

fun ButtonMessageComponent.hasEmoji(): Boolean = emoji != null

fun ButtonMessageComponent.hasIcon(): Boolean = style == ButtonStyle.LINK
