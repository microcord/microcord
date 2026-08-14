package com.discord.api.application

import com.google.gson.annotations.SerializedName

/** One entry of the response to resolving an off-Discord image url for an application. */
data class ApplicationExternalAsset(
    val url: String,
    @SerializedName("external_asset_path") val externalAssetPath: String,
)
