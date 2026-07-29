package com.discord.widgets.settings.developer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.views.experiments.ExperimentOverrideView

class ExperimentOverridesAdapter : RecyclerView.Adapter<ExperimentOverridesAdapter.ExperimentViewHolder>() {
    interface Item {
        val name: String
        val apiName: String
        val overrideBucket: Int?
        val bucketDescriptions: List<String>
        val onOverrideBucketSelected: (Int) -> Unit
        val onOverrideBucketCleared: () -> Unit
    }

    private var items = emptyList<Item>()

    fun setData(items: List<Item>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExperimentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.experiment_overrides_list_item, parent, false)
            .findViewById<ExperimentOverrideView>(R.id.experiment_overrides_list_item_experiment_override_view)
        return ExperimentViewHolder(view)
    }

    override fun onBindViewHolder(holder: ExperimentViewHolder, position: Int) = holder.bind(items[position])

    class ExperimentViewHolder(
        private val view: ExperimentOverrideView,
    ) : RecyclerView.ViewHolder(view) {
        fun bind(item: Item) = view.configure(
            item.name,
            item.apiName,
            item.overrideBucket,
            item.bucketDescriptions,
            item.onOverrideBucketSelected,
            item.onOverrideBucketCleared,
        )
    }
}
