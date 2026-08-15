package com.discord.plugins

import android.view.View
import android.view.ViewGroup
import dev.mantikafasi.manticord.plugins.api.PluginMessageContext
import dev.mantikafasi.manticord.plugins.api.PluginMessagePlacement
import com.discord.utilities.feature.PluginSurfaceCustomization
import com.discord.utilities.feature.PluginSurfaceCustomization.Surface

class PluginMessageHost {
    private val views = HashMap<String, View>()

    fun bind(
        header: ViewGroup?,
        content: ViewGroup,
        standalone: ViewGroup,
        context: PluginMessageContext,
    ) {
        val registeredKeys = PluginUiRegistry.messages().mapTo(HashSet()) { it.key }
        views.keys.removeAll { it !in registeredKeys }
        bind(header, PluginMessagePlacement.HEADER, context)
        bind(content, PluginMessagePlacement.CONTENT, context)
        bind(standalone, PluginMessagePlacement.STANDALONE, context)
    }

    private fun bind(
        container: ViewGroup?,
        placement: PluginMessagePlacement,
        context: PluginMessageContext,
    ) {
        if (container == null) return
        val registered = PluginUiRegistry.messages().filter { it.contribution.placement == placement }
        val byKey = registered.associateBy {
            PluginSurfaceCustomization.pluginKey(it.owner, it.contribution.id)
        }
        val surface = when (placement) {
            PluginMessagePlacement.HEADER -> Surface.MESSAGE_HEADER
            PluginMessagePlacement.CONTENT -> Surface.MESSAGE_CONTENT
            PluginMessagePlacement.STANDALONE -> Surface.MESSAGE_STANDALONE
        }
        val desired = PluginSurfaceCustomization.get(surface, byKey.keys.toList())
            .filter(PluginSurfaceCustomization.Entry::visible)
            .mapNotNull { byKey[it.key] }
            .mapNotNull { owned ->
            val contribution = owned.contribution
            if (!runCatching { contribution.isVisible(context) }.getOrDefault(false)) return@mapNotNull null
            val view = views[owned.key] ?: runCatching { contribution.createView(container.context) }
                .getOrNull()
                ?.also { views[owned.key] = it }
                ?: return@mapNotNull null
            runCatching { contribution.bind(view, context) }
            view
            }
        val current = (0 until container.childCount).map(container::getChildAt)
        if (current != desired) {
            container.removeAllViews()
            desired.forEach { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                container.addView(view)
            }
        }
        container.visibility = if (desired.isEmpty()) View.GONE else View.VISIBLE
    }
}
