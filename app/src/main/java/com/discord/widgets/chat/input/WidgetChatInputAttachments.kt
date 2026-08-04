package com.discord.widgets.chat.input

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.discord.R
import com.discord.api.sticker.Sticker
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.databinding.FragmentCameraBinding
import com.discord.i18n.setI18nText
import com.discord.models.domain.emoji.Emoji
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.widgets.chat.input.attachments.AttachmentBottomSheet
import com.discord.widgets.chat.input.emoji.EmojiPickerListener
import com.discord.widgets.chat.input.expression.WidgetExpressionTray
import com.discord.widgets.chat.input.sticker.StickerPickerListener
import com.lytefast.flexinput.InputListener
import com.lytefast.flexinput.FlexInputCoordinator
import com.lytefast.flexinput.adapters.AddContentPagerAdapter
import com.lytefast.flexinput.adapters.AttachmentPreviewAdapter
import com.lytefast.flexinput.adapters.EmptyListAdapter
import com.lytefast.flexinput.adapters.FileListAdapter
import com.lytefast.flexinput.adapters.PhotoCursorAdapter
import com.lytefast.flexinput.fragment.FilesFragment
import com.lytefast.flexinput.fragment.FlexInputFragment
import com.lytefast.flexinput.fragment.PhotosFragment
import com.lytefast.flexinput.model.Attachment
import com.lytefast.flexinput.model.Attachment.Companion.toAttachment
import com.lytefast.flexinput.model.Photo
import com.lytefast.flexinput.utils.SelectionAggregator
import com.lytefast.flexinput.utils.SelectionCoordinator
import com.lytefast.flexinput.utils.FileUtils.toAttachment
import com.otaliastudios.cameraview.CameraException
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.CameraOptions
import com.otaliastudios.cameraview.PictureResult
import com.otaliastudios.cameraview.controls.Facing
import com.otaliastudios.cameraview.controls.Flash
import java.io.File
import kotlinx.coroutines.launch

/**
 * Everything the composer needs from FlexInput: the attachment pages, the attachment preview strip,
 * and the expression tray that lives in FlexInput's emoji slot.
 *
 * Kept out of `WidgetChatInput` because it is all one concern — configuring somebody else's
 * fragment — and because it is the only place that has to know FlexInput's own view ids.
 *
 * **Two deliberate divergences**, both forced by the library:
 *  - The reference's `AttachmentPreviewAdapter` opens attachment actions. The published
 *    `com.github.lytefast:flex-input:0.10.9` adapter removes on tap, so preview gestures are
 *    intercepted by the `RecyclerView` and resolved through the selection aggregator.
 *  - Public FlexInput's aggregator factory replaces the reference adapter constructor while keeping
 *    the same coordinator-listener wrapping. This avoids copying analytics listeners on recreation.
 */
