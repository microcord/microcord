package com.discord.widgets.directories

import android.widget.TextView
import com.discord.i18n.setI18nText

/**
 * A "Newest"/"All Servers" divider on the directory home page.
 * `directory_section_header_list_item.xml` is a bare `TextView`.
 */
class SectionHeaderViewHolder(
    private val sectionText: TextView,
    adapter: WidgetDirectoryChannelAdapter,
) : DirectoryChannelViewHolder(sectionText, adapter) {

    override fun bind(item: DirectoryChannelItem, listener: DirectoryChannelItemClickInterface) {
        val header = item as? DirectoryChannelItem.SectionHeader ?: return
        sectionText.setI18nText(header.textRes)
    }
}
