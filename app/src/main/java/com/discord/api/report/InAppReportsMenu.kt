package com.discord.api.report

import com.google.gson.annotations.JsonAdapter

/** Index of a [ReportNode] inside [MenuAPIResponse.nodes]. */
typealias ReportNodeRef = Int

/** A URL sent inside report menu copy. */
typealias UrlRef = String

/**
 * The whole in-app reporting flow, downloaded as one document.
 *
 * The flow is a graph: [rootNodeId] is the entry point, each node's children point at the next
 * node by [ReportNodeChild.ref], and [successNodeId] terminates it. Wire names are snake_case
 * here because this document is fetched with an identity field-naming Gson, unlike the rest of
 * the API surface.
 */
data class MenuAPIResponse(
    val name: String,
    val variant: String,
    val version: String,
    val language: String?,
    val successNodeId: ReportNodeRef,
    val rootNodeId: ReportNodeRef,
    val postbackUrl: UrlRef,
    val nodes: Map<ReportNodeRef, ReportNode>,
)

/** One screen of the reporting flow. */
data class ReportNode(
    val id: ReportNodeRef,
    val menuName: String?,
    val header: String,
    val subheader: String?,
    val info: String?,
    val description: String?,
    val children: List<ReportNodeChild>,
    val elements: List<ReportNodeElement>,
    val button: ReportNodeBottomButton?,
)

/** A link from one [ReportNode] to the next. */
data class ReportNodeChild(
    val name: String,
    val ref: ReportNodeRef,
)

/** An interactive block inside a [ReportNode], such as a message preview or a checkbox list. */
data class ReportNodeElement(
    val type: String,
    val name: String,
    @field:JsonAdapter(ReportNodeElementDataListTypeAdapter::class)
    val data: List<ReportNodeElementData>?,
    val shouldSubmitData: Boolean,
)

/** One key/value row of a [ReportNodeElement]; only [elementKey] is submitted. */
data class ReportNodeElementData(
    val elementKey: String,
    val elementValue: String,
)

/** The bottom action of a [ReportNode]. */
sealed class ReportNodeBottomButton(val type: String) {
    object Cancel : ReportNodeBottomButton("cancel")
    object Done : ReportNodeBottomButton("done")
    object Submit : ReportNodeBottomButton("submit")

    /** Advances to the node at [target]. */
    data class Next(val target: ReportNodeRef) : ReportNodeBottomButton("next")
}

/** What the user selected on one visited [ReportNode]. */
data class NodeElementResult(
    val key: String,
    val data: List<ReportNodeElementData>,
)

/** One step of the user's path through the flow, used to build the submission body. */
data class NodeResult(
    val node: ReportNode,
    val destination: ReportNodeChild?,
    val elementResult: NodeElementResult?,
)
