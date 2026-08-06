package com.discord.api.collectibles

import com.google.gson.annotations.SerializedName
import com.discord.api.user.AvatarDecorationData
import com.discord.api.user.Nameplate
import com.discord.api.utcdatetime.UtcDateTime
import java.io.Serializable

/**
 * A collectible SKU the user owns, as returned by `GET /users/@me/collectibles-purchases`.
 *
 * [expiresAt], [purchaseType] and [purchasedAt] are only sent on that endpoint; the shop returns the
 * same object without them.
 */
data class CollectibleProduct(
    val skuId: Long,
    val type: Int,
    val name: String = "",
    val summary: String = "",
    val premiumType: Int? = null,
    val items: List<CollectibleItem> = emptyList(),
    val expiresAt: UtcDateTime? = null,
    val purchaseType: Int? = null,
    val purchasedAt: UtcDateTime? = null,
) : Serializable {

    /** `premium_type` is always sent; 0 means the product needs no subscription at all. */
    val isPremium: Boolean
        get() = (premiumType != null && premiumType != PREMIUM_TYPE_NONE) ||
            purchaseType == PURCHASE_TYPE_SUBSCRIPTION

    fun isActive(nowMillis: Long = System.currentTimeMillis()) =
        expiresAt == null || expiresAt.dateTimeMillis > nowMillis

    fun avatarDecoration(): AvatarDecorationData? = item(TYPE_AVATAR_DECORATION)?.let { item ->
        item.asset?.takeIf(String::isNotBlank)?.let { AvatarDecorationData(it, item.skuId) }
    }

    fun nameplate(): Nameplate? = item(TYPE_NAMEPLATE)?.let { item ->
        Nameplate(
            asset = item.asset ?: return null,
            skuId = item.skuId,
            label = item.label.orEmpty(),
            palette = item.palette ?: return null,
        )
    }

    /** Profile effects are referenced by SKU, so only the thumbnail is needed to show one. */
    fun profileEffectThumbnail(): String? = item(TYPE_PROFILE_EFFECT)
        ?.let { it.thumbnailPreviewSrc ?: it.staticFrameSrc }

    /** The purchases response carries a frame's full layer list, so no product fetch is needed. */
    fun profileFrame(): ProfileFrameItem? = item(TYPE_PROFILE_FRAME)?.let { item ->
        ProfileFrameItem(
            skuId = item.skuId,
            label = item.label,
            layers = item.layers,
            innerWidth = item.innerWidth ?: ProfileFrameItem.DEFAULT_INNER_WIDTH,
            overflowTop = item.overflowTop ?: 0,
            overflowBottom = item.overflowBottom ?: 0,
            overflowHorizontal = item.overflowHorizontal ?: 0,
        )
    }

    private fun item(type: Int) = items.firstOrNull { it.type == type }

    companion object {
        const val TYPE_AVATAR_DECORATION = 0
        const val TYPE_PROFILE_EFFECT = 1
        const val TYPE_NAMEPLATE = 2
        const val TYPE_PROFILE_FRAME = 3
        private const val PURCHASE_TYPE_SUBSCRIPTION = 7
        private const val PREMIUM_TYPE_NONE = 0
    }
}

/**
 * Superset of the per-type item shapes. Avatar decorations and nameplates differ only by [palette],
 * and the effect and frame types carry fields this client has no use for.
 */
data class CollectibleItem(
    val type: Int,
    val skuId: Long,
    val asset: String? = null,
    val label: String? = null,
    val palette: String? = null,
    val assets: CollectibleItemAssets? = null,
    // Profile effects arrive camelCase, unlike every other item shape.
    @SerializedName(value = "thumbnailPreviewSrc", alternate = ["thumbnail_preview_src"])
    val thumbnailPreviewSrc: String? = null,
    @SerializedName(value = "staticFrameSrc", alternate = ["static_frame_src"])
    val staticFrameSrc: String? = null,
    val layers: List<ProfileFrameLayer> = emptyList(),
    @SerializedName(value = "innerWidth", alternate = ["inner_width"])
    val innerWidth: Int? = null,
    @SerializedName(value = "overflowTop", alternate = ["overflow_top"])
    val overflowTop: Int? = null,
    @SerializedName(value = "overflowBottom", alternate = ["overflow_bottom"])
    val overflowBottom: Int? = null,
    @SerializedName(value = "overflowHorizontal", alternate = ["overflow_horizontal"])
    val overflowHorizontal: Int? = null,
) : Serializable

data class CollectibleItemAssets(
    val staticImageUrl: String? = null,
    val animatedImageUrl: String? = null,
    val videoUrl: String? = null,
) : Serializable
