package com.discord.widgets.chat.input

import android.content.Context
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.api.sticker.Sticker
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.models.domain.emoji.Emoji
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.attachments.AttachmentBottomSheet
import com.discord.widgets.chat.input.emoji.EmojiPickerListener
import com.discord.widgets.chat.input.expression.WidgetExpressionTray
import com.discord.widgets.chat.input.sticker.StickerPickerListener
import com.lytefast.flexinput.InputListener
import com.lytefast.flexinput.adapters.AddContentPagerAdapter
import com.lytefast.flexinput.adapters.AttachmentPreviewAdapter
import com.lytefast.flexinput.fragment.CameraFragment
import com.lytefast.flexinput.fragment.FilesFragment
import com.lytefast.flexinput.fragment.FlexInputFragment
import com.lytefast.flexinput.fragment.PhotosFragment
import com.lytefast.flexinput.model.Attachment
import com.lytefast.flexinput.model.Attachment.Companion.toAttachment

/**
 * Everything the composer needs from FlexInput: the attachment pages, the attachment preview strip,
 * and the expression tray that lives in FlexInput's emoji slot.
 *
 * Kept out of `WidgetChatInput` because it is all one concern — configuring somebody else's
 * fragment — and because it is the only place that has to know FlexInput's own view ids.
 *
 * **Two deliberate divergences**, both forced by the library:
 *  - The reference's `AttachmentPreviewAdapter` takes a long-press callback. The published
 *    `com.github.lytefast:flex-input:0.10.9` adapter has no such hook, so the long press is
 *    detected on the preview `RecyclerView` itself and resolved back to an attachment through the
 *    selection aggregator. Same result, no library fork.
 *  - Attachment sources are tracked by wrapping the selection listener rather than by overriding
 *    `SelectionAggregator.registerSelectionCoordinatorInternal`, which is `protected` in the fork
 *    and effectively final here.
 */
