package com.discord.api.bugreport

/** One product area a bug report can be filed against. */
data class Feature(
    val name: String,
    val squad: String,
    val asanaInboxId: Long?,
)

/** The remotely configured list of features offered by the in-app bug reporter. */
data class BugReportConfig(
    val features: List<Feature>,
)
