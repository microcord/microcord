package com.discord.widgets.chat.input.expression

import com.discord.widgets.chat.input.gifpicker.GifCategoryItem

/**
 * A page the tray shows *instead of* its three tabs.
 *
 * Only one exists — drilling into a GIF category — but it is modelled as a sealed hierarchy because
 * the tray's view state keys "is a detail page open" off it being non-null.
 */
sealed class ExpressionDetailPage {
    data class GifCategoryPage(val gifCategoryItem: GifCategoryItem) : ExpressionDetailPage()
}
