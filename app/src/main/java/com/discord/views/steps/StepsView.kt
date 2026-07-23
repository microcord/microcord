package com.discord.views.steps

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.discord.R
import com.discord.views.LoadingButton
import com.google.android.material.button.MaterialButton

class StepsView(context: Context, attrs: AttributeSet?) : RelativeLayout(context, attrs, 0) {
    private val buttonContainer: LinearLayout
    private val cancel: TextView
    private val close: ImageView
    private val done: LoadingButton
    private val next: MaterialButton
    private val progressIndicator: StepsProgressIndicatorView
    private val title: TextView
    private val viewPager: ViewPager2
    private var pagerAdapter: PagerAdapter? = null
    private var pagerAdapterChangeCallback: ViewPager2.OnPageChangeCallback? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_steps, this, true)
        buttonContainer = requireNotNull(findViewById(R.id.steps_button_container))
        cancel = requireNotNull(findViewById(R.id.steps_cancel))
        close = requireNotNull(findViewById(R.id.steps_close))
        done = requireNotNull(findViewById(R.id.steps_done))
        next = requireNotNull(findViewById(R.id.steps_next))
        progressIndicator = requireNotNull(findViewById(R.id.steps_progress_indicator))
        title = requireNotNull(findViewById(R.id.steps_title))
        viewPager = requireNotNull(findViewById(R.id.steps_viewpager))
        setIsLoading(false)
    }

    @JvmOverloads
    fun setup(
        adapter: PagerAdapter,
        onClose: () -> Unit,
        onPageSelected: (Int) -> Unit = {},
    ) {
        pagerAdapter = adapter
        viewPager.adapter = adapter
        pagerAdapterChangeCallback?.let(viewPager::unregisterOnPageChangeCallback)
        pagerAdapterChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onPageSelected(position)
                configurePage(position, onClose)
                progressIndicator.setCurrentStepIndex(position)
            }
        }.also(viewPager::registerOnPageChangeCallback)
        configurePage(0, onClose)
        progressIndicator.setTotalStepCount(adapter.itemCount)
    }

    fun setCurrentPage(index: Int) {
        if (index in 0 until (pagerAdapter?.itemCount ?: 0)) viewPager.currentItem = index
    }

    fun setIsNextButtonEnabled(isEnabled: Boolean) {
        next.isEnabled = isEnabled
    }

    fun setIsDoneButtonEnabled(isEnabled: Boolean) {
        done.isEnabled = isEnabled
    }

    fun setIsLoading(isLoading: Boolean) {
        done.setIsLoading(isLoading)
    }

    fun setIsTitleVisible(isVisible: Boolean) {
        title.visibility = if (isVisible) View.VISIBLE else View.GONE
        if (isVisible) progressIndicator.visibility = View.GONE
    }

    fun setStepProgressIndicatorVisible(isVisible: Boolean) {
        progressIndicator.visibility = if (isVisible) View.VISIBLE else View.GONE
        if (isVisible) title.visibility = View.GONE
    }

    fun setTitle(title: String) {
        this.title.text = title
    }

    fun a(adapter: PagerAdapter, onClose: () -> Unit, onPageSelected: (Int) -> Unit) =
        setup(adapter, onClose, onPageSelected)

    fun b(index: Int) = setCurrentPage(index)

    private fun configurePage(position: Int, onClose: () -> Unit) {
        close.setOnClickListener { onClose() }
        val adapter = pagerAdapter ?: return
        val steps = adapter.steps
        val step = steps[position]
        viewPager.isUserInputEnabled = step.allowScroll
        cancel.setText(step.cancelText)
        done.setText(context.getString(step.doneText))
        next.setText(step.nextText)

        next.setOnClickListener { view ->
            step.onNext?.invoke(view) ?: if (viewPager.currentItem != adapter.itemCount - 1) {
                viewPager.currentItem++
            } else Unit
        }
        cancel.setOnClickListener { view ->
            step.onCancel?.invoke(view) ?: if (viewPager.currentItem == 0) {
                onClose()
            } else {
                viewPager.currentItem--
            }
        }
        done.setOnClickListener { view -> step.onDone?.invoke(view) ?: onClose() }

        val lastPage = position == adapter.itemCount - 1
        next.visibility = if (!lastPage && step.canNext) View.VISIBLE else View.GONE
        cancel.visibility = if (!lastPage && step.canCancel) View.VISIBLE else View.GONE
        done.visibility = if (lastPage) View.VISIBLE else View.GONE
        buttonContainer.visibility = if (
            next.visibility == View.VISIBLE || cancel.visibility == View.VISIBLE || done.visibility == View.VISIBLE
        ) View.VISIBLE else View.GONE
    }

    sealed class Step(
        val nextText: Int,
        val cancelText: Int,
        val doneText: Int,
        val onNext: ((View) -> Unit)?,
        val onCancel: ((View) -> Unit)?,
        val onDone: ((View) -> Unit)?,
        val canNext: Boolean,
        val canCancel: Boolean,
        val allowScroll: Boolean,
    )

    data class FragmentStep(
        val fragment: Class<out Fragment>,
        val nextLabel: Int = R.string.next,
        val cancelLabel: Int = R.string.cancel,
        val doneLabel: Int = R.string.done,
        val arguments: Bundle? = null,
        val nextAction: ((View) -> Unit)? = null,
        val cancelAction: ((View) -> Unit)? = null,
        val doneAction: ((View) -> Unit)? = null,
        val nextAllowed: Boolean = true,
        val cancelAllowed: Boolean = true,
        val scrollingAllowed: Boolean = true,
    ) : Step(
        nextLabel,
        cancelLabel,
        doneLabel,
        nextAction,
        cancelAction,
        doneAction,
        nextAllowed,
        cancelAllowed,
        scrollingAllowed,
    )

    data class ViewStep(
        @LayoutRes val layoutId: Int,
        val nextLabel: Int = R.string.next,
        val cancelLabel: Int = R.string.cancel,
        val doneLabel: Int = R.string.done,
        val nextAction: ((View) -> Unit)? = null,
        val cancelAction: ((View) -> Unit)? = null,
        val doneAction: ((View) -> Unit)? = null,
        val nextAllowed: Boolean = true,
        val cancelAllowed: Boolean = true,
        val scrollingAllowed: Boolean = true,
    ) : Step(
        nextLabel,
        cancelLabel,
        doneLabel,
        nextAction,
        cancelAction,
        doneAction,
        nextAllowed,
        cancelAllowed,
        scrollingAllowed,
    )

    class PagerAdapter(fragment: Fragment, internal val steps: List<Step>) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = steps.size

        override fun createFragment(position: Int): Fragment = when (val step = steps[position]) {
            is FragmentStep -> step.fragment.getDeclaredConstructor().newInstance().apply {
                arguments = step.arguments
            }
            is ViewStep -> LayoutFragment(step.layoutId)
        }
    }

    class LayoutFragment(@LayoutRes private val layoutId: Int) : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View = inflater.inflate(layoutId, container, false)
    }
}
