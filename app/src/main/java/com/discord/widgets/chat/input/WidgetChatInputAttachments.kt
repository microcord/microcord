package com.discord.widgets.chat.input

import android.content.Context
import android.app.Activity
import android.content.AsyncQueryHandler
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.text.TextPaint
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.discord.R
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.permission.Permission
import com.discord.api.sticker.Sticker
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.databinding.FragmentCameraBinding
import com.discord.i18n.setI18nText
import com.discord.models.domain.emoji.Emoji
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.chat.input.attachments.AttachmentBottomSheet
import com.discord.widgets.chat.input.emoji.EmojiPickerListener
import com.discord.widgets.chat.input.expression.WidgetExpressionTray
import com.discord.widgets.chat.polls.PollCreateLauncherFragment
import com.discord.widgets.chat.input.sticker.StickerPickerListener
import com.lytefast.flexinput.InputListener
import com.lytefast.flexinput.FlexInputCoordinator
import com.lytefast.flexinput.adapters.AddContentPagerAdapter
import com.lytefast.flexinput.adapters.AttachmentPreviewAdapter
import com.lytefast.flexinput.adapters.EmptyListAdapter
import com.lytefast.flexinput.adapters.FileListAdapter
import com.lytefast.flexinput.fragment.FilesFragment
import com.lytefast.flexinput.fragment.FlexInputFragment
import com.lytefast.flexinput.fragment.PhotosFragment
import com.lytefast.flexinput.model.Attachment
import com.lytefast.flexinput.model.Attachment.Companion.toAttachment
import com.lytefast.flexinput.utils.SelectionAggregator
import com.lytefast.flexinput.utils.SelectionCoordinator
import com.lytefast.flexinput.utils.FileUtils.toAttachment
import com.otaliastudios.cameraview.CameraException
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.CameraOptions
import com.otaliastudios.cameraview.PictureResult
import com.otaliastudios.cameraview.controls.Facing
import com.otaliastudios.cameraview.controls.Flash
import java.net.URLConnection
import com.discord.utilities.attachments.sanitizeUploadFileName
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import rx.Observable
import rx.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val onBackspacePressed: () -> Unit,
    private val onAttachmentsChanged: (Boolean) -> Unit = {},
) {

    /**
     * @param canCreateThread the reference adds a fourth "create thread" page here. There is no
     *   thread-composer page in this reconstruction yet, so the flag is accepted and the page is
     *   omitted rather than a blank tab being added.
     */
    fun configureFlexInputContentPages(canCreateThread: Boolean) {
        val pages = mutableListOf(
            page(R_DRAWABLE_IMAGE, R_STRING_PHOTOS) { DiscordMediaFragment() },
            page(R_DRAWABLE_FILE, R_STRING_FILES) { DiscordFilesFragment() },
            page(R_DRAWABLE_CAMERA, R_STRING_CAMERA) { DiscordCameraFragment() },
        )
        val channel = StoreStream.getChannelsSelected().getSelectedChannel()
        val permissions = channel?.let { StoreStream.getPermissions().getPermissionsByChannel()[it.id] }
        if (channel != null && ((channel.isPrivate() && !channel.isSystemDM()) ||
                PermissionUtils.can(Permission.SEND_POLLS, permissions))) {
            pages += page(R.drawable.ic_poll_flexinput_24dp, R.string.poll_create) { PollCreateLauncherFragment() }
        }
        flexInputFragment.setContentPages(*pages.toTypedArray())
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
        previewList.addItemDecoration(FilePreviewDecoration(adapter))
        previewList.addItemDecoration(SpoilerPreviewDecoration(adapter))
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
                    val sourced = (attachment as? SourcedAttachment<Any>)
                        ?: SourcedAttachment(attachment, SOURCE_MEDIA_PICKER)
                    sourced.spoiler = spoiler
                    val index = aggregator.attachments.indexOf(attachment)
                    if (index >= 0) {
                        aggregator.attachments[index] = sourced
                        adapter.notifyItemChanged(index)
                    }
                    previewList.invalidateItemDecorations()
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
            fragment.context?.let { setAttachmentFromPicker(fragment, it, info) }
        }
    }

    /**
     * Takes a copy of keyboard-supplied content before queueing it.
     *
     * The URI a keyboard commits is readable only for as long as the grant behind it lives, and
     * that ends with the input session — so an upload that starts after the keyboard closes, or is
     * retried later, finds nothing to read and never finishes. Keyboards also frequently report no
     * size for that URI, and an unknown size reaches the attachment request as `-1`.
     *
     * Copying to the cache settles both: the file is ours, it outlives the keyboard, and its length
     * is known. The copy runs off the main thread because clipboard images are not always small.
     */
    private fun setAttachmentFromPicker(
        fragment: AppFragment,
        context: Context,
        inputContentInfoCompat: InputContentInfoCompat,
    ) {
        val resolver = context.contentResolver ?: return
        val defaultName = context.getString(com.discord.R.string.attachment_filename_unknown)
        val attachment = inputContentInfoCompat.toAttachment(resolver, true, defaultName)
        Observable
            .fromCallable { copyToCache(context, attachment.uri, attachment.displayName ?: defaultName) }
            .subscribeOn(Schedulers.io())
            .ui(fragment)
            .appSubscribe(
                WidgetChatInputAttachments::class.java,
                onTerminated = { runCatching { inputContentInfoCompat.releasePermission() } },
            ) { copied ->
                flexInputFragment.addExternalAttachment(
                    SourcedAttachment(copied.toAttachment(), SOURCE_KEYBOARD),
                )
            }
    }

    private fun copyToCache(context: Context, uri: Uri, displayName: String): File {
        val directory = File(context.cacheDir, KEYBOARD_ATTACHMENT_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}-${sanitizeUploadFileName(displayName)}")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Keyboard attachment content is unavailable")
        input.use { source -> file.outputStream().use(source::copyTo) }
        return file
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
            onBackspacePressed()
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

    /**
     * The documents tab, which hands off to the system document picker.
     *
     * FlexInput's own tab lists a directory it walks itself. Under scoped storage an app cannot
     * read the shared Downloads folder without being a file manager, so that listing is empty on
     * any current device and the tab renders blank with nothing to explain it. The system picker is
     * both the supported route to a document and the only one that can reach cloud providers.
     *
     * The picker is asked for a *persistable* grant, because the bytes are not read at pick time —
     * the send may happen much later, and a one-shot grant will have lapsed by then.
     */
    class DiscordFilesFragment : Fragment(R.layout.widget_chat_input_perm_req_files) {

        /**
         * Guards against re-opening while the picker is already up, and against opening at all
         * before this tab is the one on screen. The tray builds the neighbouring page in advance,
         * so acting on view creation would launch the picker while the gallery is still showing.
         */
        private var pickerOpen = false

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            view.findViewById<TextView>(R.id.perm_req_text)
                ?.setI18nText(R.string.attachment_files_browse_body)
            view.findViewById<View>(R.id.action_btn)?.setOnClickListener { openDocumentPicker() }
        }

        override fun setUserVisibleHint(isVisibleToUser: Boolean) {
            super.setUserVisibleHint(isVisibleToUser)
            if (isVisibleToUser) openIfSelected() else pickerOpen = false
        }

        override fun onResume() {
            super.onResume()
            openIfSelected()
        }

        /** There is nothing to browse in-tab, so selecting it goes straight to the system picker. */
        private fun openIfSelected() {
            if (pickerOpen || !userVisibleHint || !isResumed) return
            openDocumentPicker()
        }

        private fun openDocumentPicker() {
            pickerOpen = true
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            runCatching { startActivityForResult(intent, REQUEST_OPEN_DOCUMENT) }
                .onFailure {
                    pickerOpen = false
                    AppLog.e("No document picker available", it)
                }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode != REQUEST_OPEN_DOCUMENT) return
            // Cancelling returns to an empty tab rather than reopening the picker in a loop.
            if (resultCode != Activity.RESULT_OK) return
            val coordinator = flexInputCoordinator() ?: return
            val resolver = context?.contentResolver ?: return
            pickedUris(data).forEach { uri ->
                // Hold the grant now: this is the last moment the picker's permission is ours to
                // keep, and the file is not read until the message is actually sent.
                runCatching {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                coordinator.addExternalAttachment(
                    SourcedAttachment(uri.toAttachment(resolver), SOURCE_DOCUMENT_PICKER),
                )
            }
        }

        private fun pickedUris(data: Intent?): List<android.net.Uri> {
            val clip = data?.clipData
            if (clip != null) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            return listOfNotNull(data?.data)
        }

        private companion object {
            const val REQUEST_OPEN_DOCUMENT = 4568
        }
    }

    open class DiscordMediaFragment : PhotosFragment() {
        private var grantedSelectionCoordinator: SelectionCoordinator<Attachment<Any>, Attachment<Any>>? = null

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            super.onCreateView(inflater, container, savedInstanceState).also { view ->
                view?.post { if (hasPermissions()) refreshAfterGrant() else requestGallery() }
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
            val coordinator = SelectionCoordinator<Attachment<Any>, Attachment<Any>>()
            flexInputCoordinator()?.selectionAggregator?.registerSelectionCoordinator(coordinator)
            grantedSelectionCoordinator = coordinator
            val thumbnailSide = resources.displayMetrics.widthPixels / MEDIA_GRID_COLUMNS
            recyclerView.layoutManager = GridLayoutManager(context, MEDIA_GRID_COLUMNS)
            val adapter = MediaCursorAdapter(
                context.contentResolver,
                coordinator,
                thumbnailSide,
                viewLifecycleOwner.lifecycleScope,
            )
            recyclerView.adapter = adapter
            recyclerView.invalidateItemDecorations()
            view?.findViewById<SwipeRefreshLayout>(com.lytefast.flexinput.R.id.swipeRefreshLayout)?.let { swipeRefresh ->
                swipeRefresh.setOnRefreshListener {
                    adapter.loadMedia()
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

    private class MediaCursorAdapter(
        private val resolver: ContentResolver,
        coordinator: SelectionCoordinator<Attachment<Any>, Attachment<Any>>,
        private val thumbnailSize: Int,
        private val scope: kotlinx.coroutines.CoroutineScope,
    ) : RecyclerView.Adapter<MediaCursorAdapter.Holder>() {
        private val selection = coordinator.bind(this)
        private var cursor: Cursor? = null
        private var idColumn = 0
        private var nameColumn = 0
        private var typeColumn = 0

        init {
            setHasStableIds(true)
        }

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
            loadMedia()
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            cursor?.close()
            cursor = null
            super.onDetachedFromRecyclerView(recyclerView)
        }

        override fun getItemCount() = cursor?.count ?: 0

        override fun getItemId(position: Int) = item(position)?.id ?: RecyclerView.NO_ID

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(com.lytefast.flexinput.R.layout.view_grid_image, parent, false),
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(item(position), position)

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            val selectionEvent = payloads.filterIsInstance<SelectionCoordinator.SelectionEvent<*>>().firstOrNull()
            if (selectionEvent == null) super.onBindViewHolder(holder, position, payloads)
            else holder.setSelected(selectionEvent.isSelected)
        }

        override fun onViewRecycled(holder: Holder) {
            holder.recycle()
            super.onViewRecycled(holder)
        }

        fun loadMedia() {
            object : AsyncQueryHandler(resolver) {
                override fun onQueryComplete(token: Int, cookie: Any?, result: Cursor?) {
                    result ?: return
                    cursor?.close()
                    cursor = result
                    idColumn = result.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    nameColumn = result.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    typeColumn = result.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    notifyDataSetChanged()
                }
            }.startQuery(
                0,
                null,
                MediaStore.Files.getContentUri("external"),
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                ),
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?",
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                ),
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
            )
        }

        private fun item(position: Int): Attachment<Any>? {
            val cursor = cursor ?: return null
            if (!cursor.moveToPosition(position)) return null
            val id = cursor.getLong(idColumn)
            val mediaType = cursor.getInt(typeColumn)
            val base = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            return Attachment(
                id,
                ContentUris.withAppendedId(base, id),
                cursor.getString(nameColumn) ?: "media-$id",
                mediaType,
            )
        }

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val preview = itemView.findViewById<com.facebook.drawee.view.SimpleDraweeView>(
                com.lytefast.flexinput.R.id.content_iv,
            )
            private val selected = itemView.findViewById<View>(com.lytefast.flexinput.R.id.item_check_indicator)
            private val videoIndicator = ImageView(itemView.context).apply {
                setImageResource(R.drawable.ic_play_arrow_24dp)
                setBackgroundResource(R.drawable.drawable_circle_black)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                visibility = View.GONE
                (itemView as FrameLayout).addView(
                    this,
                    FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER),
                )
            }
            private var thumbnailJob: Job? = null
            private var attachment: Attachment<Any>? = null

            init {
                itemView.setOnClickListener {
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                        ?.let { position -> selection.toggleItem(attachment, position) }
                }
            }

            fun bind(item: Attachment<Any>?, position: Int) {
                recycle()
                attachment = item
                setSelected(item != null && selection.isSelected(item, position), animate = false)
                if (item == null) return
                val isVideo = item.data == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                videoIndicator.visibility = if (isVideo) View.VISIBLE else View.GONE
                if (isVideo) {
                    preview.setImageURI(null as Uri?)
                    preview.hierarchy.setPlaceholderImage(ColorDrawable(Color.DKGRAY))
                    thumbnailJob = scope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            runCatching {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    resolver.loadThumbnail(item.uri, Size(thumbnailSize, thumbnailSize), null)
                                } else {
                                    @Suppress("DEPRECATION")
                                    MediaStore.Video.Thumbnails.getThumbnail(
                                        resolver,
                                        item.id,
                                        MediaStore.Video.Thumbnails.MINI_KIND,
                                        null,
                                    )
                                }
                            }.getOrNull()
                        }
                        if (attachment == item && bitmap != null) {
                            preview.hierarchy.setPlaceholderImage(
                                BitmapDrawable(preview.resources, bitmap),
                                com.facebook.drawee.drawable.ScalingUtils.ScaleType.CENTER_CROP,
                            )
                        }
                    }
                } else {
                    preview.hierarchy.setPlaceholderImage(ColorDrawable(Color.DKGRAY))
                    preview.setImageURI(item.uri)
                }
            }

            fun setSelected(isSelected: Boolean, animate: Boolean = true) {
                selected.visibility = if (isSelected) View.VISIBLE else View.GONE
                val scale = if (isSelected) 0.85f else 1f
                preview.animate().cancel()
                preview.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(
                        if (animate) itemView.resources.getInteger(android.R.integer.config_shortAnimTime).toLong() else 0L,
                    )
                    .start()
            }

            fun recycle() {
                thumbnailJob?.cancel()
                thumbnailJob = null
                attachment = null
                videoIndicator.visibility = View.GONE
            }

            private fun dp(value: Int) = (value * itemView.resources.displayMetrics.density).toInt()
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
            if (appFragment()?.hasCamera() != true) showPermissions() else if (userVisibleHint) showCamera()
        }

        /**
         * The tray keeps the neighbouring page created and resumed, so opening the camera when the
         * view is built would hold the device's camera open while the gallery is on screen — and on
         * a phone with one camera that also stops any other tab from using it.
         */
        override fun setUserVisibleHint(isVisibleToUser: Boolean) {
            super.setUserVisibleHint(isVisibleToUser)
            val binding = _binding ?: return
            if (isVisibleToUser) {
                if (appFragment()?.hasCamera() == true) showCamera()
            } else {
                binding.cameraView.close()
            }
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

    /**
     * Draws a file tile over anything that is not an image.
     *
     * FlexInput hands every attachment to Fresco as a picture. A document has nothing to decode, so
     * the decode fails and — with no failure image on the view — the cell renders empty. Rather
     * than a blank square, the tile names the file and shows its extension, which is the only part
     * of a document worth previewing.
     */
    private class FilePreviewDecoration(
        private val adapter: AttachmentPreviewAdapter<Attachment<Any>>,
    ) : RecyclerView.ItemDecoration() {
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(47, 49, 54) }
        private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        private val extensionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(135, 144, 156)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val videoBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 0, 0, 0) }
        private val videoIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        /** Resolved once per uri: a content lookup on every frame would stutter the strip. */
        private val mimeByUri = mutableMapOf<String, String?>()

        override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val density = parent.resources.displayMetrics.scaledDensity
            namePaint.textSize = 10f * density
            extensionPaint.textSize = 13f * density
            repeat(parent.childCount) { index ->
                val child = parent.getChildAt(index)
                val position = parent.getChildAdapterPosition(child)
                if (position == RecyclerView.NO_POSITION || position >= adapter.selectionAggregator.size) return@repeat
                val attachment = adapter.selectionAggregator[position]
                val mime = mimeByUri.getOrPut(attachment.uri.toString()) {
                    runCatching { parent.context.contentResolver.getType(attachment.uri) }.getOrNull()
                        ?: URLConnection.guessContentTypeFromName(attachment.displayName)
                }
                when {
                    mime?.startsWith("video/") == true -> drawVideoIndicator(canvas, child, density)
                    mime == null || mime.startsWith("image/") -> Unit
                    else -> drawTile(canvas, child, attachment, density)
                }
            }
        }

        private fun drawTile(canvas: Canvas, child: View, attachment: Attachment<Any>, density: Float) {
            val radius = 3f * density
            canvas.drawRoundRect(
                child.left.toFloat(),
                child.top.toFloat(),
                child.right.toFloat(),
                child.bottom.toFloat(),
                radius,
                radius,
                backgroundPaint,
            )
            val name = attachment.displayName.orEmpty()
            val centerX = (child.left + child.right) / 2f
            val extension = name.substringAfterLast('.', "").uppercase().take(MAX_EXTENSION_CHARS)
            canvas.drawText(
                extension.ifEmpty { FALLBACK_EXTENSION },
                centerX,
                (child.top + child.bottom) / 2f,
                extensionPaint,
            )
            // The name is the only way to tell two documents apart, so it is always shown, cut to
            // whatever the tile can hold rather than overflowing into the neighbouring cell.
            val available = (child.right - child.left) - 8f * density
            val label = TextUtils.ellipsize(name, TextPaint(namePaint), available, TextUtils.TruncateAt.MIDDLE)
            canvas.drawText(label, 0, label.length, centerX, child.bottom - 6f * density, namePaint)
        }

        private fun drawVideoIndicator(canvas: Canvas, child: View, density: Float) {
            val centerX = (child.left + child.right) / 2f
            val centerY = (child.top + child.bottom) / 2f
            canvas.drawCircle(centerX, centerY, 14f * density, videoBackgroundPaint)
            val path = android.graphics.Path().apply {
                moveTo(centerX - 4f * density, centerY - 7f * density)
                lineTo(centerX + 8f * density, centerY)
                lineTo(centerX - 4f * density, centerY + 7f * density)
                close()
            }
            canvas.drawPath(path, videoIconPaint)
        }

        private companion object {
            const val MAX_EXTENSION_CHARS = 4
            const val FALLBACK_EXTENSION = "FILE"
        }
    }

    private class SpoilerPreviewDecoration(
        private val adapter: AttachmentPreviewAdapter<Attachment<Any>>,
    ) : RecyclerView.ItemDecoration() {
        private val coverPaint = Paint().apply { color = Color.argb(210, 32, 34, 37) }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            textPaint.textSize = 10f * parent.resources.displayMetrics.scaledDensity
            repeat(parent.childCount) { index ->
                val child = parent.getChildAt(index)
                val position = parent.getChildAdapterPosition(child)
                if (position == RecyclerView.NO_POSITION || position >= adapter.selectionAggregator.size) return@repeat
                if ((adapter.selectionAggregator[position] as? SourcedAttachment<*>)?.spoiler != true) return@repeat
                canvas.drawRect(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat(), coverPaint)
                val baseline = child.exactCenterY() - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(parent.context.getString(R.string.spoiler).uppercase(), child.exactCenterX(), baseline, textPaint)
            }
        }

        private fun View.exactCenterX() = (left + right) / 2f
        private fun View.exactCenterY() = (top + bottom) / 2f
    }

    private companion object {
        const val SOURCE_KEYBOARD = "keyboard"

        private const val KEYBOARD_ATTACHMENT_DIRECTORY = "keyboard-attachments"
        const val SOURCE_MEDIA_PICKER = "media_picker"
        const val SOURCE_DOCUMENT_PICKER = "document_picker"
        const val MEDIA_GRID_COLUMNS = 3

        val R_DRAWABLE_IMAGE = com.lytefast.flexinput.R.drawable.ic_image_24dp
        val R_DRAWABLE_FILE = com.lytefast.flexinput.R.drawable.ic_file_24dp
        val R_DRAWABLE_CAMERA = com.lytefast.flexinput.R.drawable.ic_add_a_photo_24dp
        val R_STRING_PHOTOS = com.lytefast.flexinput.R.string.attachment_photos
        val R_STRING_FILES = com.lytefast.flexinput.R.string.attachment_files
        val R_STRING_CAMERA = com.lytefast.flexinput.R.string.attachment_camera
    }
}

internal fun deletionRangeBeforeCursor(text: CharSequence, selectionStart: Int, selectionEnd: Int): IntRange? {
    if (selectionStart !in 0..text.length || selectionEnd !in 0..text.length) return null
    val from = if (selectionStart == selectionEnd) {
        if (selectionStart == 0) return null
        Character.offsetByCodePoints(text, selectionStart, -1)
    } else {
        minOf(selectionStart, selectionEnd)
    }
    val to = if (selectionStart == selectionEnd) selectionStart else maxOf(selectionStart, selectionEnd)
    return IntRange(from, to)
}

private fun Fragment.appFragment(): AppFragment? =
    generateSequence(parentFragment) { it.parentFragment }.filterIsInstance<AppFragment>().firstOrNull()

@Suppress("UNCHECKED_CAST")
private fun Fragment.flexInputCoordinator(): FlexInputCoordinator<Any>? =
    parentFragment?.parentFragment as? FlexInputCoordinator<Any>
