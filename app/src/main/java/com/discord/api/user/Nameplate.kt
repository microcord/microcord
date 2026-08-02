package com.discord.api.user

import java.io.Serializable

data class Collectibles(
    val nameplate: Nameplate? = null,
) : Serializable

data class Nameplate(
    val asset: String,
    val skuId: Long,
    val label: String,
    val palette: String,
    val expiresAt: Long? = null,
) : Serializable {
    fun staticImageUrl(): String? = assetUrl("static")

    fun animatedImageUrl(): String? = assetUrl("animated")

    fun imageUrls(animate: Boolean): List<String> = staticImageUrl()?.let { static ->
        if (animate) listOfNotNull(animatedImageUrl(), static) else listOf(static)
    }.orEmpty()

    fun isActive(nowEpochSeconds: Long = System.currentTimeMillis() / 1000) =
        expiresAt == null || expiresAt > nowEpochSeconds

    fun backgroundColorHex(dark: Boolean): String? = PALETTES[palette]?.let { colors ->
        if (dark) colors.first else colors.second
    }

    private fun assetUrl(format: String): String? = skuId.takeIf { it > 0 }
        ?.let { "$CDN/media/v1/collectibles-shop/$it/$format" }

    companion object {
        private const val CDN = "https://cdn.discordapp.com"

        // Discord's nameplate palette fields are darkBackground and lightBackground respectively.
        private val PALETTES = mapOf(
            "crimson" to ("#900007" to "#E7040F"),
            "berry" to ("#893A99" to "#B11FCF"),
            "sky" to ("#0080B7" to "#56CCFF"),
            "teal" to ("#086460" to "#7DEED7"),
            "forest" to ("#2D5401" to "#6AA624"),
            "bubble_gum" to ("#DC3E97" to "#F957B3"),
            "violet" to ("#730BC8" to "#972FED"),
            "cobalt" to ("#0131C2" to "#4278FF"),
            "clover" to ("#047B20" to "#63CD5A"),
            "lemon" to ("#F6CD12" to "#FED400"),
            "white" to ("#FFFFFF" to "#FFFFFF"),
            "black" to ("#000000" to "#000000"),
        )
    }
}
