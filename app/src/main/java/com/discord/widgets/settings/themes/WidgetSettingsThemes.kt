package com.discord.widgets.settings.themes

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.databinding.WidgetSettingsThemesBinding
import com.discord.databinding.WidgetSettingsThemesItemBinding
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.utilities.theme.ThemeAssets
import com.discord.utilities.theme.ThemeDefinition
import com.discord.utilities.theme.ThemeJson
import com.discord.utilities.theme.ThemeRuntime
import com.discord.utilities.theme.ThemeStore
import com.discord.utilities.theme.ThemeUpdater
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.SettingsNavigation
import java.io.File
import rx.Observable
import rx.schedulers.Schedulers

/**
 * The custom themes on this device: pick one, import Aliucord or Microcord theme files, and share,
 * save, duplicate or delete them.
 *
 * Picking a theme applies it through [ThemeRuntime] and recreates the screen, so the whole app
 * shows it straight away. Custom themes need Android 11. Below that the screen only explains why.
 */
class WidgetSettingsThemes : AppFragment(R.layout.widget_settings_themes) {

    private var _binding: WidgetSettingsThemesBinding? = null
    private val binding get() = requireNotNull(_binding)

    /** The theme waiting for the save-to-file picker; kept across the picker's round trip. */
    private var savingId: String? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME)) { uri ->
        val id = savingId
        savingId = null
        if (uri != null && id != null) saveToFile(id, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savingId = savedInstanceState?.getString(STATE_SAVING_ID)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SAVING_ID, savingId)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsThemesBinding.bind(view)
        setActionBarTitle(R.string.microcord_custom_themes)
        setActionBarSubtitle(R.string.appearance)
        setActionBarDisplayHomeAsUpEnabled()
        // Anything a JSON theme is shared as: files are rarely labelled application/json.
        binding.settingsThemesImportFile.setOnClickListener { openDocument.launch(arrayOf("*/*")) }
        binding.settingsThemesImportClipboard.setOnClickListener { importClipboard() }
        binding.settingsThemesNew.setOnClickListener { createTheme() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        render()
        if (!checkedForUpdates) {
            checkedForUpdates = true
            checkForUpdates(ThemeRuntime.store(requireContext()).list().filter { it.theme.manifest.updater != null }, quiet = true)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val binding = _binding ?: return
        val supported = ThemeRuntime.isSupported
        val store = ThemeRuntime.store(requireContext())
        val notice = when {
            !supported -> getString(R.string.microcord_themes_unsupported)
            store.consumeDisabledByGuard() -> getString(R.string.microcord_themes_guard_disabled)
            else -> null
        }
        binding.settingsThemesNotice.text = notice
        binding.settingsThemesNotice.isVisible = notice != null
        val activeIds = store.activeIds
        val themes = store.list()
        val builtIn = StoreStream.getUserSettingsSystem().getThemeSetting()
        binding.settingsThemesList.removeAllViews()
        themes.forEach { stored -> addRow(stored, builtIn, stored.id in activeIds, supported) }
        binding.settingsThemesEmpty.isVisible = themes.isEmpty()
        binding.settingsThemesNoneEnabled.isVisible = themes.isNotEmpty() && activeIds.isEmpty()
        binding.settingsThemesNew.isEnabled = supported
        binding.settingsThemesImportFile.isEnabled = supported
        binding.settingsThemesImportClipboard.isEnabled = supported
    }

    /** One theme row: preview, name and author, its switch and the options menu. */
    private fun addRow(stored: ThemeStore.Stored, builtIn: String, active: Boolean, enabled: Boolean) {
        val row = WidgetSettingsThemesItemBinding.inflate(layoutInflater, binding.settingsThemesList, true)
        row.settingsThemesItemPreview.setImageDrawable(
            ThemePreviewDrawable().apply { colors = ThemePreviewDrawable.colorsOf(requireContext(), stored.theme, builtIn) },
        )
        row.settingsThemesItemSwitch.setText(stored.theme.manifest.name)
        row.settingsThemesItemSwitch.setSubtext(getString(R.string.microcord_themes_author, stored.theme.manifest.author))
        row.settingsThemesItemSwitch.isChecked = active
        row.settingsThemesItemSwitch.isEnabled = enabled
        row.settingsThemesItemSwitch.setContainerClickListener { toggle(stored.id) }
        row.settingsThemesItemOptions.setOnClickListener { showOptions(it, stored) }
    }

    /** Several themes can be on; a newly enabled one layers on top of the others. */
    private fun toggle(id: String) {
        if (!ThemeRuntime.isSupported) return
        val enabled = ThemeRuntime.store(requireContext()).activeIds
        ThemeActions.enable(this, if (id in enabled) enabled - id else enabled + id)
    }

    /** Fetches newer copies of [themes]; an enabled one that changed is applied again. */
    private fun checkForUpdates(themes: List<ThemeStore.Stored>, quiet: Boolean) {
        if (themes.isEmpty()) return
        val context = requireContext().applicationContext
        val store = ThemeRuntime.store(context)
        Observable.fromCallable {
            themes.mapNotNull { stored -> runCatching { ThemeUpdater.update(store, stored.id) }.getOrNull()?.let { stored.id to it } }
        }
            .subscribeOn(Schedulers.io())
            .ui(this)
            .subscribe({ updated ->
                updated.forEach { (_, theme) ->
                    AppToast.show(context, getString(R.string.microcord_themes_updated, theme.manifest.name, theme.manifest.version))
                }
                if (updated.isEmpty() && !quiet) {
                    themes.forEach { AppToast.show(context, getString(R.string.microcord_themes_up_to_date, it.theme.manifest.name)) }
                }
                render()
                val active = store.activeIds
                if (updated.any { it.first in active }) ThemeActions.enable(this, active)
            }, { if (!quiet) AppToast.show(context, R.string.default_failure_to_perform_action_message) })
    }

    private fun showOptions(anchor: View, stored: ThemeStore.Stored) {
        val popup = PopupMenu(ContextThemeWrapper(anchor.context, R.style.AppTheme_PopupMenu), anchor)
        popup.menu.add(0, MENU_EDIT, MENU_EDIT, R.string.edit)
        popup.menu.add(0, MENU_DUPLICATE, MENU_DUPLICATE, R.string.microcord_themes_duplicate)
        if (stored.theme.manifest.updater != null) popup.menu.add(0, MENU_UPDATE, MENU_UPDATE, R.string.microcord_themes_check_update)
        popup.menu.add(0, MENU_SHARE, MENU_SHARE, R.string.share)
        popup.menu.add(0, MENU_SAVE, MENU_SAVE, R.string.manticord_save_to_file)
        popup.menu.add(0, MENU_DELETE, MENU_DELETE, R.string.delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> WidgetSettingsThemeEditor.launch(requireContext(), stored.id)
                MENU_DUPLICATE -> duplicate(stored)
                MENU_UPDATE -> checkForUpdates(listOf(stored), quiet = false)
                MENU_SHARE -> share(stored)
                MENU_SAVE -> {
                    savingId = stored.id
                    createDocument.launch(fileName(stored))
                }
                MENU_DELETE -> confirmDelete(stored)
            }
            true
        }
        popup.show()
    }

    /** Starts from the built-in theme in use, so the editor opens on colors the user already sees. */
    private fun createTheme() {
        val current = StoreStream.getUserSettingsSystem().getTheme()
        val theme = ThemeDefinition(
            manifest = ThemeDefinition.Manifest(getString(R.string.microcord_theme_new_name)),
            base = current.takeIf { it in ThemeDefinition.BASES } ?: ThemeDefinition.BASE_DEFAULT,
        )
        val id = ThemeRuntime.store(requireContext()).save(theme)
        WidgetSettingsThemeEditor.launch(requireContext(), id)
    }

    private fun duplicate(stored: ThemeStore.Stored) {
        val manifest = stored.theme.manifest
        val copy = stored.theme.copy(manifest = manifest.copy(name = getString(R.string.microcord_themes_copy_name, manifest.name)))
        ThemeRuntime.store(requireContext()).save(copy)
        render()
    }

    private fun confirmDelete(stored: ThemeStore.Stored) {
        WidgetNoticeDialog.show(
            parentFragmentManager,
            headerText = getString(R.string.delete),
            bodyText = getString(R.string.microcord_themes_delete_confirm, stored.theme.manifest.name),
            okText = getString(R.string.delete),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf(R.id.notice_ok to { _: View -> delete(stored) }),
        )
    }

    private fun delete(stored: ThemeStore.Stored) {
        val context = context ?: return
        val store = ThemeRuntime.store(context)
        val wasActive = stored.id in store.activeIds
        store.delete(stored.id)
        if (wasActive) ThemeActions.enable(this, store.activeIds) else render()
    }

    private fun share(stored: ThemeStore.Stored) {
        val context = requireContext().applicationContext
        Observable.fromCallable {
            val directory = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            // Only the latest export is kept; older ones are already shared or abandoned.
            directory.listFiles()?.forEach { it.delete() }
            File(directory, fileName(stored)).apply { writeText(ThemeJson.toJson(stored.theme)) }
        }
            .subscribeOn(Schedulers.io())
            .ui(this)
            .subscribe(
                { file ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(JSON_MIME)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, getString(R.string.share)))
                },
                { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
            )
    }

    private fun saveToFile(id: String, uri: Uri) {
        val context = requireContext().applicationContext
        Observable.fromCallable {
            val theme = requireNotNull(ThemeRuntime.store(context).read(id)) { "Theme is gone" }
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(ThemeJson.toJson(theme)) }
                ?: error("Unable to open the destination")
        }
            .subscribeOn(Schedulers.io())
            .ui(this)
            .subscribe(
                { AppToast.show(this, R.string.microcord_themes_saved) },
                { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
            )
    }

    private fun importFile(uri: Uri) {
        val context = requireContext().applicationContext
        Observable.fromCallable {
            val name = displayName(context, uri)?.removeSuffix(".json") ?: ThemeJson.DEFAULT_NAME
            val text = context.contentResolver.openInputStream(uri)?.use { ThemeAssets.readBounded(it, MAX_FILE_BYTES).toString(Charsets.UTF_8) }
                ?: throw IllegalArgumentException("The file could not be opened")
            save(context, text, name)
        }
            .subscribeOn(Schedulers.io())
            .ui(this)
            .subscribe(::onImported, ::onImportFailed)
    }

    private fun importClipboard() {
        val context = requireContext()
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            AppToast.show(this, R.string.microcord_themes_clipboard_empty)
            return
        }
        try {
            onImported(save(context, text, ThemeJson.DEFAULT_NAME))
        } catch (e: IllegalArgumentException) {
            onImportFailed(e)
        }
    }

    private fun save(context: Context, text: String, fallbackName: String): String {
        val theme = ThemeJson.parse(text, fallbackName).theme
        ThemeRuntime.store(context).save(theme)
        return theme.manifest.name
    }

    private fun onImported(name: String) {
        AppToast.show(this, getString(R.string.microcord_themes_imported, name))
        render()
    }

    private fun onImportFailed(error: Throwable) {
        AppToast.show(this, getString(R.string.microcord_themes_import_failed, error.message ?: error.javaClass.simpleName))
    }

    companion object {
        private const val JSON_MIME = "application/json"
        private const val EXPORT_DIR = "theme_exports"
        private const val STATE_SAVING_ID = "saving_id"
        private const val MAX_FILE_BYTES = 4 * ThemeJson.MAX_CHARS
        private const val MENU_EDIT = 1
        private const val MENU_DUPLICATE = 2
        private const val MENU_SHARE = 3
        private const val MENU_SAVE = 4
        private const val MENU_DELETE = 5
        private const val MENU_UPDATE = 6
        private var checkedForUpdates = false

        fun launch(context: Context) = SettingsNavigation.launch(context, WidgetSettingsThemes::class.java)

        /** A file name from the theme name; never a path, whatever the name contains. */
        private fun fileName(stored: ThemeStore.Stored): String {
            val slug = stored.theme.manifest.name
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
                .joinToString("").trim('-').take(40)
            return (slug.ifEmpty { "theme" }) + ".json"
        }

        private fun displayName(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
