package com.discord.widgets.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.text.method.LinkMovementMethod
import android.view.View
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import com.discord.BuildConfig
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsUpdatesBinding
import com.discord.utilities.rx.ui
import com.discord.utilities.updates.AppUpdater
import com.discord.utilities.updates.AppUpdater.Phase
import com.discord.utilities.updates.AppUpdate
import com.discord.utilities.updates.AppUpdateChanges
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.changelog.ChangeLogParser
import rx.Subscription

class WidgetSettingsUpdates : AppFragment(R.layout.widget_settings_updates) {
    private var binding: WidgetSettingsUpdatesBinding? = null
    private var preparingInstall = false
    private var cachedChanges: AppUpdateChanges? = null
    private var changesKey: String? = null
    private var changesJob: Subscription? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val bound = WidgetSettingsUpdatesBinding.bind(view).also { binding = it }
        changesKey = null
        setActionBarTitle(R.string.updater_title)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        bound.updaterCurrent.text = getString(R.string.updater_current_build,
            BuildConfig.UPDATE_BUILD_ID, BuildConfig.UPDATE_COMMIT_SHA.take(8))
        bound.updaterAutomatic.bind(AppUpdater.automaticChecksEnabled()) { enabled ->
            AppUpdater.setAutomaticChecks(enabled)
            if (enabled) AppUpdater.onForeground(view.context)
        }
        bound.updaterCheck.setOnClickListener {
            resetChanges()
            cachedChanges = null
            AppUpdater.check(view.context)
        }
        bound.updaterChangesRetry.setOnClickListener {
            resetChanges()
            render(AppUpdater.state)
        }
        bound.updaterNotes.movementMethod = LinkMovementMethod.getInstance()
        ViewCompat.setAccessibilityHeading(bound.updaterNotesTitle, true)
        ViewCompat.setAccessibilityHeading(bound.updaterCommitsTitle, true)
        bound.updaterCancel.setOnClickListener { AppUpdater.cancelDownload(view.context) }
        bound.updaterAction.setOnClickListener {
            if (AppUpdater.state.phase == Phase.READY) installUpdate() else AppUpdater.startDownload(view.context)
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        preparingInstall = false
        AppUpdater.watchDownload(requireContext())
        AppUpdater.observe().ui(this).subscribe(::render)
        if (AppUpdater.state.phase == Phase.IDLE) AppUpdater.check(requireContext())
    }

    override fun onPause() {
        resetChanges()
        AppUpdater.stopWatchingDownload()
        super.onPause()
    }

    override fun onDestroyView() {
        resetChanges()
        binding = null
        super.onDestroyView()
    }

    private fun render(state: AppUpdater.State) {
        val bound = binding ?: return
        val context = bound.root.context
        val update = state.update
        bound.updaterRelease.isVisible = update != null
        bound.updaterRelease.text = update?.let {
            getString(R.string.updater_release_details, it.release,
                it.commitSha?.take(8) ?: getString(R.string.updater_unknown_commit),
                Formatter.formatShortFileSize(context, it.size ?: 0))
        }
        bound.updaterStatus.setText(state.error ?: when (state.phase) {
            Phase.IDLE -> R.string.updater_idle
            Phase.CHECKING -> R.string.updater_checking
            Phase.AVAILABLE -> R.string.updater_available
            Phase.CURRENT -> R.string.updater_current
            Phase.LEGACY -> R.string.updater_legacy
            Phase.DOWNLOADING -> if (state.paused) R.string.updater_waiting else R.string.updater_downloading
            Phase.VERIFYING -> R.string.updater_verifying
            Phase.READY -> R.string.updater_ready
            Phase.ERROR -> R.string.updater_check_failed
        })
        val busy = state.phase in setOf(Phase.CHECKING, Phase.DOWNLOADING, Phase.VERIFYING) || preparingInstall
        val indeterminate = state.phase != Phase.DOWNLOADING
        if (bound.updaterProgress.isIndeterminate != indeterminate) {
            // Material requires the indicator to be hidden before changing its animation mode.
            bound.updaterProgress.isVisible = false
            bound.updaterProgress.isIndeterminate = indeterminate
        }
        bound.updaterProgress.isVisible = busy
        bound.updaterProgress.progress = ((state.downloaded * 100 / (update?.size ?: 1)).coerceIn(0, 100)).toInt()
        bound.updaterBytes.isVisible = state.phase == Phase.DOWNLOADING
        bound.updaterBytes.text = getString(R.string.updater_download_progress,
            Formatter.formatShortFileSize(context, state.downloaded),
            Formatter.formatShortFileSize(context, update?.size ?: 0))
        bound.updaterCheck.isEnabled = !busy && state.phase != Phase.READY
        bound.updaterAction.isVisible = state.phase == Phase.READY ||
            (state.phase in setOf(Phase.AVAILABLE, Phase.ERROR) && update?.hasBuildMetadata == true &&
                update.isNewerThan(BuildConfig.UPDATE_COMMIT_SHA, BuildConfig.UPDATE_COMMIT_TIMESTAMP, BuildConfig.VERSION_CODE.toLong()))
        bound.updaterAction.isEnabled = !busy
        bound.updaterAction.setText(if (state.phase == Phase.READY) {
            if (canInstall(context)) R.string.updater_install else R.string.updater_allow_install
        } else R.string.updater_download)
        bound.updaterCancel.isVisible = state.phase == Phase.DOWNLOADING
        bound.updaterChanges.isVisible = update != null
        if (update != null && state.phase != Phase.CHECKING) showChanges(update)
    }

