package com.discord.models.hubs

import android.content.Context
import androidx.annotation.StringRes
import com.discord.R

/**
 * A tab of the student-hub / HQ server directory.
 *
 * The `HQ*` entries deliberately reuse the same [key] values as the school entries: a directory
 * belongs to exactly one of the two worlds, and [Companion.getCategories] picks the right list, so
 * the keys never collide at runtime.
 *
 * [idealSize] is how many entries the directory tries to show for that category before it stops
 * padding the row; [Int.MAX_VALUE] means "no cap".
 */
enum class DirectoryEntryCategory(
    val key: Int,
    @StringRes val titleRes: Int,
    val idealSize: Int = Int.MAX_VALUE,
) {
    Home(-1, R.string.directory_category_all, 70),
    Uncategorized(0, R.string.uncategorized),
    SchoolClub(1, R.string.directory_category_school_club, 100),
    Class(2, R.string.directory_category_class),
    StudySocial(3, R.string.directory_category_study_social, 50),
    SubjectMajor(4, R.string.directory_category_subject_major),
    Misc(5, R.string.directory_category_miscellaneous),
    HQSocial(1, R.string.directory_category_internal_1),
    HQErgs(2, R.string.directory_category_internal_2),
    HQMisc(3, R.string.directory_category_internal_3),
    HQArchives(5, R.string.directory_category_internal_5);

    fun getTitle(context: Context): String = context.getString(titleRes)

    companion object {
        fun getCategories(isHQ: Boolean): List<DirectoryEntryCategory> =
            if (isHQ) HQ_DIRECTORY_CATEGORIES else HUB_CATEGORIES

        fun findByKey(key: Int, isHQ: Boolean): DirectoryEntryCategory =
            getCategories(isHQ).firstOrNull { it.key == key } ?: Uncategorized
    }
}

internal val HUB_CATEGORIES = listOf(
    DirectoryEntryCategory.SchoolClub,
    DirectoryEntryCategory.Class,
    DirectoryEntryCategory.StudySocial,
    DirectoryEntryCategory.Misc,
)

internal val HQ_DIRECTORY_CATEGORIES = listOf(
    DirectoryEntryCategory.HQSocial,
    DirectoryEntryCategory.HQErgs,
    DirectoryEntryCategory.HQMisc,
    DirectoryEntryCategory.HQArchives,
)
