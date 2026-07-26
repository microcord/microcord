package com.discord.models.sticker.dto

import com.discord.api.sticker.Sticker
import com.discord.models.domain.ModelSku
import com.discord.models.store.dto.ModelStoreAsset
import com.discord.models.store.dto.ModelStoreDirectoryLayout
import com.discord.utilities.time.TimeUtils

/**
 * A purchasable sticker pack.
 *
 * [storeListing] is absent for packs the user already owns or that are not sold individually, which
 * is what [canBePurchased] and [isLimitedPack] key off.
 */
data class ModelStickerPack(
    val id: Long,
    val stickers: List<Sticker>,
    val name: String,
    val storeListing: ModelStickerPackStoreListing?,
    val skuId: Long,
    val coverStickerId: Long?,
    val description: String?,
    val bannerAssetId: Long?,
) {
    /** The sticker shown on the pack tile; falls back to the first sticker in the pack. */
    val coverSticker: Sticker
        get() = stickers.firstOrNull { it.id == coverStickerId } ?: stickers[0]

    fun canBePurchased(): Boolean = storeListing?.sku?.isAvailable() == true

    fun isPremiumPack(): Boolean = storeListing?.sku?.premium ?: false

    /** A pack that will stop being sold; [ModelStickerPackStoreListing.unpublishedAt] says when. */
    fun isLimitedPack(): Boolean = storeListing?.unpublishedAt != null

    fun isAnimatedPack(): Boolean = stickers.any { it.isAnimated() }
}

data class ModelStickerPackStoreListing(
    val sku: ModelSku,
    val id: Long,
    val description: String,
    val unpublishedAt: String?,
    val thumbnail: ModelStoreAsset?,
) {
    val unpublishedAtDate: Long get() = TimeUtils.parseUTCDate(unpublishedAt)
}

/** The sticker shop front page: the packs plus the order they should be shown in. */
data class ModelStickerStoreDirectory(
    val stickerPacks: List<ModelStickerPack>,
    val storeDirectoryLayout: ModelStoreDirectoryLayout?,
)
