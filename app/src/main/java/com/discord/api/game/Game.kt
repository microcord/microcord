package com.discord.api.game

import com.discord.api.application.ThirdPartySku

data class Game(
    val id: Long,
    val name: String,
    val description: String? = null,
    val aliases: List<String>? = null,
    val iconHash: String? = null,
    val executables: List<GameExecutable>? = null,
    val thirdPartySkus: List<ThirdPartySku>? = null,
    val overlay: Boolean? = null,
    val overlayWarn: Boolean? = null,
    val overlayCompatibilityHook: Boolean? = null,
    val overlayMethods: Int? = null,
    val hook: Boolean? = null,
    val themes: List<String>? = null,
    val linkedApplications: List<GameLinkedApplication>? = null,
    val media: GameMedia? = null,
    val firstReleaseDate: String? = null,
    val genres: List<Int>? = null,
    val platforms: List<Int>? = null,
    val coverImageHash: String? = null,
    val bannerHash: String? = null,
    val screenshotHashes: List<String>? = null,
    val screenshotUrls: List<String>? = null,
    val trailers: List<GameTrailer>? = null,
    val websites: List<GameWebsite>? = null,
    val companies: List<GameCompany>? = null,
    val shopCollectionIds: List<Long>? = null,
    val l30Rank: Int? = null,
    val gameFlags: Int? = null,
    val contentClassification: GameContentClassification? = null,
    val summaryLocalized: Map<String, String>? = null,
    val reviews: GameReviews? = null,
    val opencriticUrl: String? = null,
    val steamReleaseStatus: String? = null,
) {
    fun cover() = media?.cover ?: coverImageHash?.let { GameMediaReference(TYPE_HASH, it) }

    companion object {
        const val TYPE_HASH = "hash"
        const val TYPE_URL = "url"
    }
}

data class GameExecutable(val os: String, val name: String, val isLauncher: Boolean? = null)
data class GameLinkedApplication(val id: Long, val type: Int)
data class GameMedia(
    val coverHash: String? = null,
    val artworkHashes: List<String>? = null,
    val icon: GameMediaReference? = null,
    val banner: GameMediaReference? = null,
    val cover: GameMediaReference? = null,
    val artwork: List<GameMediaReference>? = null,
)
data class GameMediaReference(val type: String, val value: String)
data class GameTrailer(
    val id: Long,
    val applicationId: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val size: Long,
)
data class GameWebsite(val url: String, val category: Int)
data class GameCompany(val name: String, val roles: List<Int>? = null)
data class GameContentClassification(val agencyRatings: Map<String, GameAgencyRating>? = null)
data class GameAgencyRating(val rating: Int, val descriptors: List<Int>? = null)
data class GameReviews(val steam: GameSteamReview? = null, val opencritic: GameOpencriticReview? = null)
data class GameSteamReview(
    val rating: Int? = null,
    val ratingCount: Long? = null,
    val recentRating: Int? = null,
    val recentRatingCount: Long? = null,
    val localizedRating: Int? = null,
    val localizedRatingCount: Long? = null,
)
data class GameOpencriticReview(
    val topCriticRating: Double? = null,
    val topCriticRatingCount: Long? = null,
    val tier: String? = null,
)
