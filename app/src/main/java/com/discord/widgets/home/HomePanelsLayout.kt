package com.discord.widgets.home

import android.content.Context
import android.util.AttributeSet
import com.discord.panels.OverlappingPanelsLayout

interface PanelLayout {
    fun openStartPanel()
    fun openEndPanel()
    fun closePanels()
}

class HomePanelsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : OverlappingPanelsLayout(context, attrs, defStyleAttr), PanelLayout
