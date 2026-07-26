package com.discord.models.domain.spotify

/** Spotify album metadata attached to a listening activity. */
data class ModelSpotifyAlbum(
    val albumType: String,
    val id: String,
    val images: List<AlbumImage>,
    val name: String,
) {
    data class AlbumImage(val url: String?)
}

data class ModelSpotifyArtist(
    val id: String,
    val name: String,
)

/** The track behind a Spotify rich-presence activity, fetched from the Spotify Web API. */
data class ModelSpotifyTrack(
    val id: String,
    val name: String,
    val durationMs: Long,
    val album: ModelSpotifyAlbum?,
    val artists: List<ModelSpotifyArtist>,
    val isLocal: Boolean,
)
