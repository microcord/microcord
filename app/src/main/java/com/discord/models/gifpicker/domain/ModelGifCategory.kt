package com.discord.models.gifpicker.domain

import java.io.Serializable

/** A GIF picker category tile: its search term plus the image shown on the tile. */
data class ModelGifCategory(
    val categoryName: String,
    val gifPreviewUrl: String,
) : Serializable
