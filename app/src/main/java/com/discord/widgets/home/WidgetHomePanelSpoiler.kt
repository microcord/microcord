package com.discord.widgets.home

import android.view.View
import android.view.ViewStub
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.utilities.view.text.LinkifiedTextView
import com.google.android.material.button.MaterialButton

/**
 * The gate that covers a spoiler channel until the viewer chooses to reveal it.
 *
 * Unlike the age gate this is not a safety control — agreeing is remembered per channel, and the
 * channel's topic is shown above the prompt so the viewer can tell what they are about to see
 * without seeing it.
 */
class WidgetHomePanelSpoiler {

    private var container: LinearLayout? = null
    private var title: TextView? = null
    private var topic: LinkifiedTextView? = null
    private var divider: View? = null
    private var confirmButton: MaterialButton? = null
    private var denyButton: MaterialButton? = null
    private var stubInflated = false

    @MainThread
    fun configureUI(
        channel: Channel?,
        gatingChannel: Channel?,
        viewStub: ViewStub?,
        onToggleGate: (Boolean) -> Unit,
        onAgree: (Long) -> Unit,
        onDeny: () -> Unit,
    ) {
        if (channel == null || gatingChannel == null) {
            setHidden(true, onToggleGate)
            return
        }
        if (!stubInflated) viewStub?.inflate()?.let { bindViews(it) }

        title?.text = gatingChannel.getDisplayNameOrDefault(title?.context ?: return)
        val topicText = gatingChannel.topic?.takeIf { it.isNotBlank() }
        topic?.text = topicText
        topic?.isVisible = topicText != null
        divider?.isVisible = topicText != null
        confirmButton?.setOnClickListener {
            onAgree(gatingChannel.id)
            setHidden(true, onToggleGate)
        }
        denyButton?.setOnClickListener { onDeny() }
        setHidden(false, onToggleGate)
    }

    /** @param onToggleGate receives "is the gate showing", so the caller can hide the chat under it. */
    private fun setHidden(hidden: Boolean, onToggleGate: (Boolean) -> Unit) {
        val container = this.container ?: return
        if (hidden) {
            if (container.visibility == View.GONE) return
            container.visibility = View.GONE
            onToggleGate(false)
        } else {
            if (container.visibility == View.VISIBLE) return
            container.visibility = View.VISIBLE
            onToggleGate(true)
        }
    }

    private fun bindViews(inflated: View) {
        stubInflated = true
        container = inflated as LinearLayout
        title = inflated.findViewById(R.id.home_panel_center_spoiler_title)
        topic = inflated.findViewById(R.id.home_panel_center_spoiler_topic)
        divider = inflated.findViewById(R.id.home_panel_center_spoiler_divider)
        confirmButton = inflated.findViewById(R.id.home_panel_center_spoiler_confirm)
        denyButton = inflated.findViewById(R.id.home_panel_center_spoiler_deny)
        ViewCompat.setOnApplyWindowInsetsListener(inflated) { view, insets ->
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, insets.systemWindowInsetBottom)
            insets.consumeSystemWindowInsets()
        }
        ViewCompat.requestApplyInsets(inflated)
    }
}
