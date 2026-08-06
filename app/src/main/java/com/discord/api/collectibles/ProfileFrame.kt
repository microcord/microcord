package com.discord.api.collectibles

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * The artwork of an equipped profile frame, from `GET collectibles-products/{skuId}`.
 *
 * A frame is a stack of images positioned around the profile rather than a single picture. Only
 * [ProfileFrameLayer.TYPE_STAPLE] layers anchored to the top are drawn on a real profile; rails and
 * borders exist for the shop's tall preview card, which this client does not have.
 */
data class ProfileFrameProduct(
    val items: List<ProfileFrameItem> = emptyList(),
) : Serializable {

    fun frame(): ProfileFrameItem? = items.firstOrNull { it.type == CollectibleProduct.TYPE_PROFILE_FRAME }
}

data class ProfileFrameItem(
    val type: Int = CollectibleProduct.TYPE_PROFILE_FRAME,
    @SerializedName(value = "skuId", alternate = ["sku_id"])
    val skuId: Long = 0,
    val label: String? = null,
    val layers: List<ProfileFrameLayer> = emptyList(),
    /** The profile width the overflows below were measured against. */
    @SerializedName(value = "innerWidth", alternate = ["inner_width"])
    val innerWidth: Int = DEFAULT_INNER_WIDTH,
    @SerializedName(value = "overflowTop", alternate = ["overflow_top"])
    val overflowTop: Int = 0,
    @SerializedName(value = "overflowBottom", alternate = ["overflow_bottom"])
    val overflowBottom: Int = 0,
    @SerializedName(value = "overflowHorizontal", alternate = ["overflow_horizontal"])
    val overflowHorizontal: Int = 0,
) : Serializable {

    /** The layers a profile draws, nearest the top first. */
    fun topStaples(): List<ProfileFrameLayer> = layers.filter {
        it.type == ProfileFrameLayer.TYPE_STAPLE && it.anchor == ProfileFrameLayer.ANCHOR_TOP
    }

    companion object {
        const val DEFAULT_INNER_WIDTH = 1200
    }
}

data class ProfileFrameLayer(
    val id: String = "",
    val type: String = TYPE_STAPLE,
    /** Whether the layer sits in front of the profile's content or behind it. */
    val order: String = ORDER_FRONT,
    val anchor: String = ANCHOR_TOP,
    val responsive: Boolean = false,
) : Serializable {

    val isFront: Boolean get() = order == ORDER_FRONT

    /** Each layer is its own asset under the frame's SKU. */
    fun assetUrl(skuId: Long): String = "$CDN/media/v1/collectibles-shop/$skuId/$id/static"

    companion object {
        const val TYPE_STAPLE = "staple"
        const val ANCHOR_TOP = "top"
        const val ORDER_FRONT = "front"
        private const val CDN = "https://cdn.discordapp.com"
    }
}
