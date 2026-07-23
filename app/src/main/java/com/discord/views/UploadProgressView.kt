package com.discord.views

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.discord.R

class UploadProgressView(context: Context, attrs: AttributeSet?) : ConstraintLayout(context, attrs) {
    private val progressBar: ProgressBar
    private val fileImage: ImageView
    private val subtext: TextView
    private val title: TextView
    private val completeDrawable = ContextCompat.getDrawable(context, R.drawable.drawable_progress_green)
    private val inProgressDrawable = TypedValue().let { value ->
        context.theme.resolveAttribute(R.attr.progress_gradient, value, true)
        ContextCompat.getDrawable(context, value.resourceId)
    }

    init {
        val content = LayoutInflater.from(context).inflate(R.layout.view_upload_progress, this, false)
        addView(content)
        progressBar = requireNotNull(content.findViewById(R.id.progress_bar))
        fileImage = requireNotNull(content.findViewById(R.id.progress_file_image))
        subtext = requireNotNull(content.findViewById(R.id.progress_subtext))
        title = requireNotNull(content.findViewById(R.id.progress_text))
    }

    @JvmOverloads
    fun configure(title: CharSequence, progress: Int, subtitle: String? = null) {
        this.title.text = title
        when {
            progress >= 0 -> {
                progressBar.isIndeterminate = false
                if (Build.VERSION.SDK_INT >= 24) {
                    progressBar.setProgress(progress, progress >= progressBar.progress)
                } else {
                    progressBar.progress = progress
                }
                progressBar.progressDrawable = if (progress == 100) completeDrawable else inProgressDrawable
                progressBar.visibility = View.VISIBLE
            }
            progress == -1 -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
            }
            progress == -2 -> progressBar.visibility = View.INVISIBLE
            else -> throw IllegalArgumentException("invalid argument supplied to progress: $progress")
        }
        subtext.text = subtitle
        subtext.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun setIcon(drawableResId: Int) {
        fileImage.setImageResource(drawableResId)
    }
}
