package com.discord.widgets.settings.developer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.databinding.ExperimentOverridesListItemBinding

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ExperimentViewHolder(
        ExperimentOverridesListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: ExperimentViewHolder, position: Int) = holder.bind(items[position])

    class ExperimentViewHolder(
        private val binding: ExperimentOverridesListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Item) = binding.experimentOverridesListItemExperimentOverrideView.configure(
            item.name,
            item.apiName,
            item.overrideBucket,
            item.bucketDescriptions,
            item.onOverrideBucketSelected,
            item.onOverrideBucketCleared,
        )
    }
}