class WidgetChatInputAttachments(
    private val flexInputFragment: FlexInputFragment,
    private val flexInputViewModel: AppFlexInputViewModel,
    /** Overrides the default "append to the end of the composer" behaviour with cursor insertion. */
    private val emojiPickerListener: EmojiPickerListener? = null,
    /** Overrides the default synthetic `KEYCODE_DEL` with the host's own deletion. */
    private val onBackspacePressed: (() -> Unit)? = null,
    private val onAttachmentsChanged: (Boolean) -> Unit = {},
) {

    /**
     * @param canCreateThread the reference adds a fourth "create thread" page here. There is no
     *   thread-composer page in this reconstruction yet, so the flag is accepted and the page is
     *   omitted rather than a blank tab being added.
     */
    fun configureFlexInputContentPages(canCreateThread: Boolean) {
        flexInputFragment.setContentPages(
            page(R_DRAWABLE_IMAGE, R_STRING_PHOTOS) { DiscordMediaFragment() },
            page(R_DRAWABLE_FILE, R_STRING_FILES) { DiscordFilesFragment() },
            page(R_DRAWABLE_CAMERA, R_STRING_CAMERA) { DiscordCameraFragment() },
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
        return configureExpressionTray()
    }

    private fun configurePreviewAdapter(fragment: AppFragment) {
        val context = fragment.context ?: return
        val adapter = AttachmentPreviewAdapter<Attachment<Any>>(context.contentResolver) { previewAdapter ->
            object : SelectionAggregator<Attachment<Any>>(previewAdapter) {
                override fun registerSelectionCoordinatorInternal(
                    selectionCoordinator: SelectionCoordinator<Attachment<Any>, *>,
                ) {
                    super.registerSelectionCoordinatorInternal(selectionCoordinator)
                    val delegate = selectionCoordinator.itemSelectionListener
                    selectionCoordinator.itemSelectionListener =
                        object : SelectionCoordinator.ItemSelectionListener<Attachment<Any>> {
                            override fun onItemSelected(item: Attachment<Any>) {
                                track(item, context)
                                delegate.onItemSelected(item)
                            }

                            override fun onItemUnselected(item: Attachment<Any>) {
                                delegate.onItemUnselected(item)
                            }

                            override fun unregister() = delegate.unregister()
                        }
                }
            }
        }
        flexInputFragment.setAttachmentPreviewAdapter(adapter)
        adapter.selectionAggregator.addItemSelectionListener(
            object : SelectionCoordinator.ItemSelectionListener<Attachment<Any>> {
                override fun onItemSelected(item: Attachment<Any>) = notifyAttachmentsChanged(fragment, adapter)
                override fun onItemUnselected(item: Attachment<Any>) = notifyAttachmentsChanged(fragment, adapter)
            },
        )

        val previewList = flexInputFragment.view
            ?.findViewById<RecyclerView>(com.lytefast.flexinput.R.id.attachment_preview_list)
            ?: return
        installLongPressToOpenSheet(fragment, previewList, adapter)
    }

    private fun notifyAttachmentsChanged(
        fragment: AppFragment,
        adapter: AttachmentPreviewAdapter<Attachment<Any>>,
    ) {
        fragment.view?.post {
            onAttachmentsChanged(adapter.selectionAggregator.size > 0)
        }
    }

    /** Opens Discord's spoiler/remove sheet instead of FlexInput's tap-to-remove behavior. */
    private fun installLongPressToOpenSheet(
        fragment: AppFragment,
        previewList: RecyclerView,
        adapter: AttachmentPreviewAdapter<Attachment<Any>>,
    ) {
        var consumeGesture = false
        fun showAttachment(event: MotionEvent): Boolean {
            val child = previewList.findChildViewUnder(event.x, event.y) ?: return false
            val position = previewList.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) return false
            val aggregator = adapter.selectionAggregator
            if (position >= aggregator.size) return false
            val attachment = aggregator[position]
            fragment.hideKeyboard(child)
            AttachmentBottomSheet.show(
                fragmentManager = flexInputFragment.childFragmentManager,
                attachment = attachment,
                spoiler = (attachment as? SourcedAttachment<*>)?.spoiler == true,
                onFileRemoved = { aggregator.unselectItem(attachment) },
                onMarkSpoiler = { spoiler ->
                    (attachment as? SourcedAttachment<*>)?.spoiler = spoiler
                    adapter.notifyDataSetChanged()
                },
            )
            return true
        }
        val detector = GestureDetector(
            previewList.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean {
                    consumeGesture = false
                    return true
                }

                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    consumeGesture = showAttachment(event)
                    return consumeGesture
                }

                override fun onLongPress(event: MotionEvent) {
                    consumeGesture = showAttachment(event)
                }
            },
        )
        previewList.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    detector.onTouchEvent(e)
                    return consumeGesture
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                    detector.onTouchEvent(e)
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
    private fun configureExpressionTray(): WidgetExpressionTray? {
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

    open class DiscordFilesFragment : FilesFragment() {
        private var grantedSelectionCoordinator: SelectionCoordinator<Attachment<Any>, Attachment<File>>? = null

        override fun hasPermissions(vararg requiredPermissionList: String): Boolean =
            appFragment()?.hasGallery() == true

        override fun newPermissionsRequestAdapter(onClickListener: View.OnClickListener): EmptyListAdapter =
            PermissionsEmptyListAdapter(View.OnClickListener { requestGallery() })

        private fun requestGallery() {
            requestGalleryPermission(::refreshAfterGrant)
        }

        protected open fun requestGalleryPermission(onGranted: () -> Unit) =
            appFragment()?.requestGallery(onGranted)

        internal fun refreshAfterGrant() {
            val context = context ?: return
            val recyclerView = view?.findViewById<RecyclerView>(com.lytefast.flexinput.R.id.list) ?: return
            grantedSelectionCoordinator?.close()
            val coordinator = SelectionCoordinator<Attachment<Any>, Attachment<File>>()
            flexInputCoordinator()?.selectionAggregator?.registerSelectionCoordinator(coordinator)
            grantedSelectionCoordinator = coordinator
            val adapter = FileListAdapter(context.contentResolver, coordinator)
            recyclerView.adapter = adapter
            val swipeRefresh = view?.findViewById<SwipeRefreshLayout>(com.lytefast.flexinput.R.id.swipeRefreshLayout)
            fun loadFiles() {
                viewLifecycleOwner.lifecycleScope.launch {
                    adapter.load(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                    swipeRefresh?.isRefreshing = false
                }
            }
            swipeRefresh?.setOnRefreshListener(::loadFiles)
            loadFiles()
        }

        override fun onDestroyView() {
            grantedSelectionCoordinator?.close()
            grantedSelectionCoordinator = null
            super.onDestroyView()
        }
    }

    open class DiscordMediaFragment : PhotosFragment() {
        private var grantedSelectionCoordinator: SelectionCoordinator<Attachment<Any>, Photo>? = null

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            super.onCreateView(inflater, container, savedInstanceState).also { view ->
                if (!hasPermissions()) view?.post(::requestGallery)
            }

        override fun hasPermissions(vararg requiredPermissionList: String): Boolean =
            appFragment()?.hasGallery() == true

        override fun newPermissionsRequestAdapter(onClickListener: View.OnClickListener): EmptyListAdapter =
            PermissionsEmptyListAdapter(View.OnClickListener { requestGallery() })

        private fun requestGallery() {
            requestGalleryPermission(::refreshAfterGrant)
        }

        protected open fun requestGalleryPermission(onGranted: () -> Unit) =
            appFragment()?.requestGallery(onGranted)

        internal fun refreshAfterGrant() {
            val context = context ?: return
            val recyclerView = view?.findViewById<RecyclerView>(com.lytefast.flexinput.R.id.list) ?: return
            grantedSelectionCoordinator?.close()
            val coordinator = SelectionCoordinator<Attachment<Any>, Photo>()
            flexInputCoordinator()?.selectionAggregator?.registerSelectionCoordinator(coordinator)
            grantedSelectionCoordinator = coordinator
            val thumbnailSide = resources.displayMetrics.widthPixels / MEDIA_GRID_COLUMNS
            recyclerView.layoutManager = GridLayoutManager(context, MEDIA_GRID_COLUMNS)
            val adapter = PhotoCursorAdapter(
                context.contentResolver,
                coordinator,
                thumbnailSide,
                thumbnailSide,
            )
            recyclerView.adapter = adapter
            recyclerView.invalidateItemDecorations()
            view?.findViewById<SwipeRefreshLayout>(com.lytefast.flexinput.R.id.swipeRefreshLayout)?.let { swipeRefresh ->
                swipeRefresh.setOnRefreshListener {
                    adapter.loadPhotos()
                    swipeRefresh.isRefreshing = false
                }
            }
        }

        override fun onDestroyView() {
            grantedSelectionCoordinator?.close()
            grantedSelectionCoordinator = null
            super.onDestroyView()
        }
    }

    class DiscordCameraFragment : Fragment(R.layout.fragment_camera) {
        private var _binding: FragmentCameraBinding? = null
        private val binding get() = requireNotNull(_binding)
        private var pendingCapture: File? = null

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _binding = FragmentCameraBinding.bind(view)
            binding.cameraView.setLifecycleOwner(viewLifecycleOwner)
            binding.cameraView.addCameraListener(object : CameraListener() {
                override fun onCameraOpened(options: CameraOptions) {
                    binding.cameraFacingBtn.visibility =
                        if (options.supportedFacing.size > 1) View.VISIBLE else View.GONE
                    binding.cameraFlashBtn.visibility =
                        if (options.supportedFlash.size > 1) View.VISIBLE else View.GONE
                }

                override fun onPictureTaken(result: PictureResult) {
                    val coordinator = flexInputCoordinator() ?: return
                    val file = coordinator.fileManager.newImageFile()
                    result.toFile(file) { saved ->
                        if (saved == null) AppLog.e("Could not save camera attachment")
                        else coordinator.addExternalAttachment(saved.toAttachment())
                    }
                }

                override fun onCameraError(exception: CameraException) {
                    AppLog.e("Camera capture failed", exception)
                }
            })
            binding.takePhotoBtn.setOnClickListener { binding.cameraView.takePicture() }
            binding.cameraFacingBtn.setOnClickListener {
                binding.cameraView.facing =
                    if (binding.cameraView.facing == Facing.BACK) Facing.FRONT else Facing.BACK
            }
            binding.cameraFlashBtn.setOnClickListener {
                binding.cameraView.flash = when (binding.cameraView.flash) {
                    Flash.OFF -> Flash.AUTO
                    Flash.AUTO -> Flash.ON
                    else -> Flash.OFF
                }
            }
            binding.launchCameraBtn.setOnClickListener { launchSystemCamera() }
            if (appFragment()?.hasCamera() == true) showCamera() else showPermissions()
        }

        private fun showPermissions() {
            binding.cameraContainer.visibility = View.GONE
            binding.permissionsContainer.visibility = View.VISIBLE
            if (binding.permissionsContainer.childCount == 0) {
                LayoutInflater.from(requireContext()).inflate(
                    com.lytefast.flexinput.R.layout.view_camera_permissions,
                    binding.permissionsContainer,
                    true,
                ).findViewById<View>(com.lytefast.flexinput.R.id.permissions_req_btn)
                    ?.setOnClickListener { appFragment()?.requestCamera(::showCamera) }
            }
        }

        private fun showCamera() {
            val binding = _binding ?: return
            binding.permissionsContainer.visibility = View.GONE
            binding.cameraContainer.visibility = View.VISIBLE
            binding.cameraView.open()
        }

        private fun launchSystemCamera() {
            val coordinator = flexInputCoordinator() ?: return
            val file = coordinator.fileManager.newImageFile()
            val uri = coordinator.fileManager.toFileProviderUri(requireContext(), file)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            requireContext().packageManager.queryIntentActivities(intent, 0).forEach {
                requireContext().grantUriPermission(
                    it.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            pendingCapture = file
            startActivityForResult(intent, REQUEST_IMAGE_CAPTURE)
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode != REQUEST_IMAGE_CAPTURE) return
            val file = pendingCapture.also { pendingCapture = null } ?: return
            if (resultCode == Activity.RESULT_OK) flexInputCoordinator()?.addExternalAttachment(file.toAttachment())
            else file.delete()
        }

        override fun onDestroyView() {
            _binding = null
            super.onDestroyView()
        }

        private companion object {
            const val REQUEST_IMAGE_CAPTURE = 4567
        }
    }

    private class PermissionsEmptyListAdapter(onClickListener: View.OnClickListener) :
        EmptyListAdapter(R.layout.widget_chat_input_perm_req_files, R.id.action_btn, onClickListener) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmptyListAdapter.ViewHolder =
            super.onCreateViewHolder(parent, viewType).also {
                it.itemView.findViewById<TextView>(R.id.perm_req_text)
                    .setI18nText(R.string.system_permission_request_files)
            }
    }

    private companion object {
        const val SOURCE_KEYBOARD = "keyboard"
        const val SOURCE_MEDIA_PICKER = "media_picker"
        const val MEDIA_GRID_COLUMNS = 3

        val R_DRAWABLE_IMAGE = com.lytefast.flexinput.R.drawable.ic_image_24dp
        val R_DRAWABLE_FILE = com.lytefast.flexinput.R.drawable.ic_file_24dp
        val R_DRAWABLE_CAMERA = com.lytefast.flexinput.R.drawable.ic_add_a_photo_24dp
        val R_STRING_PHOTOS = com.lytefast.flexinput.R.string.attachment_photos
        val R_STRING_FILES = com.lytefast.flexinput.R.string.attachment_files
        val R_STRING_CAMERA = com.lytefast.flexinput.R.string.attachment_camera
    }
}

private fun Fragment.appFragment(): AppFragment? =
    generateSequence(parentFragment) { it.parentFragment }.filterIsInstance<AppFragment>().firstOrNull()

@Suppress("UNCHECKED_CAST")
private fun Fragment.flexInputCoordinator(): FlexInputCoordinator<Any>? =
    parentFragment?.parentFragment as? FlexInputCoordinator<Any>
