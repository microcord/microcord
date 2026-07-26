package com.discord.api.report

/**
 * The body posted at the end of a reporting flow.
 *
 * [id] identifies the reported entity; exactly one of the other id fields names the context it
 * was reported from. [breadcrumbs] is the path of nodes the user walked and [elements] the data
 * they selected along the way — both are derived from the visited [NodeResult]s by [from].
 */
data class ReportSubmissionBody(
    val id: Long,
    val messageId: Long? = null,
    val channelId: Long? = null,
    val guildId: Long? = null,
    val hubId: Long? = null,
    val guildScheduledEventId: Long? = null,
    val language: String,
    val variant: String,
    val name: String,
    val version: String,
    val breadcrumbs: List<ReportNodeRef>,
    val elements: Map<String, List<String>>,
) {
    companion object {
        /**
         * Reduces the user's path through the flow into the breadcrumb list and element map the
         * server expects. Nodes the user passed through without choosing a destination, and nodes
         * with no element selection, contribute nothing.
         */
        fun from(results: List<NodeResult>): Pair<List<ReportNodeRef>, Map<String, List<String>>> {
            val breadcrumbs = results.mapNotNull { it.destination?.ref }
            val elements = results
                .mapNotNull { result ->
                    result.elementResult?.let { it.key to it.data.map(ReportNodeElementData::elementKey) }
                }
                .toMap()
            return breadcrumbs to elements
        }
    }
}
