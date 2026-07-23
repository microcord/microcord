package com.discord.views.directories

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R

class ServerDiscoveryHeader(context: Context, attrs: AttributeSet?) :
    ConstraintLayout(context, attrs, 0) {
    private val description: TextView
    private val searchLayout: FrameLayout
    private val title: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_server_discovery_header, this)
        description = requireNotNull(findViewById(R.id.server_discovery_header_description))
        requireNotNull<View>(findViewById(R.id.server_discovery_header_image))
        searchLayout = requireNotNull(findViewById(R.id.server_discovery_header_search_layout))
        title = requireNotNull(findViewById(R.id.server_discovery_header_title))
    }

    fun setButtonOnClickListener(listener: View.OnClickListener?) {
        searchLayout.setOnClickListener(listener)
    }

    fun setDescription(description: CharSequence?) {
        this.description.text = description
    }

    fun setTitle(title: CharSequence?) {
        this.title.text = title
    }
}
