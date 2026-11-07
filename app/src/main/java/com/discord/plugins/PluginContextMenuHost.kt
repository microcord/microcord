package com.discord.plugins

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.discord.R
import com.discord.utilities.color.ColorCompat
import dev.mantikafasi.manticord.plugins.api.PluginContextMenuContext

object PluginContextMenuHost {
    fun bind(container: ViewGroup, context: PluginContextMenuContext, dismiss: () -> Unit, modern: Boolean = false) {
        bind(container, listOf(context), dismiss, modern)
    }

    /** [modern] styles the rows like the modern message menu's, with an icon slot even when a plugin gives none. */
    fun bind(container: ViewGroup, contexts: List<PluginContextMenuContext>, dismiss: () -> Unit, modern: Boolean = false) {
        container.removeAllViews()
        PluginUiRegistry.contextMenuItems().forEach { owned ->
            val item = owned.contribution
            val context = contexts.firstOrNull { candidate ->
                candidate.surface in item.surfaces && runCatching { item.isVisible(candidate) }.getOrDefault(false)
            } ?: return@forEach
            val row = LayoutInflater.from(container.context).inflate(
                if (modern) R.layout.plugin_context_menu_item_modern else R.layout.plugin_context_menu_item,
                container,
                false,
            ) as TextView
            row.text = item.title
            val icon = runCatching { item.createIcon?.invoke(container.context) }.getOrNull()
            if (icon == null && modern) {
                val size = (24 * container.resources.displayMetrics.density).toInt()
                row.setCompoundDrawablesRelative(ColorDrawable(Color.TRANSPARENT).apply { setBounds(0, 0, size, size) }, null, null, null)
            }
            icon?.let { icon ->
                val tinted = DrawableCompat.wrap(icon).mutate()
                DrawableCompat.setTint(
                    tinted,
                    if (item.destructive) ContextCompat.getColor(container.context, R.color.status_red)
                    else ColorCompat.getThemedColor(container.context, R.attr.colorInteractiveNormal),
                )
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted, null, null, null)
            }
            if (item.destructive) row.setTextColor(
                ColorStateList.valueOf(ContextCompat.getColor(container.context, R.color.status_red)),
            )
            row.setOnClickListener {
                runCatching { item.onClick(context) }
                dismiss()
            }
            container.addView(row)
        }
        container.visibility = if (container.childCount == 0) View.GONE else View.VISIBLE
    }
}
