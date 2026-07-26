package com.discord.api.report

/** What is being reported; the value is the path segment of the report-menu endpoint. */
enum class ReportType(val pathValue: String) {
    Message("message"),
    StageChannel("stage_channel"),
    DirectoryServer("guild_directory_entry"),
    GuildScheduledEvent("guild_scheduled_event"),
}

/** A single selectable reason on a legacy (non-menu) report screen. */
data class ReportReason(
    val reason: Int,
    val label: String,
    val description: String,
)
