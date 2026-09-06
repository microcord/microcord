package com.discord.utilities.updates

import com.google.gson.annotations.SerializedName

data class AppUpdateChanges(
    val release: String?,
    val fromSha: String?,
    val toSha: String?,
    val notes: String?,
    val notesTruncated: Boolean?,
    val commits: List<Commit>?,
    val comparison: Comparison?,
    val totalCommits: Long?,
    val commitsTruncated: Boolean?,
) {
    data class Commit(val sha: String?, val title: String?)

    enum class Comparison {
        @SerializedName("compared") COMPARED,
        @SerializedName("identical") IDENTICAL,
        @SerializedName("unavailable") UNAVAILABLE,
    }

    fun validate(update: AppUpdate, installedSha: String): AppUpdateChanges = apply {
        require(release == update.release && fromSha == installedSha && toSha == update.commitSha)
        require(notes != null && notes.length <= 4_000 && notesTruncated != null)
        require(commits != null && commits.size <= 100 && commitsTruncated != null)
        commits.forEach { commit ->
            require(commit.sha != null && Regex("[0-9a-f]{40}").matches(commit.sha))
            require(commit.title != null && commit.title.length <= 200 &&
                commit.title.none { it in "\r\n\u000b\u000c\u0085\u2028\u2029" })
        }
        when (comparison) {
            Comparison.COMPARED -> {
                require(toSha != null && fromSha != toSha && commits.isNotEmpty())
                require(totalCommits != null && totalCommits >= commits.size)
                require(commitsTruncated == (totalCommits > commits.size))
            }
            Comparison.IDENTICAL -> require(toSha != null && fromSha == toSha &&
                commits.isEmpty() && totalCommits == 0L && !commitsTruncated)
            Comparison.UNAVAILABLE -> require(commits.isEmpty() && totalCommits == null && !commitsTruncated)
            null -> error("Unknown update comparison")
        }
    }
}
