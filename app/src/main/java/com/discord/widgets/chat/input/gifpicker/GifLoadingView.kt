package com.discord.widgets.chat.input.gifpicker

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.discord.R
import com.discord.utilities.recycler.GridColumnSpaceItemDecoration

class GifLoadingView : RecyclerView {
    private var isStaggered = false
    private val loadingGifAdapter = LoadingGifAdapter()

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initialize(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        initialize(attrs)
    }

    private fun initialize(attrs: AttributeSet?) {
        context.obtainStyledAttributes(attrs, R.styleable.GifLoadingView).run {
            try {
                isStaggered = getBoolean(R.styleable.GifLoadingView_glv_is_staggered, false)
            } finally {
                recycle()
            }
        }
        adapter = loadingGifAdapter
    }

    @JvmOverloads
    fun updateView(numColumns: Int, numRows: Int = 4) {
        layoutManager = StaggeredGridLayoutManager(numColumns, StaggeredGridLayoutManager.VERTICAL)
        addItemDecoration(
            GridColumnSpaceItemDecoration(
                (8 * resources.displayMetrics.density).toInt(),
                numColumns,
            ),
        )
        val count = numRows * numColumns
        val heights = if (isStaggered) {
            buildList {
                add(96)
                for (index in 1 until count - 1) {
                    add(if (index % 2 != 0 || index >= numColumns) 160 else 96)
                }
                add(96)
            }
        } else {
            List(count) { 96 }
        }
        loadingGifAdapter.setData(heights)
    }

    private class LoadingGifAdapter : Adapter<LoadingGifViewHolder>() {
        private var heightsDp: List<Int> = emptyList()

        override fun getItemCount() = heightsDp.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = LoadingGifViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.loading_gif_item_view, parent, false),
        )

        override fun onBindViewHolder(holder: LoadingGifViewHolder, position: Int) {
            holder.configure(heightsDp[position])
        }

        fun setData(heightsDp: List<Int>) {
            this.heightsDp = heightsDp
            notifyDataSetChanged()
        }
    }

    private class LoadingGifViewHolder(private val root: android.view.View) : ViewHolder(root) {
        fun configure(heightDp: Int) {
            root.layoutParams = root.layoutParams.apply {
                height = (heightDp * root.resources.displayMetrics.density).toInt()
            }
        }
    }
}
