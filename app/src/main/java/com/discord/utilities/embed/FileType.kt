package com.discord.utilities.embed

import androidx.annotation.DrawableRes
import com.discord.R
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Attachment kinds that get their own icon on a non-previewable file card.
 *
 * **Declaration order is the matching order and it is load-bearing.** Several extensions match more
 * than one pattern, and the first match wins: `js` is [CODE] before it is [WEBCODE], and `xls` is a
 * [SPREADSHEET] before it is [WEBCODE]. Reordering these constants silently changes which icon a
 * file gets.
 *
 * Matching is `find()`, not `matches()`, against patterns anchored with `$` — so the input may be
 * either a bare extension or a whole filename.
 */
enum class FileType(pattern: String) {
    VIDEO("(?:avi|flv|wmv|mov|mp4)$"),
    ACROBAT("pdf$"),
    AE("ae$"),
    SKETCH("sketch$"),
    AI("ai$"),
    ARCHIVE("(?:rar|zip|7z|tar|tar\\.gz)$"),
    CODE(
        "(?:c\\+\\+|cpp|cc|c|h|hpp|mm|m|json|js|rb|rake|py|asm|fs|pyc|dtd|cgi|bat|rss|" +
            "java|graphml|idb|lua|o|gml|prl|sls|conf|cmake|make|sln|vbe|cxx|wbf|vbs|" +
            "r|wml|php|bash|applescript|fcgi|yaml|ex|exs|sh|ml|actionscript)$"
    ),
    DOCUMENT("(?:txt|rtf|doc|docx|md|pages|ppt|pptx|pptm|key|log)$"),
    SPREADSHEET("(?:xls|xlsx|numbers|csv|xliff)$"),
    WEBCODE("(?:html|xhtml|htm|js|xml|xls|xsd|css|styl)$"),
    ;

    private val compiled: Pattern = Pattern.compile(pattern)

    /**
     * One `Matcher` per thread per constant.
     *
     * `Matcher` is stateful and not thread-safe, but allocating one per call on a chat list bind is
     * wasteful, so it is reset instead — and confined to the calling thread so a background
     * pre-render cannot corrupt the UI thread's match.
     */
    private val threadLocalMatcher = object : ThreadLocal<Matcher>() {
        override fun initialValue(): Matcher = compiled.matcher("")
    }

    private fun matches(extension: String): Boolean =
        threadLocalMatcher.get()!!.reset(extension).find()

    @get:DrawableRes
    val fileDrawable: Int
        get() = when (this) {
            VIDEO -> R.drawable.ic_file_video
            ACROBAT -> R.drawable.ic_file_acrobat
            AE -> R.drawable.ic_file_ae
            SKETCH -> R.drawable.ic_file_sketch
            AI -> R.drawable.ic_file_ai
            ARCHIVE -> R.drawable.ic_file_archive
            CODE -> R.drawable.ic_file_code
            DOCUMENT -> R.drawable.ic_file_document
            SPREADSHEET -> R.drawable.ic_file_spreadsheet
            WEBCODE -> R.drawable.ic_file_webcode
        }

    companion object {
        /** First declared type whose pattern matches, or null for an unrecognised extension. */
        @JvmStatic
        fun getFromExtension(extension: String): FileType? = values().firstOrNull {
            it.matches(extension)
        }
    }
}
