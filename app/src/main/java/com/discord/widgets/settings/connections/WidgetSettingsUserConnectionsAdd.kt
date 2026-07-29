package com.discord.widgets.settings.connections

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.stores.StoreStream
import com.discord.utilities.platform.Platform

class WidgetSettingsUserConnectionsAdd : AppBottomSheet() {
    private val adapter = PlatformAdapter(::selectPlatform)

    override fun getContentViewResId() = R.layout.widget_settings_connections_add

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<RecyclerView>(R.id.connections_recycler).adapter = adapter
        val playstationBucket = StoreStream.getExperiments()
            .getUserExperiment("2021-12_connected_accounts_playstation", trackExposure = true)
            ?.bucket
        adapter.setData(
            Platform.entries.filter {
                it.enabled && it != Platform.NONE && (it != Platform.PLAYSTATION || playstationBucket == 1 || playstationBucket == 2)
            },
        )
    }

    override fun onPause() {
        super.onPause()
        dismiss()
        hideKeyboard(view)
    }

    private fun selectPlatform(platform: Platform, view: View) {
        dismiss()
        if (platform == Platform.XBOX) {
            WidgetSettingsUserConnectionsAddXbox.launch(view.context)
        } else {
            StoreStream.getUserConnections().authorizeConnection(platform.platformId, view.context)
        }
    }

    private class PlatformAdapter(
        private val onSelected: (Platform, View) -> Unit,
    ) : RecyclerView.Adapter<PlatformViewHolder>() {
        private var items = emptyList<Platform>()

        fun setData(value: List<Platform>) {
            items = value
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PlatformViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.widget_settings_item_add_connected_account, parent, false),
            onSelected,
        )

        override fun onBindViewHolder(holder: PlatformViewHolder, position: Int) = holder.bind(items[position])
    }

    private class PlatformViewHolder(
        itemView: View,
        private val onSelected: (Platform, View) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.add_connected_account_image)
        private val name: TextView = itemView.findViewById(R.id.add_connected_account_text)

        fun bind(platform: Platform) {
            name.text = platform.properName
            val value = TypedValue()
            val drawable = platform.themedPlatformImage?.takeIf {
                itemView.context.theme.resolveAttribute(it, value, true)
            }?.let { value.resourceId } ?: 0
            image.setImageResource(drawable)
            itemView.setOnClickListener { onSelected(platform, it) }
        }
    }

    companion object {
        fun show(fragment: Fragment) {
            WidgetSettingsUserConnectionsAdd().show(fragment.parentFragmentManager, WidgetSettingsUserConnectionsAdd::class.java.name)
        }
    }
}
