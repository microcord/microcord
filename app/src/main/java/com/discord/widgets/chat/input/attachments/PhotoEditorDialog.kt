package com.discord.widgets.chat.input.attachments

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppDialog
import com.discord.databinding.DialogPhotoEditorBinding
import com.discord.databinding.DialogPhotoEditorCropBinding
import com.discord.databinding.DialogPhotoEditorTextBinding
import com.discord.databinding.PhotoEditorFilterItemBinding
import com.discord.dialogs.SimpleConfirmationDialog
import com.discord.dialogs.SimpleConfirmationDialogArgs
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.utilities.images.MGImagesBitmap
import com.discord.widgets.chat.input.emoji.EmojiPickerContextType
import com.discord.widgets.chat.input.emoji.EmojiPickerListener
import com.discord.widgets.chat.input.emoji.EmojiPickerNavigator
import com.discord.widgets.chat.input.sticker.StickerPickerListener
import com.discord.widgets.chat.input.sticker.WidgetStickerPickerSheet
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoFilter
import ja.burhanrashid52.photoeditor.OnPhotoEditorListener
import ja.burhanrashid52.photoeditor.SaveSettings
import ja.burhanrashid52.photoeditor.TextStyleBuilder
import ja.burhanrashid52.photoeditor.ViewType
import java.io.File
import java.util.UUID
import java.net.URL
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rx.Subscription
import rx.schedulers.Schedulers

private val PHOTO_EDITOR_COLORS = intArrayOf(
    Color.WHITE, Color.BLACK, 0xffef4444.toInt(), 0xfff97316.toInt(),
    0xffeab308.toInt(), 0xff22c55e.toInt(), 0xff38bdf8.toInt(), 0xffa855f7.toInt(),
)

