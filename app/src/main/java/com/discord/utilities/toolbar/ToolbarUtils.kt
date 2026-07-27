package com.discord.utilities.toolbar

import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat

fun Toolbar.getNavigationIconView(): View? {
    val existingDescription = navigationContentDescription?.takeIf(CharSequence::isNotEmpty)
    val lookup = existingDescription ?: "navigationIcon"
    navigationContentDescription = lookup
    val matches = ArrayList<View>()
    findViewsWithText(matches, lookup, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
    if (existingDescription == null) navigationContentDescription = null
    return matches.firstOrNull()
}

fun Toolbar.positionUnreadCountView(view: View) {
    val navigation = getNavigationIconView() ?: return
    val position = {
        val offset = 2 * resources.displayMetrics.density
        view.x = navigation.right / 2f + offset
        view.y = navigation.bottom / 2f + offset
    }
    if (ViewCompat.isLaidOut(navigation) && !navigation.isLayoutRequested) {
        position()
    } else {
        navigation.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                changed: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                changed.removeOnLayoutChangeListener(this)
                position()
            }
        })
    }
}
