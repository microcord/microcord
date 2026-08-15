package com.discord.api.applicationdirectory

/**
 * One shelf of the app launcher's curated recommendations, e.g. "Puzzle Game".
 *
 * The surface decides which [type]s the server may return: the in-text launcher only ever gets
 * [TYPE_EXPANDABLE_LIST], [TYPE_BANNER_CARDS] and [TYPE_SMALL_BANNER_CARDS].
 */
data class ApplicationDirectoryCollection(
    val id: String,
    val type: Int,
    val position: Int,
    val title: String,
    val description: String? = null,
    val flags: Int = 0,
    val applicationDirectoryCollectionItems: List<ApplicationDirectoryCollectionItem> = emptyList(),
) {
    companion object {
        const val TYPE_EXPANDABLE_LIST = 4
        const val TYPE_BANNER_CARDS = 5
        const val TYPE_SMALL_BANNER_CARDS = 6

        const val SURFACE_APP_LAUNCHER_IN_TEXT = 2
        const val ACTIVE_STATE_ACTIVE = 1
        const val PLATFORM_ANDROID = 2

        /** Appends the activities that no other collection already listed. */
        const val FLAG_APPENDS_REMAINING_ACTIVITIES = 1
    }
}

data class ApplicationDirectoryCollectionItem(
    val id: String? = null,
    val type: Int,
    val position: Int = 0,
    val flags: Int = 0,
    val imageHash: String? = null,
    val application: ApplicationDirectoryApplication? = null,
) {
    val promoted: Boolean get() = flags and FLAG_PROMOTED != 0

    companion object {
        const val TYPE_APPLICATION = 1
        private const val FLAG_PROMOTED = 1
    }
}
