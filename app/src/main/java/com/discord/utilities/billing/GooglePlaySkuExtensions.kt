@file:JvmName("GooglePlaySkuKt")

package com.discord.utilities.billing

val GooglePlaySku.section: GooglePlaySku.Section
    get() = when (type) {
        GooglePlaySku.Type.PREMIUM_TIER_2,
        GooglePlaySku.Type.PREMIUM_TIER_1,
        -> GooglePlaySku.Section.PREMIUM
        GooglePlaySku.Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD,
        GooglePlaySku.Type.PREMIUM_TIER_1_AND_PREMIUM_GUILD,
        -> GooglePlaySku.Section.PREMIUM_AND_PREMIUM_GUILD
        GooglePlaySku.Type.PREMIUM_GUILD -> GooglePlaySku.Section.PREMIUM_GUILD
    }

val GooglePlaySku.isBundledSku: Boolean
    get() = type == GooglePlaySku.Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD ||
        type == GooglePlaySku.Type.PREMIUM_TIER_1_AND_PREMIUM_GUILD

val GooglePlaySku.isTier1: Boolean
    get() = type == GooglePlaySku.Type.PREMIUM_TIER_1 ||
        type == GooglePlaySku.Type.PREMIUM_TIER_1_AND_PREMIUM_GUILD

val GooglePlaySku.isTier2: Boolean
    get() = type == GooglePlaySku.Type.PREMIUM_TIER_2 ||
        type == GooglePlaySku.Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD
