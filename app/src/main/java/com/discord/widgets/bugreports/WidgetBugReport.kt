package com.discord.widgets.bugreports

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.bugreport.Feature
import com.discord.app.AppFragment
import com.discord.app.AppBottomSheet
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.i18n.I18nSpans
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.ui
import com.discord.utilities.view.recycler.MaxHeightRecyclerView
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.setText
import com.discord.utilities.view.extensions.setTextIfDifferent
import com.discord.utilities.view.validators.BasicTextInputValidator
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.ValidationManager
import com.discord.views.SearchInputView
import com.discord.views.sticker.StickerView
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The in-app bug reporter: a screenshot, a title, a feature area, a priority and a description.
 *
 * Three surfaces share one flipper — the form, a sending/sent status screen and the feature-area
 * picker. The picker is reached from the form and returns to it on Back, which is why the back
 * handler is registered lazily on first entry and removed again on the way out.
 */
class WidgetBugReport : AppFragment(R.layout.widget_bug_report) {

    private val viewModel: BugReportViewModel by appViewModels {
        BugReportViewModel(
            screenshotUri = Uri.parse(mostRecentIntent.getStringExtra(INTENT_EXTRA_SCREENSHOT_URI)),
            screenshotName = mostRecentIntent.getStringExtra(INTENT_EXTRA_SCREENSHOT_FILENAME) ?: "",
        )
    }

    private val featuresAdapter = BugReportFeatureAdapter(::onFeatureClickListener)
    private val imagesChangeDetector = MGImages.DistinctChangeDetector()
    private var backHandlerAdded = false

    private lateinit var flipper: AppViewFlipper
    private lateinit var screenshot: ImageView
    private lateinit var clearScreenshot: ImageView
    private lateinit var noScreenshotLabel: MaterialTextView
    private lateinit var undoScreenshotRemove: MaterialButton
    private lateinit var reportName: TextInputLayout
    private lateinit var reportDescription: TextInputLayout
    private lateinit var featureArea: TextInputLayout
    private lateinit var featureAreaTouchDelegate: View
    private lateinit var priority: TextInputLayout
    private lateinit var priorityIcon: SimpleDraweeView
    private lateinit var priorityTouchDelegate: View
    private lateinit var submitError: MaterialTextView
    private lateinit var submit: MaterialButton
    private lateinit var statusSticker: StickerView
    private lateinit var statusText: MaterialTextView
    private lateinit var featureSearch: SearchInputView
    private lateinit var featureLoader: ProgressBar
    private lateinit var featureRecycler: RecyclerView

    private val validationManager: ValidationManager by lazy {
        ValidationManager(
            Input.TextInputLayoutInput(
                "name",
                reportName,
                BasicTextInputValidator.createRequiredInputValidator(R.string.bug_report_error_name_required),
            ),
            Input.TextInputLayoutInput(
                "priority",
                priority,
                BasicTextInputValidator.createRequiredInputValidator(R.string.bug_report_error_priority_required),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.prefetchStickers(requireContext())
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        flipper = view.findViewById(R.id.bug_report_switch_flipper)
        screenshot = view.findViewById(R.id.bug_report_screenshot)
        clearScreenshot = view.findViewById(R.id.bug_report_clear_screenshot)
        noScreenshotLabel = view.findViewById(R.id.bug_report_no_screenshot_label)
        undoScreenshotRemove = view.findViewById(R.id.bug_report_undo_screenshot_remove)
        reportName = view.findViewById(R.id.bug_report_name)
        reportDescription = view.findViewById(R.id.bug_report_description)
        featureArea = view.findViewById(R.id.bug_report_feature_area)
        featureAreaTouchDelegate = view.findViewById(R.id.widget_bug_report_feature_touch_delegate)
        priority = view.findViewById(R.id.bug_report_priority)
        priorityIcon = view.findViewById(R.id.bug_report_priority_icon)
        priorityTouchDelegate = view.findViewById(R.id.widget_bug_report_severity_touch_delegate)
        submitError = view.findViewById(R.id.submit_report_error)
        submit = view.findViewById(R.id.bug_report_submit)
        statusSticker = view.findViewById(R.id.bug_report_status_expression)
        statusText = view.findViewById(R.id.bug_report_status_text)
        featureSearch = view.findViewById(R.id.bug_report_feature_search)
        featureLoader = view.findViewById(R.id.bug_report_feature_loader)
        featureRecycler = view.findViewById(R.id.bug_report_feature_recycler)

        setActionBarTitle(R.string.submit_bug)
        setActionBarDisplayHomeAsUpEnabled()

        featureRecycler.adapter = featuresAdapter
        screenshot.setImageURI(Uri.parse(mostRecentIntent.getStringExtra(INTENT_EXTRA_SCREENSHOT_URI)))

        clearScreenshot.setOnClickListener { viewModel.useScreenshot(false) }
        undoScreenshotRemove.setOnClickListener { viewModel.useScreenshot(true) }
        reportName.addBindedTextWatcher(this) { viewModel.updateReportName(it.toString()) }
        reportDescription.addBindedTextWatcher(this) { viewModel.updateReportDescription(it.toString()) }
        featureSearch.bindTextWatcher(this) { viewModel.filterFeatures(it) }
        featureAreaTouchDelegate.setOnClickListener { viewModel.showFeatureAreas() }
        priorityTouchDelegate.setOnClickListener {
            hideKeyboard()
            showPriorityPicker()
        }
        submit.setOnClickListener { submit() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .subscribe(::updateView)
        viewModel.observeEvents()
            .ui(this)
            .subscribe(::handleEvent)
    }

    private fun handleEvent(event: BugReportViewModel.Event) {
        when (event) {
            is BugReportViewModel.Event.CloseReport -> appActivity?.finish()
        }
    }

    private fun onFeatureClickListener(feature: Feature) {
        viewModel.selectFeatureArea(feature)
    }

    private fun showPriorityPicker() {
        BugReportPrioritySheet.show(childFragmentManager) { viewModel.updatePriority(it) }
    }

    private fun submit() {
        if (!validationManager.validate()) return
        viewModel.sendReport(readScreenshotBody())
    }

    /** Reads the screenshot back off the content resolver, or `null` when the user removed it. */
    private fun readScreenshotBody(): RequestBody? {
        if (!viewModel.useScreenshot) return null
        val uri = viewModel.screenshotUri
        if (uri.path == null) return null
        val resolver = requireContext().contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return bytes.toRequestBody(resolver.getType(uri)?.toMediaTypeOrNull())
    }

    private fun updateView(viewState: BugReportViewModel.ViewState) {
        when (viewState) {
            is BugReportViewModel.ViewState.Report -> configureReport(viewState)
            is BugReportViewModel.ViewState.Sending -> configureStatus(
                viewState.sendingSticker,
                R.string.bug_report_status_sending,
            )

            is BugReportViewModel.ViewState.Success -> configureStatus(
                viewState.successSticker,
                R.string.bug_report_status_sent,
            )

            is BugReportViewModel.ViewState.SelectFeature -> configureSelectFeature(viewState)
        }
    }

    private fun configureReport(viewState: BugReportViewModel.ViewState.Report) {
        setActionBarTitle(R.string.submit_bug)
        setActionBarSubtitle("")
        flipper.displayedChild = VIEW_INDEX_REPORT

        submitError.visibility = if (viewState.error != null) View.VISIBLE else View.GONE
        viewState.error?.let { submitError.text = it.message }

        reportName.setTextIfDifferent(viewState.reportName)
        reportDescription.setTextIfDifferent(viewState.reportDescription)

        // "Removed" keeps the image slot occupied (INVISIBLE, not GONE) so the form does not jump.
        screenshot.visibility = if (viewState.useScreenshot) View.VISIBLE else View.INVISIBLE
        clearScreenshot.visibility = if (viewState.useScreenshot) View.VISIBLE else View.GONE
        noScreenshotLabel.visibility = if (viewState.useScreenshot) View.GONE else View.VISIBLE
        undoScreenshotRemove.visibility = if (viewState.useScreenshot) View.GONE else View.VISIBLE

        viewState.feature?.let { featureArea.setTextIfDifferent(it.name) }
        viewState.priority?.let { index ->
            val level = priorityLevels[index]
            priority.setText(level.title)
            MGImages.setImage(
                view = priorityIcon,
                url = IconUtils.getForEmoji(level.emojiId, animated = true, sizePx = 64),
                useSmallCache = true,
                changeDetector = imagesChangeDetector,
            )
        }
    }

    private fun configureStatus(sticker: com.discord.api.sticker.Sticker?, textResId: Int) {
        flipper.displayedChild = VIEW_INDEX_STATUS
        if (sticker != null) {
            statusSticker.visibility = View.VISIBLE
            statusSticker.setSticker(sticker)
        } else {
            // No sticker store yet — see BugReportViewModel.stickerLookup.
            statusSticker.visibility = View.GONE
        }
        statusText.setText(textResId)
    }

    private fun configureSelectFeature(viewState: BugReportViewModel.ViewState.SelectFeature) {
        setActionBarTitle(R.string.bug_report_select_feature_area)
        setActionBarSubtitle(getString(R.string.submit_bug))
        flipper.displayedChild = VIEW_INDEX_FEATURE_AREA

        featureLoader.visibility = if (viewState.loadingFeatures) View.VISIBLE else View.GONE
        featureRecycler.visibility = if (viewState.loadingFeatures) View.GONE else View.VISIBLE

        // Only push the query back into the field when it actually differs, or the cursor jumps
        // while the user is typing.
        val query = viewState.query.orEmpty()
        val currentQuery = (featureSearch.getEditText() as? android.widget.EditText)?.text?.toString()
        if (currentQuery != query) featureSearch.setText(query)

        featuresAdapter.setData(viewState.features)

        if (!backHandlerAdded) {
            backHandlerAdded = true
            setOnBackPressed({
                backHandlerAdded = false
                viewModel.showReport()
                setOnBackPressed({ false })
                true
            })
        }
    }

    companion object {
        const val INTENT_EXTRA_SCREENSHOT_FILENAME = "extra_screenshot_filename"
        const val INTENT_EXTRA_SCREENSHOT_URI = "extra_screenshot_uri"
        const val VIEW_INDEX_REPORT = 0
        const val VIEW_INDEX_STATUS = 1
        const val VIEW_INDEX_FEATURE_AREA = 2

        /**
         * P0…P3. **The index into this list is the `priority` field sent to the API**, so neither
         * the order nor the contents may be rearranged for presentation.
         */
        val priorityLevels: List<Priority> = listOf(
            Priority(
                R.string.bug_report_priority_critical_title,
                R.string.bug_report_priority_critical_description,
                801497159479722084L,
            ),
            Priority(
                R.string.bug_report_priority_high_title,
                R.string.bug_report_priority_high_description,
                410336837563973632L,
            ),
            Priority(
                R.string.bug_report_priority_low_title,
                R.string.bug_report_priority_low_description,
                841420679643529296L,
            ),
            Priority(
                R.string.bug_report_priority_very_low_title,
                R.string.bug_report_priority_very_low_description,
                827645852352512021L,
            ),
        )

        /** Rendered description for [index], with the leading `**bold**` run styled. */
        fun priorityDescription(context: Context, index: Int): CharSequence =
            I18nSpans.render(context.getString(priorityLevels[index].description))

        /**
         * The reference takes a `ScreenshotDetector.Screenshot`; `com.discord.screenshot_detection`
         * is not reconstructed, so the two fields it carries are passed directly.
         */
        fun launch(context: Context, screenshotUri: Uri, screenshotFileName: String?) {
            val intent = Intent()
                .putExtra(INTENT_EXTRA_SCREENSHOT_URI, screenshotUri.toString())
                .putExtra(INTENT_EXTRA_SCREENSHOT_FILENAME, screenshotFileName)
            AppScreenRouter.launch(context, WidgetBugReport::class.java, intent)
        }
    }
}

internal class BugReportPrioritySheet : AppBottomSheet() {
    var onSelected: ((Int) -> Unit)? = null

    override fun getContentViewResId() = R.layout.bottom_sheet_simple_selector

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setBottomSheetCollapsedStateDisabled()
        view.findViewById<TextView>(R.id.widget_simple_bottom_sheet_selector_placeholder)
            .setText(R.string.bug_report_priority)
        view.findViewById<MaxHeightRecyclerView>(R.id.widget_simple_bottom_sheet_selector_recycler).adapter =
            SimpleRecyclerAdapter(WidgetBugReport.priorityLevels) { inflater, parent ->
                PriorityHolder(inflater.inflate(R.layout.bottom_sheet_simple_selector_item, parent, false))
            }
    }

    override fun onPause() {
        super.onPause()
        dismiss()
    }

    private inner class PriorityHolder(view: View) : SimpleRecyclerAdapter.ViewHolder<Priority>(view) {
        override fun bind(data: Priority) {
            val index = WidgetBugReport.priorityLevels.indexOf(data)
            itemView.findViewById<TextView>(R.id.select_component_sheet_item_title).setText(data.title)
            itemView.findViewById<TextView>(R.id.select_component_sheet_item_description).text =
                WidgetBugReport.priorityDescription(itemView.context, index)
            MGImages.setImage(
                itemView.findViewById(R.id.select_component_sheet_item_icon),
                IconUtils.getForEmoji(data.emojiId, animated = true, sizePx = 64),
            )
            itemView.setOnClickListener {
                onSelected?.invoke(index)
                dismiss()
            }
        }
    }

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager, onSelected: (Int) -> Unit) =
            BugReportPrioritySheet().apply { this.onSelected = onSelected }
                .show(fragmentManager, BugReportPrioritySheet::class.java.name)
    }
}
