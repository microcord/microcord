package com.discord.overlay

import android.animation.Animator
import android.animation.AnimatorInflater
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.isGuildVocalChannel
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.OverlayAppDialog
import com.discord.widgets.user.search.WidgetGlobalSearchAdapter
import com.discord.widgets.user.search.WidgetGlobalSearchModel
import com.discord.widgets.user.search.WidgetGlobalSearchViewModel
import com.google.android.material.textfield.TextInputLayout

class OverlayVoiceSelectorBubbleDialog(context: Context) : OverlayAppDialog(context) {
    private lateinit var dialogCard: View
    private lateinit var closeButton: View
    private lateinit var searchInput: TextInputLayout
    private lateinit var emptyResults: TextView
    private lateinit var results: RecyclerView

    private val viewModel = WidgetGlobalSearchViewModel()
    private lateinit var adapter: WidgetGlobalSearchAdapter
    private var query = ""
    private var renderedFilter: String? = null

    init {
        windowLayoutParams.flags =
            windowLayoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        inflateContent()
        adapter = WidgetGlobalSearchAdapter(results)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bindContent()
        AnimatorInflater.loadAnimator(context, R.animator.overlay_slide_up_fade_in).apply {
            setTarget(dialogCard)
            start()
        }
    }

    override fun getClosingAnimator(): Animator =
        AnimatorInflater.loadAnimator(context, R.animator.overlay_slide_down_fade_out).apply {
            setTarget(dialogCard)
        }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        unsubscribeSignal.onNext(null)
        adapter.unsubscribeFromUpdates()
        removeAllViewsInLayout()
        inflateContent()
        bindContent()
    }

    override fun onDetachedFromWindow() {
        adapter.unsubscribeFromUpdates()
        adapter.dispose()
        viewModel.unsubscribeSignal.onNext(null)
        super.onDetachedFromWindow()
    }

    private fun inflateContent() {
        dialogCard = LayoutInflater.from(context)
            .inflate(R.layout.overlay_voice_channel_selector, this, false)
        addView(dialogCard)
        closeButton = requireNotNull(dialogCard.findViewById(R.id.close))
        searchInput = requireNotNull(dialogCard.findViewById(R.id.overlay_channel_search))
        emptyResults = requireNotNull(dialogCard.findViewById(R.id.empty_results))
        results = requireNotNull(dialogCard.findViewById(R.id.results_rv))
    }

    private fun bindContent() {
        closeButton.setOnClickListener { close() }
        searchInput.editText?.apply {
            setText(query)
            setSelection(text?.length ?: 0)
            doAfterTextChanged { editable ->
                query = editable?.toString().orEmpty()
                viewModel.setFilter("!$query")
            }
        }

        adapter.recycler = results
        MGRecyclerAdapter.configure(adapter)
        adapter.onSelectedListener = { _, _, item ->
            (item as? WidgetGlobalSearchModel.ItemChannel)?.channel?.let { channel ->
                StoreStream.getVoiceChannelSelected().selectVoiceChannel(channel.id).subscribe({}, {})
                close()
            }
        }
        viewModel.setFilter("!$query")
        viewModel.observeViewState()
            .ui(this)
            .appSubscribe(OverlayVoiceSelectorBubbleDialog::class.java, context, onNext = ::render)
    }

    private fun render(model: WidgetGlobalSearchModel) {
        val rows = voiceChannelRows(model.data)
        emptyResults.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        results.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        adapter.setOnUpdated { _, _ ->
            if (renderedFilter != model.filter) {
                results.scrollToPosition(0)
                renderedFilter = model.filter
            }
        }
        adapter.setData(rows)
    }

    companion object {
        internal fun voiceChannelRows(
            rows: List<WidgetGlobalSearchModel.ItemDataPayload>,
        ): List<WidgetGlobalSearchModel.ItemDataPayload> = rows.filter { row ->
            row is WidgetGlobalSearchModel.ItemChannel && row.channel.isGuildVocalChannel()
        }
    }
}