class WidgetChatInputAttachments(
    private val flexInputFragment: FlexInputFragment,
    private val flexInputViewModel: AppFlexInputViewModel,
    /** Overrides the default "append to the end of the composer" behaviour with cursor insertion. */
    private val emojiPickerListener: EmojiPickerListener? = null,
    /** Overrides the default synthetic `KEYCODE_DEL` with the host's own deletion. */
    private val onBackspacePressed: (() -> Unit)? = null,
) {

    /**
     * @param canCreateThread the reference adds a fourth "create thread" page here. There is no
     *   thread-composer page in this reconstruction yet, so the flag is accepted and the page is
     *   omitted rather than a blank tab being added.
     */
    fun configureFlexInputContentPages(canCreateThread: Boolean) {
        flexInputFragment.setContentPages(
            page(R_DRAWABLE_IMAGE, R_STRING_PHOTOS) { PhotosFragment() },
            page(R_DRAWABLE_FILE, R_STRING_FILES) { FilesFragment() },
            page(R_DRAWABLE_CAMERA, R_STRING_CAMERA) { CameraFragment() },
        )
        if (canCreateThread) {
            AppLog.i("thread-creation attachment page requested but not reconstructed")
        }
    }

    fun setInputListener(inputListener: InputListener) {
        flexInputFragment.setInputListener(inputListener)
    }

    fun addExternalAttachment(attachment: Attachment<*>) {
        flexInputFragment.addExternalAttachment(attachment)
    }

    /**
     * Wires the composer up: preview strip, attachment analytics, keyboard-committed content, and
     * the expression tray.
     *
     * @return the tray, so the host can drive [WidgetExpressionTray.isShown].
     */
    fun configureFlexInputFragment(fragment: AppFragment): WidgetExpressionTray? {
        configureFlexInputContentPages(canCreateThread = false)
        configurePreviewAdapter(fragment)
        configureKeyboardContent(fragment)
        return configureExpressionTray(fragment)
    }

    private fun configurePreviewAdapter(fragment: AppFragment) {
        val context = fragment.context ?: return
        val adapter = AttachmentPreviewAdapter<Attachment<Any>>(context.contentResolver)
        adapter.selectionAggregator
            .initFrom(flexInputFragment.selectionAggregator)
            .addItemSelectionListener(
                object : com.lytefast.flexinput.utils.SelectionCoordinator.ItemSelectionListener<Attachment<Any>> {
                    override fun onItemSelected(item: Attachment<Any>) = track(item, context)
                    override fun onItemUnselected(item: Attachment<Any>) = Unit
                    override fun unregister() = Unit
                },
            )
        flexInputFragment.setAttachmentPreviewAdapter(adapter)

        val previewList = flexInputFragment.view
            ?.findViewById<RecyclerView>(com.lytefast.flexinput.R.id.attachment_preview_list)
            ?: return
        installLongPressToOpenSheet(fragment, previewList, adapter)
    }

    /**
     * Long-pressing a preview thumbnail opens [AttachmentBottomSheet].
     *
     * The gesture is detected here rather than in the adapter because the published adapter only
     * exposes a click (which unselects). Resolution is by hit-test → child adapter position →
     * aggregator index, which is exactly what the adapter itself binds from.
     */
    private fun installLongPressToOpenSheet(
        fragment: AppFragment,
        previewList: RecyclerView,
        adapter: AttachmentPreviewAdapter<Attachment<Any>>,
    ) {
        val detector = GestureDetector(
            previewList.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(event: MotionEvent) {
                    val child = previewList.findChildViewUnder(event.x, event.y) ?: return
                    val position = previewList.getChildAdapterPosition(child)
                    if (position == RecyclerView.NO_POSITION) return
                    val aggregator = adapter.selectionAggregator
                    if (position >= aggregator.size) return
                    val attachment = aggregator[position]
                    child.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    AttachmentBottomSheet.show(
                        fragmentManager = fragment.childFragmentManager,
                        attachment = attachment,
                        spoiler = (attachment as? SourcedAttachment<*>)?.spoiler == true,
                        onFileRemoved = { aggregator.unselectItem(attachment) },
                        onMarkSpoiler = { spoiler ->
                            (attachment as? SourcedAttachment<*>)?.spoiler = spoiler
                            adapter.notifyDataSetChanged()
                        },
                    )
                }
            },
        )
        previewList.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    detector.onTouchEvent(e)
                    // Never consumed: the tap-to-remove behaviour on the child must still run.
                    return false
                }
            },
        )
    }

    /** Images committed straight from the IME (GIF keyboards) become attachments. */
    private fun configureKeyboardContent(fragment: AppFragment) {
        val editText = flexInputFragment.view
            ?.findViewById<com.lytefast.flexinput.widget.FlexEditText>(com.lytefast.flexinput.R.id.text_input)
            ?: return
        editText.inputContentHandler = { info ->
            fragment.context?.let { setAttachmentFromPicker(it, info) }
        }
    }

    private fun setAttachmentFromPicker(context: Context, inputContentInfoCompat: InputContentInfoCompat) {
        val resolver = context.contentResolver ?: return
        val defaultName = context.getString(com.discord.R.string.attachment_filename_unknown)
        flexInputFragment.addExternalAttachment(
            SourcedAttachment(
                inputContentInfoCompat.toAttachment(resolver, true, defaultName),
                SOURCE_KEYBOARD,
            ),
        )
    }

    /**
     * Puts [WidgetExpressionTray] into FlexInput's emoji slot and connects its three callbacks back
     * to the composer's own `EditText`.
     */
    private fun configureExpressionTray(fragment: AppFragment): WidgetExpressionTray? {
        val chatInput = flexInputFragment.view
            ?.findViewById<View>(com.lytefast.flexinput.R.id.text_input) as? android.widget.TextView
            ?: return null

        val existing = flexInputFragment.childFragmentManager
            .findFragmentById(com.lytefast.flexinput.R.id.emoji_container) as? WidgetExpressionTray
        val tray = existing ?: WidgetExpressionTray()

        tray.setEmojiPickerListener(
            emojiPickerListener ?: object : EmojiPickerListener {
                override fun onEmojiPicked(emoji: Emoji) {
                    flexInputFragment.append("${emoji.getChatInputText()} ")
                }
            },
        )
        tray.setStickerPickerListener(
            StickerPickerListener { _: Sticker -> flexInputViewModel.hideExpressionTray() },
        )
        tray.setOnBackspacePressedListener {
            val onBackspace = onBackspacePressed
            if (onBackspace != null) {
                onBackspace()
            } else {
                chatInput.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                chatInput.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
        }
        tray.setOnEmojiSearchOpenedListener { flexInputViewModel.hideExpressionTray() }

        flexInputFragment.setEmojiFragment(tray)

        // The tray is only *told* it is showing once the state that shows it has been applied, so
        // its "opened" analytics and scroll-to-top do not fire for a tray nobody can see.
        flexInputViewModel.observeViewState()
            .ui(fragment)
            .subscribe(
                { state ->
                    if (state.expressionTrayShown) flexInputFragment.showEmojiTray() else flexInputFragment.hideEmojiTray()
                    if (tray.isAdded) tray.isShown(state.expressionTrayShown)
                },
                { /* the tray keeps its current visibility */ },
            )
        return tray
    }

    private fun page(
        icon: Int,
        contentDescription: Int,
        create: () -> androidx.fragment.app.Fragment,
    ): AddContentPagerAdapter.PageSupplier =
        object : AddContentPagerAdapter.PageSupplier(icon, contentDescription) {
            override fun createFragment(): androidx.fragment.app.Fragment = create()
        }

    private fun track(attachment: Attachment<Any>, context: Context) {
        val source = (attachment as? SourcedAttachment<*>)?.source ?: SOURCE_MEDIA_PICKER
        try {
            val mimeType = context.contentResolver.getType(attachment.uri).orEmpty()
            AnalyticsTracker.addAttachment(source, mimeType, flexInputFragment.selectionAggregator.size)
        } catch (t: Throwable) {
            // Telemetry must never take the composer down with it.
            AppLog.i("Analytic error on attachment update", t)
        }
    }

    private companion object {
        const val SOURCE_KEYBOARD = "keyboard"
        const val SOURCE_MEDIA_PICKER = "media_picker"

        val R_DRAWABLE_IMAGE = com.lytefast.flexinput.R.drawable.ic_image_24dp
        val R_DRAWABLE_FILE = com.lytefast.flexinput.R.drawable.ic_file_24dp
        val R_DRAWABLE_CAMERA = com.lytefast.flexinput.R.drawable.ic_add_a_photo_24dp
        val R_STRING_PHOTOS = com.lytefast.flexinput.R.string.attachment_photos
        val R_STRING_FILES = com.lytefast.flexinput.R.string.attachment_files
        val R_STRING_CAMERA = com.lytefast.flexinput.R.string.attachment_camera
    }
}