class PhotoEditorDialog : DialogFragment() {
    private var _binding: DialogPhotoEditorBinding? = null
    private val binding get() = requireNotNull(_binding)
    private lateinit var editor: PhotoEditor
    private var imageSubscription: Subscription? = null
    private var spoiler = false
    private var brushColor = Color.WHITE
    private var brushSize = 24f
    private var editingText: View? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadBitmap(it, ::addOverlay) }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext(), android.R.style.Theme_Translucent_NoTitleBar_Fullscreen).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = DialogPhotoEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        spoiler = requireArguments().getBoolean(ARG_SPOILER)
        editor = PhotoEditor.Builder(requireContext(), binding.photoEditorView)
            .setPinchTextScalable(true)
            .setClipSourceImage(false)
            .build()
        binding.photoEditorCanvas.isMotionEventSplittingEnabled = false
        binding.photoEditorView.isMotionEventSplittingEnabled = false
        editor.setOnPhotoEditorListener(object : OnPhotoEditorListener {
            override fun onEditTextChangeListener(rootView: View, text: String, colorCode: Int) {
                editingText = rootView
                PhotoEditorTextDialog.show(parentFragmentManager, text, colorCode, editing = true)
            }
            override fun onAddViewListener(viewType: ViewType, numberOfAddedViews: Int) = Unit
            override fun onRemoveViewListener(viewType: ViewType, numberOfAddedViews: Int) = Unit
            override fun onStartViewChangeListener(viewType: ViewType) = Unit
            override fun onStopViewChangeListener(viewType: ViewType) = Unit
            override fun onTouchSourceImage(event: MotionEvent) = Unit
        })
        ViewCompat.setOnApplyWindowInsetsListener(binding.photoEditorRoot) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        parentFragmentManager.setFragmentResultListener(TEXT_RESULT_KEY, this) { _, result ->
            if (result.getBoolean(TEXT_DELETE)) {
                editingText?.let(binding.photoEditorView::removeView)
                editingText = null
                return@setFragmentResultListener
            }
            result.getString(TEXT_RESULT)?.takeIf(String::isNotBlank)?.let { text ->
                val style = TextStyleBuilder().apply {
                    withTextColor(result.getInt(TEXT_COLOR, Color.WHITE))
                    withTextSize(result.getFloat(TEXT_SIZE, 28f))
                    withTextFont(Typeface.create(result.getString(TEXT_FONT) ?: "sans-serif", result.getInt(TEXT_STYLE)))
                    if (result.getBoolean(TEXT_UNDERLINE)) withTextFlag(Paint.UNDERLINE_TEXT_FLAG)
                }
                editingText?.let { editor.editText(it, text, style); cleanOverlay(it) } ?: run {
                    editor.addText(text, style)
                    cleanOverlay(binding.photoEditorView.getChildAt(binding.photoEditorView.childCount - 1))
                }
                editingText = null
            }
        }
        configureHeader()
        configureDrawToolbar()
        configureMainToolbar()
        configureFilters()
        binding.photoEditorSpoilerOverlay.visibility = if (spoiler) View.VISIBLE else View.GONE
        binding.photoEditorSpoilerOverlay.setOnClickListener { it.visibility = View.GONE }
        loadBitmap(Uri.parse(requireArguments().getString(ARG_URI))) { bitmap ->
            binding.photoEditorView.source.setImageBitmap(bitmap)
            fitCanvas(bitmap)
            binding.photoEditorProgress.visibility = View.GONE
            enablePen()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            statusBarColor = EDITOR_CHROME_COLOR
            navigationBarColor = EDITOR_CHROME_COLOR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
            WindowInsetsControllerCompat(this, decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        ViewCompat.requestApplyInsets(binding.photoEditorRoot)
    }

    override fun onDestroyView() {
        imageSubscription?.unsubscribe()
        imageSubscription = null
        _binding = null
        super.onDestroyView()
    }

    private fun configureHeader() = with(binding) {
        photoEditorTitle.text = requireArguments().getString(ARG_NAME) ?: getString(R.string.photo_editor)
        photoEditorClose.setOnClickListener { dismiss() }
        photoEditorSpoiler.setColorFilter(if (spoiler) DANGER else Color.WHITE)
        photoEditorSpoiler.setOnClickListener {
            spoiler = !spoiler
            photoEditorSpoiler.setColorFilter(if (spoiler) DANGER else Color.WHITE)
            photoEditorSpoilerOverlay.visibility = if (spoiler) View.VISIBLE else View.GONE
        }
        photoEditorDelete.setOnClickListener {
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_DELETED to true))
            dismiss()
        }
        photoEditorSave.setOnClickListener { save() }
    }

    private fun configureMainToolbar() = with(binding) {
        photoEditorDraw.isSelected = true
        photoEditorUndo.setOnClickListener { editor.undo() }
        photoEditorRedo.setOnClickListener { editor.redo() }
        photoEditorReset.setOnClickListener { editor.clearAllViews() }
        photoEditorDraw.setOnClickListener { selectMainTool(it); photoEditorSubToolbar.displayedChild = DRAW_TOOLBAR; enablePen() }
        photoEditorText.setOnClickListener { selectMainTool(null); editingText = null; showTextDialog() }
        photoEditorEmoji.setOnClickListener { selectMainTool(null); showEmojiPicker() }
        photoEditorSticker.setOnClickListener { selectMainTool(null); showStickerPicker() }
        photoEditorImage.setOnClickListener { selectMainTool(null); imagePicker.launch("image/*") }
        photoEditorCrop.setOnClickListener { selectMainTool(null); showCropDialog() }
        photoEditorFilter.setOnClickListener { selectMainTool(it); editor.setBrushDrawingMode(false); photoEditorSubToolbar.displayedChild = FILTER_TOOLBAR }
        listOf(photoEditorUndo, photoEditorRedo, photoEditorReset, photoEditorDraw, photoEditorText,
            photoEditorEmoji, photoEditorSticker, photoEditorImage, photoEditorCrop, photoEditorFilter,
        ).forEach { button ->
            button.setOnLongClickListener {
                Toast.makeText(context, button.contentDescription, Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun configureDrawToolbar() = with(binding) {
        photoEditorBrushSize.value = brushSize
        photoEditorBrushSizeLabel.text = getString(R.string.photo_editor_brush_size, brushSize.toInt())
        photoEditorBrushSize.addOnChangeListener { _, value, _ ->
            brushSize = value
            photoEditorBrushSizeLabel.text = getString(R.string.photo_editor_brush_size, value.toInt())
            enablePen()
        }
        photoEditorPen.isSelected = true
        photoEditorPen.setOnClickListener { photoEditorPen.isSelected = true; photoEditorErase.isSelected = false; enablePen() }
        photoEditorErase.setOnClickListener { photoEditorPen.isSelected = false; photoEditorErase.isSelected = true; editor.brushEraser() }
        val swatches = listOf(photoEditorColorWhite, photoEditorColorBlack, photoEditorColorRed,
            photoEditorColorOrange, photoEditorColorYellow, photoEditorColorGreen,
            photoEditorColorBlue, photoEditorColorPurple).zip(PHOTO_EDITOR_COLORS.toList())
        swatches.forEach { (view, color) ->
            updateSwatch(view, color, color == brushColor)
            view.setOnClickListener {
                brushColor = color
                swatches.forEach { (candidate, candidateColor) -> updateSwatch(candidate, candidateColor, candidateColor == color) }
                enablePen()
            }
        }
    }

    private fun configureFilters() {
        binding.photoEditorFilters.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        binding.photoEditorFilters.adapter = FilterAdapter { filter -> editor.setFilterEffect(filter) }
    }

    private fun showTextDialog() {
        editor.setBrushDrawingMode(false)
        PhotoEditorTextDialog.show(parentFragmentManager)
    }

    private fun showEmojiPicker() = EmojiPickerNavigator.launchBottomSheet(
        parentFragmentManager,
        object : EmojiPickerListener {
            override fun onEmojiPicked(emoji: Emoji) {
                val uri = emoji.getImageUri(false, 320, requireContext())
                if (uri.startsWith("res:///")) {
                    addUnicodeEmoji(emoji as? ModelEmojiUnicode, uri.substringAfterLast('/').toIntOrNull())
                } else {
                    addRemoteImage(uri)
                }
            }
        },
        EmojiPickerContextType.Chat,
    )

    private fun showStickerPicker() = WidgetStickerPickerSheet.show(
        parentFragmentManager,
        StickerPickerListener { sticker ->
            val url = "https://media.discordapp.net/stickers/${sticker.getStickerId()}.png?size=320"
            loadAsync(::addOverlay) { URL(url).openStream().use(BitmapFactory::decodeStream) }
        },
        selectionOnly = true,
    )

    private fun addRemoteImage(url: String) {
        imageSubscription?.unsubscribe()
        imageSubscription = MGImagesBitmap.getBitmap(url, false)
            .subscribeOn(Schedulers.io())
            .subscribe({ bitmap -> binding.root.post { addOverlay(bitmap) } }, { error ->
                binding.root.post { Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show() }
            })
    }

    private fun addUnicodeEmoji(emoji: ModelEmojiUnicode?, fallbackResource: Int?) {
        val context = requireContext().applicationContext
        loadAsync(::addOverlay) {
            val names = emoji?.codePoints?.replace('_', '-')?.let { listOf(it, it.replace("-fe0f", "")) }.orEmpty().distinct()
            names.firstNotNullOfOrNull { name ->
                runCatching {
                    URL("https://cdn.jsdelivr.net/gh/jdecked/twemoji@latest/assets/svg/$name.svg").openStream().use { stream ->
                        val svg = SVG.getFromInputStream(stream).apply { documentWidth = 512f; documentHeight = 512f }
                        Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
                    }
                }.getOrNull()
            } ?: fallbackResource
                ?.let { AppCompatResources.getDrawable(context, it) }
                ?.toBitmap(320, 320, Bitmap.Config.ARGB_8888)
        }
    }

    private fun addOverlay(bitmap: Bitmap) {
        editor.addImage(bitmap)
        val overlay = binding.photoEditorView.getChildAt(binding.photoEditorView.childCount - 1)
        cleanOverlay(overlay)
        overlay.setOnTouchListener(PhotoEditorOverlayTouchListener(overlay) {
            SimpleConfirmationDialog.show(
                parentFragmentManager,
                SimpleConfirmationDialogArgs(
                    title = getString(R.string.photo_editor_remove_overlay),
                    description = getString(R.string.photo_editor_remove_overlay_body),
                    positiveButtonText = getString(R.string.remove),
                    negativeButtonText = getString(R.string.cancel),
                ),
                positiveClickListener = View.OnClickListener { binding.photoEditorView.removeView(overlay) },
            )
        })
    }

    private fun cleanOverlay(overlay: View) {
        overlay.findViewById<View>(ja.burhanrashid52.photoeditor.R.id.imgPhotoEditorClose)?.let { close ->
            (close.parent as? ViewGroup)?.removeView(close)
        }
        overlay.findViewById<View>(ja.burhanrashid52.photoeditor.R.id.frmBorder)?.let { border ->
            border.background = null
            ViewCompat.setBackgroundTintList(border, ColorStateList.valueOf(Color.TRANSPARENT))
        }
    }

    private fun showCropDialog() {
        val drawable = binding.photoEditorView.source.drawable as? android.graphics.drawable.BitmapDrawable ?: return
        val source = drawable.bitmap
        val cropBinding = DialogPhotoEditorCropBinding.inflate(layoutInflater)
        val cropDialog = Dialog(requireContext()).apply { requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(cropBinding.root) }
        var rotation = 0f
        var flipH = false
        var flipV = false
        var preview = source
        // The crop overlay works in the preview's own coordinates, so the preview keeps the photo's
        // aspect ratio: as wide as the sheet, and up to [CROP_PREVIEW_MAX_HEIGHT] of the screen tall.
        fun fitPreview() {
            val box = cropBinding.photoEditorCropBox
            if (box.width == 0) return
            val maxHeight = resources.displayMetrics.heightPixels * CROP_PREVIEW_MAX_HEIGHT
            val scale = minOf(box.width.toFloat() / preview.width, maxHeight / preview.height)
            val width = (preview.width * scale).toInt()
            val height = (preview.height * scale).toInt()
            box.post {
                box.updateLayoutParams<ViewGroup.LayoutParams> { this.height = height }
                cropBinding.photoEditorCropFrame.updateLayoutParams<ViewGroup.LayoutParams> {
                    this.width = width
                    this.height = height
                }
            }
        }
        cropBinding.photoEditorCropBox.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) fitPreview()
        }
        fun updatePreview() {
            val matrix = Matrix().apply { postRotate(rotation); postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f) }
            preview = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            cropBinding.photoEditorCropPreview.setImageBitmap(preview)
            fitPreview()
        }
        cropBinding.photoEditorCropRotation.addOnChangeListener { _, value, _ -> rotation = value; updatePreview() }
        cropBinding.photoEditorCropFlipH.setOnClickListener { flipH = !flipH; it.isSelected = flipH; updatePreview() }
        cropBinding.photoEditorCropFlipV.setOnClickListener { flipV = !flipV; it.isSelected = flipV; updatePreview() }
        cropBinding.photoEditorCropRotate.setOnClickListener { rotation = if (rotation + 90f > 180f) rotation - 270f else rotation + 90f; cropBinding.photoEditorCropRotation.value = rotation }
        cropBinding.photoEditorCropCancel.setOnClickListener { cropDialog.dismiss() }
        cropBinding.photoEditorCropApply.setOnClickListener {
            val rect = cropBinding.photoEditorCropOverlay.cropPercent()
            val left = (preview.width * rect.left).toInt().coerceIn(0, preview.width - 1)
            val top = (preview.height * rect.top).toInt().coerceIn(0, preview.height - 1)
            val width = (preview.width * rect.width()).toInt().coerceIn(1, preview.width - left)
            val height = (preview.height * rect.height()).toInt().coerceIn(1, preview.height - top)
            val cropped = Bitmap.createBitmap(preview, left, top, width, height)
            editor.setFilterEffect(PhotoFilter.NONE)
            binding.photoEditorView.source.setImageBitmap(cropped)
            fitCanvas(cropped)
            cropDialog.dismiss()
        }
        updatePreview()
        cropDialog.setOnShowListener {
            cropDialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setGravity(Gravity.BOTTOM)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.6f }
            }
        }
        cropDialog.show()
    }

    private fun fitCanvas(bitmap: Bitmap) = binding.photoEditorCanvas.post {
        val holder = binding.photoEditorCanvas
        val scale = minOf(holder.width.toFloat() / bitmap.width, holder.height.toFloat() / bitmap.height)
        binding.photoEditorView.layoutParams = android.widget.FrameLayout.LayoutParams(
            (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), Gravity.CENTER,
        )
        binding.photoEditorView.source.scaleType = android.widget.ImageView.ScaleType.FIT_XY
    }

    private fun save() {
        val output = File(requireContext().cacheDir, "photo-editor/${UUID.randomUUID()}.png").apply { parentFile?.mkdirs() }
        binding.photoEditorProgress.visibility = View.VISIBLE
        editor.clearHelperBox()
        editor.saveAsFile(
            output.absolutePath,
            SaveSettings.Builder().setClearViewsEnabled(false).setTransparencyEnabled(true).build(),
            object : PhotoEditor.OnSaveListener {
                override fun onSuccess(imagePath: String) {
                    parentFragmentManager.setFragmentResult(
                        RESULT_KEY,
                        bundleOf(
                            RESULT_URI to Uri.fromFile(output).toString(),
                            RESULT_NAME to editedName(requireArguments().getString(ARG_NAME)),
                            RESULT_SPOILER to spoiler,
                        ),
                    )
                    dismiss()
                }

                override fun onFailure(exception: Exception) {
                    binding.photoEditorProgress.visibility = View.GONE
                    Toast.makeText(context, exception.message ?: getString(R.string.photo_editor_save_failed), Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    private fun loadBitmap(uri: Uri, loaded: (Bitmap) -> Unit) {
        val context = requireContext().applicationContext
        loadAsync(loaded) {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                }
        }
    }

    private fun loadAsync(loaded: (Bitmap) -> Unit, load: () -> Bitmap?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching(load).getOrNull() }
            if (bitmap != null) loaded(bitmap)
            else Toast.makeText(context, R.string.photo_editor_load_failed, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun enablePen() {
        editor.setBrushDrawingMode(true)
        editor.brushColor = brushColor
        editor.brushSize = brushSize
    }

    private fun selectMainTool(selected: View?) = with(binding) {
        listOf(photoEditorDraw, photoEditorText, photoEditorEmoji, photoEditorSticker, photoEditorImage, photoEditorCrop, photoEditorFilter)
            .forEach { it.isSelected = it === selected }
    }

    private fun updateSwatch(view: View, color: Int, selected: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(if (selected) 3 else 1), if (selected) Color.WHITE else 0xff4e5058.toInt())
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class FilterAdapter(private val selected: (PhotoFilter) -> Unit) :
        RecyclerView.Adapter<FilterAdapter.Holder>() {
        private var selectedPosition = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            PhotoEditorFilterItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

        override fun getItemCount() = FILTERS.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(FILTERS[position], position == selectedPosition)

        inner class Holder(private val binding: PhotoEditorFilterItemBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(filter: PhotoFilter, isSelected: Boolean) {
                binding.root.text = filter.name.replace('_', ' ')
                binding.root.isSelected = isSelected
                binding.root.setOnClickListener {
                    val previous = selectedPosition
                    selectedPosition = bindingAdapterPosition
                    notifyItemChanged(previous)
                    notifyItemChanged(selectedPosition)
                    selected(filter)
                }
            }
        }
    }

    companion object {
        const val RESULT_KEY = "photo_editor_result"
        const val RESULT_URI = "uri"
        const val RESULT_NAME = "name"
        const val RESULT_SPOILER = "spoiler"
        const val RESULT_DELETED = "deleted"
        private const val ARG_URI = "uri"
        private const val ARG_NAME = "name"
        private const val ARG_SPOILER = "spoiler"
        private const val DRAW_TOOLBAR = 0
        private const val FILTER_TOOLBAR = 1
        internal const val TEXT_RESULT_KEY = "photo_editor_text_result"
        internal const val TEXT_RESULT = "text"
        internal const val TEXT_COLOR = "color"
        internal const val TEXT_SIZE = "size"
        internal const val TEXT_FONT = "font"
        internal const val TEXT_STYLE = "style"
        internal const val TEXT_UNDERLINE = "underline"
        internal const val TEXT_DELETE = "delete"
        private const val DANGER = 0xffda373c.toInt()
        private const val EDITOR_CHROME_COLOR = 0xff1e1f22.toInt()
        /** Share of the screen height a tall photo may take in the crop sheet. */
        private const val CROP_PREVIEW_MAX_HEIGHT = 0.55f
        private val FILTERS = arrayOf(PhotoFilter.NONE, PhotoFilter.AUTO_FIX, PhotoFilter.BRIGHTNESS, PhotoFilter.CONTRAST, PhotoFilter.DOCUMENTARY, PhotoFilter.DUE_TONE, PhotoFilter.FILL_LIGHT, PhotoFilter.FISH_EYE, PhotoFilter.GRAIN, PhotoFilter.GRAY_SCALE, PhotoFilter.LOMISH, PhotoFilter.NEGATIVE, PhotoFilter.POSTERIZE, PhotoFilter.SATURATE, PhotoFilter.SEPIA, PhotoFilter.SHARPEN, PhotoFilter.TEMPERATURE, PhotoFilter.TINT, PhotoFilter.VIGNETTE)

        fun show(fragmentManager: androidx.fragment.app.FragmentManager, uri: Uri, name: String?, spoiler: Boolean) {
            PhotoEditorDialog().apply {
                arguments = bundleOf(ARG_URI to uri.toString(), ARG_NAME to name, ARG_SPOILER to spoiler)
            }.show(fragmentManager, PhotoEditorDialog::class.java.simpleName)
        }

        private fun editedName(name: String?) = "${name?.substringBeforeLast('.', name) ?: "image"}-edited.png"
    }
}

class PhotoEditorTextDialog : AppDialog(R.layout.dialog_photo_editor_text) {
    private val binding get() = DialogPhotoEditorTextBinding.bind(requireView())
    private var color = Color.WHITE
    private var size = 28f
    private var bold = false
    private var italic = false
    private var underline = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        color = arguments?.getInt(ARG_COLOR, Color.WHITE) ?: Color.WHITE
        binding.photoEditorTextInput.setText(arguments?.getString(ARG_TEXT).orEmpty())
        val fonts = listOf("sans-serif", "sans-serif-thin", "sans-serif-light", "sans-serif-medium", "sans-serif-black", "sans-serif-condensed", "serif", "monospace", "casual", "cursive")
        binding.photoEditorTextFont.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, fonts))
        binding.photoEditorTextFont.setOnClickListener { binding.photoEditorTextFont.showDropDown() }
        binding.photoEditorTextSize.value = size
        binding.photoEditorTextSizeLabel.text = getString(R.string.photo_editor_text_size, size.toInt())
        binding.photoEditorTextSize.addOnChangeListener { _, value, _ -> size = value; updatePreview() }
        binding.photoEditorTextBold.setOnClickListener { bold = !bold; updatePreview() }
        binding.photoEditorTextItalic.setOnClickListener { italic = !italic; updatePreview() }
        binding.photoEditorTextUnderline.setOnClickListener { underline = !underline; updatePreview() }
        binding.photoEditorTextColor.setOnClickListener {
            color = PHOTO_EDITOR_COLORS[(PHOTO_EDITOR_COLORS.indexOf(color) + 1).mod(PHOTO_EDITOR_COLORS.size)]
            updatePreview()
        }
        updatePreview()
        binding.photoEditorTextCancel.setOnClickListener { dismiss() }
        binding.photoEditorTextDelete.visibility = if (arguments?.getBoolean(ARG_EDIT) == true) View.VISIBLE else View.GONE
        binding.photoEditorTextDelete.setOnClickListener {
            parentFragmentManager.setFragmentResult(PhotoEditorDialog.TEXT_RESULT_KEY, bundleOf(PhotoEditorDialog.TEXT_DELETE to true))
            dismiss()
        }
        binding.photoEditorTextAdd.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                PhotoEditorDialog.TEXT_RESULT_KEY,
                bundleOf(
                    PhotoEditorDialog.TEXT_RESULT to binding.photoEditorTextInput.text?.toString().orEmpty(),
                    PhotoEditorDialog.TEXT_COLOR to color,
                    PhotoEditorDialog.TEXT_SIZE to size,
                    PhotoEditorDialog.TEXT_FONT to binding.photoEditorTextFont.text.toString(),
                    PhotoEditorDialog.TEXT_STYLE to when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL },
                    PhotoEditorDialog.TEXT_UNDERLINE to underline,
                ),
            )
            dismiss()
        }
        binding.photoEditorTextInput.requestFocus()
    }

    private fun updatePreview() = with(binding) {
        photoEditorTextInput.setTextColor(color)
        photoEditorTextInput.textSize = size
        photoEditorTextInput.setTypeface(Typeface.create(photoEditorTextFont.text.toString(), Typeface.NORMAL), when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL })
        photoEditorTextInput.paintFlags = if (underline) photoEditorTextInput.paintFlags or Paint.UNDERLINE_TEXT_FLAG else photoEditorTextInput.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        photoEditorTextBold.isSelected = bold; photoEditorTextItalic.isSelected = italic; photoEditorTextUnderline.isSelected = underline
        photoEditorTextBold.setTextColor(if (bold) Color.WHITE else 0xff80848e.toInt())
        photoEditorTextItalic.setTextColor(if (italic) Color.WHITE else 0xff80848e.toInt())
        photoEditorTextUnderline.setTextColor(if (underline) Color.WHITE else 0xff80848e.toInt())
        photoEditorTextSizeLabel.text = getString(R.string.photo_editor_text_size, size.toInt())
        updateColorSwatch(photoEditorTextColor, color)
    }

    private fun updateColorSwatch(view: View, color: Int) {
        view.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(3), Color.WHITE) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.6f }
            attributes = attributes.apply { y = dp(72) }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }

    companion object {
        private const val ARG_TEXT = "text"
        private const val ARG_COLOR = "color"
        private const val ARG_EDIT = "edit"
        fun show(manager: androidx.fragment.app.FragmentManager, text: String = "", color: Int = Color.WHITE, editing: Boolean = false) {
            PhotoEditorTextDialog().apply { arguments = bundleOf(ARG_TEXT to text, ARG_COLOR to color, ARG_EDIT to editing) }
                .show(manager, PhotoEditorTextDialog::class.java.simpleName)
        }
    }
}
