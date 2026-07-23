package com.discord.views.experiments

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R

class ExperimentOverrideView(context: Context, attrs: AttributeSet?) :
    ConstraintLayout(context, attrs, 0) {
    data class SpinnerItem(val bucket: Int?, val text: String)

    private val bucketDescriptions: TextView
    private val bucketsSpinner: Spinner
    private val clear: TextView
    private val experimentApiName: TextView
    private val experimentName: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_experiment_override, this)
        bucketDescriptions = requireNotNull(findViewById(R.id.experiment_override_bucket_descriptions))
        bucketsSpinner = requireNotNull(findViewById(R.id.experiment_override_buckets_spinner))
        requireNotNull<View>(findViewById(R.id.experiment_override_buckets_spinner_container))
        clear = requireNotNull(findViewById(R.id.experiment_override_clear))
        experimentApiName = requireNotNull(findViewById(R.id.experiment_override_experiment_api_name))
        experimentName = requireNotNull(findViewById(R.id.experiment_override_experiment_name))
    }

    fun configure(
        experimentName: String,
        experimentApiName: String,
        overrideBucket: Int?,
        bucketDescriptions: List<String>,
        onOverrideBucketSelected: (Int) -> Unit,
        onOverrideBucketCleared: () -> Unit,
    ) {
        this.experimentName.text = experimentName
        this.experimentApiName.text = experimentApiName
        this.bucketDescriptions.text = bucketDescriptions.joinToString("\n")
        val items = buildList {
            if (overrideBucket == null) add(SpinnerItem(null, "Select..."))
            bucketDescriptions.indices.forEach { add(SpinnerItem(it, "Bucket $it")) }
        }
        bucketsSpinner.adapter = SpinnerAdapter(context, items)
        overrideBucket?.let(bucketsSpinner::setSelection)
        bucketsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (id != -1L) onOverrideBucketSelected(id.toInt())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        clear.setOnClickListener { onOverrideBucketCleared() }
        clear.visibility = if (overrideBucket == null) View.GONE else View.VISIBLE
    }

    private class SpinnerAdapter(context: Context, private val items: List<SpinnerItem>) :
        ArrayAdapter<SpinnerItem>(context, R.layout.view_simple_spinner_dropdown_item, items) {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].bucket?.toLong() ?: -1L
        override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
            itemView(position, convertView)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) =
            itemView(position, convertView)

        private fun itemView(position: Int, convertView: View?): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.view_simple_spinner_dropdown_item, null, false)
            (view as TextView).text = items[position].text
            return view
        }
    }
}
