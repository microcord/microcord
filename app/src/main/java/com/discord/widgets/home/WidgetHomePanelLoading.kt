package com.discord.widgets.home

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.utilities.display.getStatusBarHeight
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.fadeIn
import com.discord.utilities.view.extensions.fadeOut
import kotlin.math.roundToInt

/**
 * The splash panel shown over the home surface until the client has hydrated.
 *
 * Two details that are easy to get wrong:
 *
 * * **The logo is nudged up by half the status-bar height.** The panel draws under the status bar,
 *   so centring it in the layout would put it visually low; the negative top margin puts it back on
 *   the optical centre.
 * * **[panelInitialized] is static.** Once the client has hydrated in this process, a later
 *   recreation of the home fragment must not flash the splash again, so the "already initialised"
 *   answer outlives the view. The first configure after that swaps instantly instead of fading.
 */
class WidgetHomePanelLoading(root: View) {

    private val loadingPanel: FrameLayout = root.findViewById(R.id.loading)
    private val logo: ImageView = loadingPanel.findViewById(R.id.logo)
    private val centerPanel: View = root.findViewById(R.id.panel_center)

    init {
        centerLogoRelativeToLoadingScreen()
    }

    private fun centerLogoRelativeToLoadingScreen() {
        val layoutParams = logo.layoutParams as FrameLayout.LayoutParams
        layoutParams.topMargin = (getStatusBarHeight(logo.resources) / -2f).roundToInt()
        logo.layoutParams = layoutParams
    }

    fun configure(fragment: AppFragment) {
        WidgetHomeModel.observeInitialized()
            .ui(fragment)
            .appSubscribe(WidgetHomePanelLoading::class.java) { setLoadingPanelVisibility(it, animate = true) }
        setLoadingPanelVisibility(panelInitialized, animate = false)
    }

    private fun setLoadingPanelVisibility(initialized: Boolean, animate: Boolean) {
        // Once the splash is gone it never comes back, so an "initialised" update after it has been
        // dismissed is a no-op. Only a not-yet-initialised update may put it back on screen.
        if (loadingPanel.visibility != View.VISIBLE && initialized) return
        panelInitialized = initialized
        if (!initialized) {
            centerPanel.visibility = View.GONE
            loadingPanel.visibility = View.VISIBLE
            return
        }
        if (animate) {
            loadingPanel.fadeOut()
            centerPanel.fadeIn()
        } else {
            loadingPanel.visibility = View.GONE
            centerPanel.visibility = View.VISIBLE
        }
        onHomeShown()
    }

    companion object {
        /** Process-wide: the splash is a first-launch affordance, not a per-view one. */
        private var panelInitialized = false

        /**
         * The reference raises `StoreAnalytics.appUiViewed(WidgetHome::class)` here — the moment the
         * splash gives way is what "the app is usable" means for cold-start timing. `StoreAnalytics`
         * exists in this project but `StoreStream` neither builds nor publishes it, and calling
         * `AppStartAnalyticsTracker` directly would lose its once-per-process guard, so the call
         * site is a seam. Point it at the store once it is wired.
         */
        @JvmField
        var onHomeShown: () -> Unit = {}
    }
}
