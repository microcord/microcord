package com.discord.plugins

import dev.mantikafasi.manticord.plugins.api.PluginLoadMode
import dev.mantikafasi.manticord.plugins.api.PluginManifest
import java.util.PriorityQueue
import com.discord.BuildConfig

internal data class PluginCandidate(
    val manifest: PluginManifest,
    val enabled: Boolean = true,
)

internal data class ResolvedPlugin(
    val manifest: PluginManifest,
    val loadMode: PluginLoadMode,
)

internal data class PluginLoadPlan(
    val plugins: List<ResolvedPlugin>,
    val blocked: Map<String, PluginBlockReason>,
)

internal sealed class PluginBlockReason {
    data object Disabled : PluginBlockReason()
    data object DuplicateId : PluginBlockReason()
    data class IncompatibleManticord(val current: Int) : PluginBlockReason()
    data class MissingRequirement(val pluginId: String) : PluginBlockReason()
    data class IncompatibleRequirement(val pluginId: String, val versionCode: Int) : PluginBlockReason()
    data class RequirementBlocked(val pluginId: String) : PluginBlockReason()
    data object DependencyCycle : PluginBlockReason()
}

internal object PluginDependencyResolver {
    /** `PriorityQueue`'s own default; the constructor taking only a comparator is API 24+. */
    private const val INITIAL_QUEUE_CAPACITY = 11

    fun resolve(
        candidates: List<PluginCandidate>,
        manticordVersion: Int = BuildConfig.VERSION_CODE,
    ): PluginLoadPlan {
        val groups = candidates.groupBy { it.manifest.id }
        val nodes = groups.mapNotNull { (id, matches) ->
            id.takeIf { matches.size == 1 }?.let { it to matches.single() }
        }.toMap()
        val blocked = sortedMapOf<String, PluginBlockReason>()

        groups.filterValues { it.size > 1 }.keys.forEach { blocked[it] = PluginBlockReason.DuplicateId }
        nodes.forEach { (id, candidate) ->
            when {
                !candidate.enabled -> blocked[id] = PluginBlockReason.Disabled
                manticordVersion < candidate.manifest.manticordVersionMinimum ||
                    (candidate.manifest.manticordVersionMaximumExclusive != null &&
                        manticordVersion >= requireNotNull(candidate.manifest.manticordVersionMaximumExclusive)) ->
                    blocked[id] = PluginBlockReason.IncompatibleManticord(manticordVersion)
            }
        }

        nodes.forEach { (id, candidate) ->
            if (id in blocked) return@forEach
            for (requirement in candidate.manifest.requirements) {
                val dependency = nodes[requirement.pluginId]
                if (dependency == null) {
                    blocked[id] = if (requirement.pluginId in groups) {
                        PluginBlockReason.RequirementBlocked(requirement.pluginId)
                    } else {
                        PluginBlockReason.MissingRequirement(requirement.pluginId)
                    }
                    break
                }
                if (!requirement.accepts(dependency.manifest.versionCode)) {
                    blocked[id] = PluginBlockReason.IncompatibleRequirement(
                        requirement.pluginId,
                        dependency.manifest.versionCode,
                    )
                    break
                }
            }
        }

        propagateBlockedRequirements(nodes, blocked)
        findCycleMembers(nodes, blocked).forEach { blocked[it] = PluginBlockReason.DependencyCycle }
        propagateBlockedRequirements(nodes, blocked)

        val active = nodes.filterKeys { it !in blocked }
        val dependents = active.keys.associateWith { mutableListOf<String>() }
        val remainingRequirements = active.mapValues { (_, candidate) ->
            candidate.manifest.requirements.count { it.pluginId in active }
        }.toMutableMap()
        active.forEach { (id, candidate) ->
            candidate.manifest.requirements.forEach { requirement ->
                dependents[requirement.pluginId]?.add(id)
            }
        }

        // The capacity form: PriorityQueue(Comparator) only exists from Android 7 (API 24), and minSdk is 23.
        val ready = PriorityQueue<String>(INITIAL_QUEUE_CAPACITY) { left, right ->
            val priority = active.getValue(right).manifest.priority.compareTo(active.getValue(left).manifest.priority)
            if (priority != 0) priority else left.compareTo(right)
        }
        remainingRequirements.filterValues { it == 0 }.keys.forEach(ready::add)

        val orderedIds = buildList(active.size) {
            while (ready.isNotEmpty()) {
                val id = ready.remove()
                add(id)
                dependents.getValue(id).forEach { dependent ->
                    val remaining = remainingRequirements.getValue(dependent) - 1
                    remainingRequirements[dependent] = remaining
                    if (remaining == 0) ready.add(dependent)
                }
            }
        }

        val blocking = orderedIds.filterTo(mutableSetOf()) {
            active.getValue(it).manifest.loadMode == PluginLoadMode.BLOCKING
        }
        fun promoteRequirements(id: String) {
            active.getValue(id).manifest.requirements.forEach { requirement ->
                if (blocking.add(requirement.pluginId)) promoteRequirements(requirement.pluginId)
            }
        }
        blocking.toList().forEach(::promoteRequirements)

        return PluginLoadPlan(
            plugins = orderedIds.map { id ->
                ResolvedPlugin(
                    manifest = active.getValue(id).manifest,
                    loadMode = if (id in blocking) PluginLoadMode.BLOCKING else PluginLoadMode.ASYNC,
                )
            },
            blocked = blocked,
        )
    }

    private fun propagateBlockedRequirements(
        nodes: Map<String, PluginCandidate>,
        blocked: MutableMap<String, PluginBlockReason>,
    ) {
        var changed: Boolean
        do {
            changed = false
            nodes.forEach { (id, candidate) ->
                if (id in blocked) return@forEach
                val requirement = candidate.manifest.requirements.firstOrNull { it.pluginId in blocked }
                    ?: return@forEach
                blocked[id] = PluginBlockReason.RequirementBlocked(requirement.pluginId)
                changed = true
            }
        } while (changed)
    }

    private fun findCycleMembers(
        nodes: Map<String, PluginCandidate>,
        blocked: Map<String, PluginBlockReason>,
    ): Set<String> {
        val state = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()
        val cycles = mutableSetOf<String>()

        fun visit(id: String) {
            state[id] = 1
            stack += id
            nodes.getValue(id).manifest.requirements.forEach { requirement ->
                val dependency = requirement.pluginId
                if (dependency in blocked || dependency !in nodes) return@forEach
                when (state[dependency]) {
                    null -> visit(dependency)
                    1 -> cycles += stack.subList(stack.indexOf(dependency), stack.size)
                }
            }
            stack.removeAt(stack.lastIndex)
            state[id] = 2
        }

        nodes.keys.filter { it !in blocked }.forEach { id ->
            if (state[id] == null) visit(id)
        }
        return cycles
    }
}
