package com.discord.widgets.nux

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppFragment
import com.discord.utilities.dimen.dpToPixels
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * The three-page "here is how the app is laid out" overlay shown to new users.
 *
 * It is a dialog rather than a screen so it can float over whatever the user was looking at, and it
 * heavily dims the background (0.9, against the platform default of 0.6) because the pages are
 * annotated screenshots that have to read as an overlay rather than as content.
 */
class WidgetNavigationHelp : AppDialog(R.layout.widget_navigation_help) {

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setDimAmount(BACKGROUND_DIM_AMOUNT)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            // Fully transparent: the pages draw their own translucent backgrounds.
            setBackgroundDrawable(ColorDrawable(0))
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            )
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val pager = view.findViewById<ViewPager2>(R.id.navigation_help_nux_pager)
        pager.setPageTransformer(MarginPageTransformer(dpToPixels(PAGE_MARGIN_DP)))
        pager.adapter = NuxPagerAdapter(this)

        // The reference calls `ViewPager2ExtensionsKt.setUpWithViewPager2`, which is not
        // reconstructed; it is exactly this mediator with a no-op tab configurer.
        TabLayoutMediator(
            view.findViewById<TabLayout>(R.id.navigation_help_indicators),
            pager,
        ) { _, _ -> }.attach()

        view.findViewById<ImageView>(R.id.navigation_help_close).setOnClickListener {
            // The overlay is routinely dismissed from an async callback after the host saved state.
            dismissAllowingStateLoss()
        }
    }


    /** Page 1: the guild rail. */
    class NuxGuildsPageFragment : AppFragment(R.layout.widget_navigation_help_nux_guilds)

    /** Page 2: the channel list. */
    class NuxChannelsPageFragment : AppFragment(R.layout.widget_navigation_help_nux_channels)

    /** Page 3: direct messages. */
    class NuxDmsPageFragment : AppFragment(R.layout.widget_navigation_help_nux_dms)

    class NuxPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

        override fun getItemCount(): Int = PAGE_COUNT

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> NuxGuildsPageFragment()
            1 -> NuxChannelsPageFragment()
            2 -> NuxDmsPageFragment()
            else -> throw IllegalStateException("unknown nux page: $position")
        }

        private companion object {
            const val PAGE_COUNT = 3
        }
    }

    companion object {
        private const val BACKGROUND_DIM_AMOUNT = 0.9f
        private const val PAGE_MARGIN_DP = 8

        /**
         * The reference tags this dialog with the literal string `"javaClass"` — a Kotlin idiom
         * that was never evaluated. Reproduced so a queued duplicate is still suppressed by the
         * same key.
         */
        private const val TAG = "javaClass"

        fun show(fragmentManager: FragmentManager) {
            WidgetNavigationHelp().show(fragmentManager, TAG)
        }
    }
}
