package com.discord.widgets.user

import androidx.annotation.DrawableRes

data class Badge(
    @DrawableRes val icon: Int = 0,
    val text: CharSequence? = null,
    val tooltip: CharSequence? = null,
    val showPremiumUpSell: Boolean = false,
    val objectType: String? = null,
)
