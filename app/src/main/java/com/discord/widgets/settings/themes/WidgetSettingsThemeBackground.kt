package com.discord.widgets.settings.themes

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import com.discord.R
import com.discord.app.AppToast
import com.discord.databinding.WidgetSettingsThemeBackgroundBinding
import com.discord.utilities.rx.ui
import com.discord.utilities.theme.ThemeAssets
import com.discord.utilities.theme.ThemeDefinition
import com.discord.utilities.theme.ThemeDefinition.Transparency
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import rx.Observable
import rx.schedulers.Schedulers
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Aliucord's `background`: an image behind the app, darkened and blurred, and where it shows.
 *
 * The image is a file picked here (copied into the app) or a link, downloaded when the theme is
 * applied. The preview approximates the darkening and the blur; the real ones are baked in once.
 */
class WidgetSettingsThemeBackground : ThemeEditorPage(R.layout.widget_settings_theme_background, R.string.microcord_theme_background) {

    private var _binding: WidgetSettingsThemeBackgroundBinding? = null
    private val binding get() = requireNotNull(_binding)
    private lateinit var modes: Map<Transparency?, CheckedSetting>
    private lateinit var modeRadios: RadioManager
    private var shownImage: String? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val context = requireContext().applicationContext
        Observable.fromCallable { ThemeAssets.import(context, uri, IMAGE_EXTENSION) }
            .subscribeOn(Schedulers.io())
            .ui(this)
            .subscribe(
                { reference -> setImage(reference) },
                { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
            )
    }

    override fun onThemeLoaded(view: View) {
        _binding = WidgetSettingsThemeBackgroundBinding.bind(view)
        binding.themeBackgroundChoose.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        binding.themeBackgroundRemove.setOnClickListener { save(theme.copy(background = null)) }
        binding.themeBackgroundLink.setText(theme.background?.url?.takeIf { it.startsWith(HTTPS) })
        binding.themeBackgroundLink.setOnEditorActionListener { field, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            val link = field.text?.toString()?.trim().orEmpty()
            if (link.startsWith(HTTPS)) setImage(link) else binding.themeBackgroundLinkWrap.error = getString(R.string.microcord_theme_background_link)
            true
        }
        binding.themeBackgroundDim.setOnSeekBarChangeListener(seekListener(
            onChange = { binding.themeBackgroundDimLabel.text = getString(R.string.microcord_theme_background_dim, percent(it)); preview(dim = it) },
            onDone = { value -> theme.background?.let { save(theme.copy(background = it.copy(overlayAlpha = value))) } },
        ))
        binding.themeBackgroundBlur.setOnSeekBarChangeListener(seekListener(
            onChange = { binding.themeBackgroundBlurLabel.text = getString(R.string.microcord_theme_background_blur, it); preview(blur = it) },
            onDone = { value -> theme.background?.let { save(theme.copy(background = it.copy(blurRadius = value.toFloat()))) } },
        ))
        modes = mapOf(
            null to binding.themeBackgroundAuto,
            Transparency.NONE to binding.themeBackgroundNone,
            Transparency.CHAT to binding.themeBackgroundChat,
            Transparency.CHAT_SETTINGS to binding.themeBackgroundChatSettings,
            Transparency.FULL to binding.themeBackgroundFull,
        )
        modeRadios = RadioManager(modes.values.toList())
        modes.forEach { (mode, radio) -> radio.setContainerClickListener { save(theme.copy(transparency = mode)) } }
        binding.themeBackgroundSeeThrough.setContainerClickListener {
            val simple = theme.simpleColors.toMutableMap()
            if (isSeeThrough()) SEE_THROUGH.keys.forEach(simple::remove) else simple.putAll(SEE_THROUGH)
            save(theme.copy(simpleColors = simple))
        }
    }

    private fun isSeeThrough() = SEE_THROUGH.all { (key, color) -> theme.simpleColors[key] == color }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun render() {
        val binding = _binding ?: return
        val background = theme.background
        val enabled = background != null
        listOf(binding.themeBackgroundDim, binding.themeBackgroundBlur, binding.themeBackgroundRemove).forEach { it.isEnabled = enabled }
        modes.values.forEach { it.isEnabled = enabled }
        val dim = background?.overlayAlpha ?: ThemeDefinition.DEFAULT_OVERLAY_ALPHA
        val blur = background?.blurRadius?.roundToInt() ?: 0
        binding.themeBackgroundDim.progress = dim
        binding.themeBackgroundBlur.progress = blur
        binding.themeBackgroundDimLabel.text = getString(R.string.microcord_theme_background_dim, percent(dim))
        binding.themeBackgroundBlurLabel.text = getString(R.string.microcord_theme_background_blur, blur)
        modeRadios.setChecked(modes.getValue(theme.transparency))
        binding.themeBackgroundSeeThrough.isChecked = isSeeThrough()
        loadImage(background?.url)
        preview(dim, blur)
    }

    private fun setImage(reference: String) {
        val current = theme.background
        save(theme.copy(background = current?.copy(url = reference) ?: ThemeDefinition.Background(reference)))
    }

    /** Decodes a small copy for the preview, downloading a link first. */
    private fun loadImage(reference: String?) {
        if (reference == shownImage) return
        val binding = _binding ?: return
        binding.themeBackgroundImage.setImageDrawable(null)
        binding.themeBackgroundEmpty.isVisible = reference == null
        if (reference == null) {
            shownImage = null
            return
        }
        val context = requireContext().applicationContext
        val width = max(binding.themeBackgroundImage.width, PREVIEW_WIDTH)
        Observable.fromCallable {
            val file = ThemeAssets.local(context, reference)
                ?: ThemeDefinition(ThemeDefinition.Manifest(""), background = ThemeDefinition.Background(reference))
                    .let { ThemeAssets.fetchMissing(context, listOf(it)) }
                    .let { ThemeAssets.local(context, reference) }
                ?: error("Not available")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        }
            .subscribeOn(Schedulers.io())
            .ui(this)
            .subscribe(
                { bitmap ->
                    if (theme.background?.url == reference) {
                        shownImage = reference
                        _binding?.themeBackgroundImage?.setImageBitmap(bitmap)
                    }
                },
                { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
            )
    }

    private fun preview(dim: Int = binding.themeBackgroundDim.progress, blur: Int = binding.themeBackgroundBlur.progress) {
        val image = _binding?.themeBackgroundImage ?: return
        image.setColorFilter(Color.argb(dim, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            image.setRenderEffect(if (blur > 0) RenderEffect.createBlurEffect(blur.toFloat(), blur.toFloat(), Shader.TileMode.CLAMP) else null)
        }
    }

    private fun seekListener(onChange: (Int) -> Unit, onDone: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = onDone(seekBar.progress)
    }

    private companion object {
        const val HTTPS = "https://"
        const val IMAGE_EXTENSION = ".img"
        const val PREVIEW_WIDTH = 720

        /** Aliucord's full-transparency template: a clear main background, the rest lightly tinted. */
        val SEE_THROUGH = mapOf("background" to 0, "background_secondary" to 0x25000000)

        fun percent(alpha: Int) = (alpha * 100f / 255f).roundToInt()
    }
}
