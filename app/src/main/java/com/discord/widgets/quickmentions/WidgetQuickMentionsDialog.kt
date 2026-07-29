package com.discord.widgets.quickmentions

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.appActivityViewModels
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.widgets.user.WidgetUserMentions
import com.discord.widgets.user.search.WidgetGlobalSearch
import com.discord.widgets.user.search.WidgetGlobalSearchDismissModel
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * The two-tab overlay reached from the chat header: quick switcher on the left, recent mentions on
 * the right.
 *
 * It is the same [WidgetGlobalSearch] the standalone switcher hosts, so a selection dismisses this
 * dialog through the shared [WidgetGlobalSearchDismissModel] rather than through anything the pager
 * knows about.
 */
class WidgetQuickMentionsDialog : AppDialog(R.layout.widget_quick_mentions_dialog) {

    private val dismissViewModel: WidgetGlobalSearchDismissModel by appActivityViewModels {
        WidgetGlobalSearchDismissModel()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        setStyle(STYLE_NORMAL, R.style.AppTheme_GlobalSearchDialog)
        return super.onCreateDialog(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            if (!StoreStream.getAccessibility().isReducedMotionEnabled()) {
                attributes.windowAnimations = R.style.UiKit_QuickSwitcher_Animation
            }
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            )
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        dismissViewModel.dismissEvents.ui(this).subscribe { dismiss() }

        val view = requireView()
        val tabsAdapter = TabsAdapter(this, arguments?.getString(WidgetGlobalSearch.EXTRA_SEARCH_TEXT))
        val pager = view.findViewById<ViewPager2>(R.id.tab_contents)
        pager.adapter = tabsAdapter
        TabLayoutMediator(view.findViewById<TabLayout>(R.id.tabs), pager) { tab, position ->
            tab.setText(tabsAdapter.getItemTitleResId(position))
        }.attach()
    }

    private class TabsAdapter(
        fragment: WidgetQuickMentionsDialog,
        private val searchText: String?,
    ) : FragmentStateAdapter(fragment) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment = when (position) {
            POSITION_QUICK_SWITCHER -> WidgetGlobalSearch.create(searchText)
            POSITION_MENTIONS -> WidgetUserMentions.create(isEmbedded = true)
            else -> throw UnsupportedOperationException("no tab at position $position")
        }

        fun getItemTitleResId(position: Int): Int = when (position) {
            POSITION_QUICK_SWITCHER -> R.string.search
            POSITION_MENTIONS -> R.string.mentions
            else -> throw UnsupportedOperationException("no tab at position $position")
        }
    }

    companion object {
        private const val TAG = "WidgetQuickMentionsDialog"
        private const val POSITION_QUICK_SWITCHER = 0
        private const val POSITION_MENTIONS = 1

        fun show(fragmentManager: FragmentManager, searchText: String? = null) {
            dismiss(fragmentManager)
            WidgetQuickMentionsDialog()
                .apply {
                    arguments = Bundle().apply {
                        putString(WidgetGlobalSearch.EXTRA_SEARCH_TEXT, searchText)
                    }
                }
                .show(fragmentManager, TAG)
        }

        fun dismiss(fragmentManager: FragmentManager) {
            (fragmentManager.findFragmentByTag(TAG) as? WidgetQuickMentionsDialog)?.dismiss()
        }
    }
}
