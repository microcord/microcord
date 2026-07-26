package com.discord.widgets.directories

import android.annotation.SuppressLint
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.discord.app.AppFragment
import com.discord.widgets.hubs.toArgsBundle
import com.discord.models.hubs.DirectoryEntryCategory

/**
 * One [WidgetDirectoryCategory] page per directory tab.
 *
 * [tabs] is assigned from the view model's `mappedTabs`; the tab strip and the pages therefore
 * always describe the same category list.
 */
class WidgetDirectoryChannelViewPagerAdapter(fragment: AppFragment) : FragmentStateAdapter(fragment) {

    var tabs: List<DirectoryEntryCategory> = emptyList()
        // `FragmentStateAdapter` has no diffing API — page identity is positional unless the
        // subclass overrides `getItemId`/`containsItem`, and the reference does not. The set is
        // guarded so an identical assignment does not tear down and rebuild every page fragment.
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int): WidgetDirectoryCategory {
        val category = checkNotNull(tabs.getOrNull(position)) {
            "Couldn't find tab with position $position in $tabs"
        }
        return WidgetDirectoryCategory().apply {
            arguments = DirectoryCategoryArgs(category).toArgsBundle()
        }
    }
}
