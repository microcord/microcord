package com.discord.plugins

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.mantikafasi.manticord.plugins.api.PluginMessageContext
import dev.mantikafasi.manticord.plugins.api.PluginMessagePlacement
import dev.mantikafasi.manticord.plugins.api.PluginMessageViews
import com.discord.utilities.feature.PluginSurfaceCustomization
import com.discord.utilities.feature.PluginSurfaceCustomization.Surface

class PluginMessageHost {
    private val views = HashMap<String, View>()
    private var pristine: PristineViews? = null

    /** Undoes the last decorators' changes. Call before the row's native bind, which then wins. */
    fun restoreDecorations() {
        pristine?.restore()
    }

    /** Runs the registered decorators; call after the row's native bind. Free when none are registered. */
    fun decorate(views: PluginMessageViews, context: PluginMessageContext) {
        val decorators = PluginUiRegistry.messageDecorators()
        if (decorators.isEmpty()) return
        if (pristine == null) pristine = PristineViews(views)
        decorators.forEach { owned -> runCatching { owned.contribution.decorate(views, context) } }
    }

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

/** What decorators may change, as the row first looked; see [PluginMessageViews]. */
private class PristineViews(views: PluginMessageViews) {
    private val root = views.root
    private val rootAlpha = root.alpha
    private val rootBackground = root.background
    private val texts = listOfNotNull(views.content, views.author, views.timestamp).map(::TextState)

    fun restore() {
        root.alpha = rootAlpha
        if (root.background !== rootBackground) root.background = rootBackground
        texts.forEach(TextState::restore)
    }

    private class TextState(private val view: TextView) {
        private val alpha = view.alpha
        private val colors = view.textColors
        private val paintFlags = view.paintFlags

        fun restore() {
            view.alpha = alpha
            if (view.textColors !== colors) view.setTextColor(colors)
            view.paintFlags = paintFlags
        }
    }
}

