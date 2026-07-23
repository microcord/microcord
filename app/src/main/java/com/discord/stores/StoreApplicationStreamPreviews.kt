package com.discord.stores

object StoreApplicationStreamPreviews {
    sealed interface StreamPreview {
        data object Fetching : StreamPreview
        data class Resolved(val url: String?) : StreamPreview
    }
}
