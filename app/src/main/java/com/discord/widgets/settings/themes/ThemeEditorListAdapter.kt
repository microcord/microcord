package com.discord.widgets.settings.themes

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.databinding.WidgetSettingsThemeEditorColorItemBinding
import com.discord.databinding.WidgetSettingsThemeEditorHeaderBinding
import com.discord.databinding.WidgetSettingsThemeEditorNavItemBinding

/** The theme editor pages' lists: section headers, rows that open a page, and color rows. */
internal class ThemeEditorListAdapter : ListAdapter<ThemeEditorListAdapter.Item, RecyclerView.ViewHolder>(DIFF) {

    sealed class Item {
        abstract val id: String

        data class Header(override val id: String, val title: CharSequence) : Item()

        data class Nav(override val id: String, val label: CharSequence, val detail: CharSequence, val onClick: () -> Unit) : Item()

        /** A color, or with [icon] a drawable shown tinted [color]. */
        data class Color(
            override val id: String,
            val label: CharSequence,
            val color: Int?,
            val detail: CharSequence,
            val overridden: Boolean,
            val icon: (() -> Drawable?)? = null,
            val onClick: () -> Unit,
            val onReset: () -> Unit,
        ) : Item()
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Item.Header -> TYPE_HEADER
        is Item.Nav -> TYPE_NAV
        is Item.Color -> TYPE_COLOR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(WidgetSettingsThemeEditorHeaderBinding.inflate(inflater, parent, false))
            TYPE_NAV -> NavHolder(WidgetSettingsThemeEditorNavItemBinding.inflate(inflater, parent, false))
            else -> ColorHolder(WidgetSettingsThemeEditorColorItemBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.Header -> (holder as HeaderHolder).binding.themeEditorHeader.text = item.title
            is Item.Nav -> (holder as NavHolder).bind(item)
            is Item.Color -> (holder as ColorHolder).bind(item)
        }
    }

    private class HeaderHolder(val binding: WidgetSettingsThemeEditorHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            ViewCompat.setAccessibilityHeading(binding.root, true)
        }
    }

    private class NavHolder(private val binding: WidgetSettingsThemeEditorNavItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Item.Nav) {
            binding.themeEditorNavLabel.text = item.label
            binding.themeEditorNavDetail.text = item.detail
            binding.root.setOnClickListener { item.onClick() }
        }
    }

    private class ColorHolder(private val binding: WidgetSettingsThemeEditorColorItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Item.Color) {
            ThemeColorRows.bind(binding, item.label, item.color, item.detail)
            val icon = item.icon?.invoke()
            binding.themeEditorColorIcon.isVisible = item.icon != null
            binding.themeEditorColorSwatch.isVisible = item.icon == null
            binding.themeEditorColorIcon.setImageDrawable(icon)
            binding.themeEditorColorIcon.imageTintList = item.color?.let(ColorStateList::valueOf)
            binding.themeEditorColorReset.isVisible = item.overridden
            binding.themeEditorColorReset.setOnClickListener { item.onReset() }
            binding.root.setOnClickListener { item.onClick() }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_NAV = 1
        const val TYPE_COLOR = 2

        val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
        }
    }
}
