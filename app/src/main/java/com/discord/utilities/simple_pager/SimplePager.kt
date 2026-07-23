package com.discord.utilities.simple_pager

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout

class SimplePager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewPager(context, attrs) {
    var isScrollingEnabled = true
    var keepAllPagesLoaded = true
    var preserveSelectedPage = true
    var wrapHeight = false

    override fun onInterceptTouchEvent(event: MotionEvent) =
        isScrollingEnabled && super.onInterceptTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent) =
        isScrollingEnabled && super.onTouchEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var resolvedHeightSpec = heightMeasureSpec
        if (wrapHeight) {
            var maxHeight = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                child.measure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                maxHeight = maxOf(maxHeight, child.measuredHeight)
            }
            if (maxHeight != 0) {
                resolvedHeightSpec = View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.EXACTLY)
            }
        }
        super.onMeasure(widthMeasureSpec, resolvedHeightSpec)
    }

    override fun setAdapter(adapter: PagerAdapter?) {
        val oldCurrentItem = currentItem
        super.setAdapter(adapter)
        if (preserveSelectedPage) {
            if (childCount > oldCurrentItem) setCurrentItem(oldCurrentItem, true) else currentItem = 0
        }
        if (keepAllPagesLoaded) offscreenPageLimit = adapter?.count ?: 0
    }

    fun setTabLayout(tabLayout: TabLayout?) {
        tabLayout?.setupWithViewPager(this)
    }

    class Item(val title: String, val creator: () -> Fragment)

    class Adapter(fragmentManager: FragmentManager, vararg items: Item) :
        FragmentPagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        private val items = items.asList()

        override fun getCount() = items.size

        override fun getPageTitle(position: Int): CharSequence? =
            items.getOrNull(position)?.title ?: super.getPageTitle(position)

        override fun getItem(position: Int): Fragment =
            items.getOrNull(position)?.creator?.invoke()
                ?: throw IllegalArgumentException("Invalid position: $position.")
    }
}
