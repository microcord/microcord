package com.discord.media_picker

/**
 * The picker flows a screen can start.
 *
 * Each one owns a `startActivityForResult` request code, which is how a result finds its way back to
 * [MediaPicker.handleActivityResult] — a picker result arrives on the hosting screen, not on the
 * picker, so the request code is the only channel available.
 *
 * The codes are fixed values rather than an ordinal-derived sequence because they leak into
 * `onActivityResult` of every screen in the app and must never shift when a constant is added.
 */
enum class RequestType {
    /** Direct camera capture; the result arrives through the stashed capture URI, not the intent. */
    CAMERA,

    /** System gallery picker. */
    GALLERY,

    /** Storage Access Framework document picker. */
    DOCUMENTS,

    /** uCrop, launched on an image that has already been chosen. */
    CROP,

    /** Combined chooser offering the camera alongside every gallery and document source. */
    CHOOSER,
    ;

    /** `startActivityForResult` request code for this flow. */
    internal val code: Int
        get() = when (this) {
            CAMERA -> 777
            GALLERY -> 778
            DOCUMENTS -> 779
            CROP -> 800
            CHOOSER -> 801
        }

    companion object {
        /** The flow [code] belongs to, or `null` when the code is not one of the picker's. */
        internal fun fromCode(code: Int): RequestType? = when (code) {
            777 -> CAMERA
            778 -> GALLERY
            779 -> DOCUMENTS
            800 -> CROP
            801 -> CHOOSER
            else -> null
        }
    }
}
