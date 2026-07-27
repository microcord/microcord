package com.discord.widgets.tabs

import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Publishes the measured height of the bottom navigation bar.
 *
 * The bar is drawn over the tab content rather than beside it, so the content needs a bottom margin
 * equal to the bar's height or its last row sits underneath. The height is not a constant — it grows
 * with the navigation-bar inset and the user's font scale — so it is measured
 * ([TabsHostBottomNavigationView.onSizeChanged]) and pushed through here into
 * [TabsHostViewModel.ViewState.bottomNavHeight], which [WidgetTabsHost] turns into that margin.
 *
 * A process-wide singleton because the view and the view model have no other common owner: the view
 * belongs to a fragment view that is destroyed and recreated, the view model outlives it.
 */
class BottomNavViewObserver : TabsHostBottomNavigationView.HeightChangedListener {

    private val heightSubject: BehaviorSubject<Int> = BehaviorSubject.create(0)

    fun observeHeight(): Observable<Int> = heightSubject

    override fun onHeightChanged(height: Int) {
        heightSubject.onNext(height)
    }

    companion object {
        val INSTANCE: BottomNavViewObserver by lazy { BottomNavViewObserver() }
    }
}