    private fun resetChanges() {
        changesJob?.unsubscribe()
        changesJob = null
        changesKey = null
    }

    private fun showChanges(update: AppUpdate) {
        val key = "${update.release}:${update.commitSha}"
        if (changesKey == key) return
        resetChanges()
        changesKey = key
        val cached = cachedChanges?.takeIf {
            it.release == update.release && it.toSha == update.commitSha && it.fromSha == BuildConfig.UPDATE_COMMIT_SHA
        }
        if (cached != null) {
            renderChanges(cached)
            return
        }
        val bound = binding ?: return
        bound.updaterChangesStatus.setText(R.string.updater_changes_loading)
        bound.updaterChangesStatus.isVisible = true
        bound.updaterChangesRetry.isVisible = false
        bound.updaterNotes.text = ""
        bound.updaterNotesTruncated.isVisible = false
        bound.updaterCommits.text = ""
        bound.updaterCommitSummary.text = ""
        changesJob = AppUpdater.loadChanges(update).ui(this).subscribe({ changes ->
            if (changesKey == key) {
                changesJob = null
                cachedChanges = changes
                renderChanges(changes)
            }
        }, {
            if (changesKey == key) {
                changesJob = null
                binding?.updaterChangesStatus?.setText(R.string.updater_changes_failed)
                binding?.updaterChangesRetry?.isVisible = true
            }
        })
    }

    private fun renderChanges(changes: AppUpdateChanges) {
        val bound = binding ?: return
        val context = bound.root.context
        bound.updaterChangesStatus.isVisible = false
        bound.updaterChangesRetry.isVisible = false
        val notes = changes.notes?.takeIf(String::isNotBlank) ?: context.getString(R.string.updater_notes_empty)
        runCatching {
            ChangeLogParser.parse(context, notes) { ctx, url, mask -> UriHandler.handleOrUntrusted(ctx, url, mask) }
        }.fold(
            onSuccess = { bound.updaterNotes.setDraweeSpanStringBuilder(it) },
            onFailure = { bound.updaterNotes.text = notes },
        )
        bound.updaterNotesTruncated.isVisible = changes.notesTruncated == true
        bound.updaterCommitSummary.text = when (changes.comparison) {
            AppUpdateChanges.Comparison.COMPARED -> buildString {
                append(context.getString(R.string.updater_commits_range, changes.fromSha?.take(8), changes.toSha?.take(8)))
                if (changes.commitsTruncated == true) append("\n").append(context.getString(
                    R.string.updater_commits_truncated, changes.commits.orEmpty().size, changes.totalCommits))
            }
            AppUpdateChanges.Comparison.IDENTICAL -> context.getString(R.string.updater_commits_identical)
            else -> context.getString(R.string.updater_commits_unavailable)
        }
        bound.updaterCommits.text = changes.commits.orEmpty().joinToString("\n\n") { "${it.sha?.take(8)}  ${it.title.orEmpty()}" }
    }

    private fun installUpdate() {
        val context = context ?: return
        try {
            if (!canInstall(context)) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return
            }
            preparingInstall = true
            render(AppUpdater.state)
            AppUpdater.prepareInstall(context).ui(this).subscribe({ file ->
                preparingInstall = false
                render(AppUpdater.state)
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) {
                    binding?.updaterStatus?.setText(R.string.updater_install_failed)
                }
            }, {
                preparingInstall = false
                AppUpdater.invalidateDownload(context)
            })
        } catch (_: Exception) {
            preparingInstall = false
            render(AppUpdater.state)
            binding?.updaterStatus?.setText(R.string.updater_install_failed)
        }
    }

    private fun canInstall(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    companion object {
        fun launch(context: Context) = SettingsNavigation.launch(context, WidgetSettingsUpdates::class.java)
    }
}
