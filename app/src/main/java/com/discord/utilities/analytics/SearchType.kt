package com.discord.utilities.analytics

/** Which picker a `search_*` analytics event came from. */
enum class SearchType {
    GIF,
    EMOJI,
}

/** `chat_input_component_viewed` values — one per attachment/expression entry point. */
object ChatInputComponentTypes {
    const val CAMERA = "camera"
    const val EMOJI = "emoji"
    const val EMOJI_SEARCH = "emoji search"
    const val FILES = "files"
    const val GIF = "GIF"
    const val GIF_SEARCH = "gif search"
    const val MEDIA_PICKER = "media picker"
    const val STICKER = "sticker"
    const val STICKER_SEARCH = "sticker search"
}
