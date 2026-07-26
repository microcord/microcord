package com.discord.models.store.dto

import java.io.Serializable

/** An uploaded store image (pack thumbnails, banners); [width]/[height] are absent for non-images. */
data class ModelStoreAsset(
    val id: Long,
    val size: Int,
    val mimeType: String,
    val filename: String?,
    val width: Int?,
    val height: Int?,
) : Serializable

/** The ordering the store front page should use, expressed as a flat SKU list. */
data class ModelStoreDirectoryLayout(
    val id: Long,
    val allSkus: List<Long>,
)
