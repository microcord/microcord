@file:JvmName("PremiumUtilsKt")

package com.discord.utilities.premium

import com.discord.api.premium.PremiumTier

/**
 * Entitlement predicates over [PremiumTier].
 *
 * Deliberately *not* rank comparisons. `TIER_0` (Nitro Basic) sits below `TIER_1` (Nitro Classic)
 * in the enum, yet grants premium stickers while `TIER_1` does not — the tiers are a product
 * matrix, not a ladder, so each entitlement is spelled out by membership.
 */

/** Custom `#tag` discriminator and animated avatars: Nitro Classic and Nitro only. */
fun PremiumTier.grantsAccessToCustomTagAndAnimatedAvatarFeatures(): Boolean =
    this == PremiumTier.TIER_1 || this == PremiumTier.TIER_2

/** Premium sticker packs: Nitro and Nitro Basic — Nitro Classic is excluded. */
fun PremiumTier.grantsAccessToPremiumStickers(): Boolean =
    this == PremiumTier.TIER_2 || this == PremiumTier.TIER_0
