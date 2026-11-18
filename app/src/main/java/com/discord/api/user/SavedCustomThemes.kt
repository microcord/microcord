package com.discord.api.user

import com.discord.api.message.SharedClientTheme

/** Response from `GET users/@me/custom-themes`: the Nitro custom themes saved to the account, newest first. */
data class SavedCustomThemes(
    val customThemes: List<SharedClientTheme>? = null,
)
