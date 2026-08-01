package com.discord.widgets.settings

import android.text.SpannableStringBuilder
import android.view.View
import android.widget.TextView
import androidx.fragment.app.setFragmentResultListener
import com.discord.app.AppFragment
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.text.addBindedTextWatcher
import com.discord.views.CheckedSetting
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.atomic.AtomicBoolean
import rx.functions.Action1

/**
 * Tracks the edited-vs-committed value of a form's fields so a settings screen can show its save
 * action only while something has actually changed, and warn before discarding.
 *
 * Reference: `com.discord.utilities.stateful.StatefulViews`, used by every "edit and press save"
 * server-settings screen (overview, vanity URL, edit role, edit member, edit integration, emoji
 * edit). It holds two maps keyed by view id — the value the screen last *rendered* and the value the
 * user has since *typed* — and derives everything from the difference between them:
 *
 * - [get] records the rendered value and returns the edited one if there is one, so re-rendering
 *   from a store emission never clobbers in-progress typing;
 * - [getIfChanged] returns only genuinely changed fields, which is what makes the PATCH bodies
 *   sparse instead of rewriting every property;
 * - [configureSaveActionView] shows the save FAB only when something changed *and* no required
 *   field is blank;
 * - [setupUnsavedChangesConfirmation] intercepts Back with a discard confirmation.
 *
 * **Location:** this belongs in `com.discord.utilities.stateful`, which does not exist in this
 * project yet and is outside this slice's ownership. It is kept here, next to the screens that need
 * it, and recorded in `docs/parity-audit/followups.md` so the utilities owner can move it. The
 * behaviour is the reference's, including the quirks noted below.
 */
class StatefulViews(private val ids: List<Int>) {

    constructor(vararg ids: Int) : this(ids.toList())

    /** The value each field was last rendered with. */
    private val viewValues = HashMap<Int, Any?>()

    /** The value the user has typed/toggled since, for the fields they touched. */
    private val viewValuesEdited = HashMap<Int, Any?>()

    /** Field id → whether it must be non-blank for the save action to appear. */
    private val requiredFieldIds = HashMap<Int, Boolean>()

    /**
     * Records [defaultValue] as the committed value of [id] and returns what should be displayed:
     * the user's edit if they have one, the default otherwise.
     *
     * Faithful quirk: a *required* field whose edited value is `null` falls back to the default,
     * while an explicitly optional field keeps the `null`. That is how the reference lets an
     * optional field be cleared but never lets a required one become null.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(id: Int, defaultValue: T): T {
        viewValues[id] = defaultValue
        val current = currentValue(id)
        return if (requiredFieldIds[id] == false || current != null) current as T else defaultValue
    }

    /** The edited value of [id], or `null` when the user has not changed it. */
    @Suppress("UNCHECKED_CAST")
    fun <T> getIfChanged(id: Int): T? =
        if (hasChanged(id)) viewValuesEdited[id] as T? else null

    fun hasChanged(viewId: Int): Boolean =
        viewValuesEdited.containsKey(viewId) && viewValuesEdited[viewId] != viewValues[viewId]

    fun put(id: Int, value: Any?) {
        viewValuesEdited[id] = value
    }

    /** Marks [views] as allowed to be blank; by default a watched field is required. */
    fun addOptionalFields(vararg views: View) {
        views.forEach { requiredFieldIds[it.id] = false }
    }

    /**
     * Drops the pending edits. [clearDefaults] also drops the rendered values, which a screen does
     * after a successful save so the newly saved values become the new baseline.
     */
    @JvmOverloads
    fun clear(clearDefaults: Boolean = false) {
        viewValuesEdited.clear()
        if (clearDefaults) viewValues.clear()
    }

    /** Shows [saveActionView] only while there is something worth saving. */
    fun configureSaveActionView(saveActionView: View?) {
        if (saveActionView == null) return
        val show = hasAnythingChanged() && !isAnyRequiredFieldBlank()
        if (saveActionView is FloatingActionButton) {
            if (show) saveActionView.show() else saveActionView.hide()
        } else {
            saveActionView.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    /**
     * Watches [views] for edits and re-evaluates [saveAction] after each one.
     *
     * A [TextView], a [TextInputLayout] and a [CheckedSetting] are the three kinds of field the
     * reference supports; anything else is a programming error and throws, rather than silently
     * never enabling the save button.
     */
    fun setupTextWatcherWithSaveAction(
        fragment: AppFragment,
        saveAction: View?,
        vararg views: View,
    ) {
        views.forEach { view ->
            // A field not explicitly registered as optional is required.
            requiredFieldIds[view.id] = requiredFieldIds[view.id] ?: true
            val onEdited: (Any?) -> Unit = { value ->
                put(view.id, value)
                configureSaveActionView(saveAction)
            }
            when (view) {
                is TextInputLayout ->
                    view.addBindedTextWatcher(fragment) { onEdited(it.toString()) }

                is CheckedSetting ->
                    view.setOnCheckedListener(Action1 { onEdited(it) })

                is TextView ->
                    view.addBindedTextWatcher(fragment) { onEdited(it.toString()) }

                else -> throw UnsupportedOperationException(
                    "$view must support using `TextWatcher`.",
                )
            }
        }
    }

    /** Asks for confirmation before Back discards pending edits. */
    fun setupUnsavedChangesConfirmation(fragment: AppFragment) {
        val handler = DiscardChangesBackHandler(fragment) { hasAnythingChanged() }
        fragment.setOnBackPressed(handler::onBackPressed)
    }

    private fun currentValue(id: Int): Any? =
        if (viewValuesEdited.containsKey(id)) viewValuesEdited[id] else viewValues[id]

    /**
     * Whether any watched field differs from what was rendered.
     *
     * A `SpannableStringBuilder` is compared by its text: an `EditText` hands back a spanned value
     * whose spans differ from the plain `String` the screen rendered, so comparing the objects
     * directly would report a change the user never made.
     */
    private fun hasAnythingChanged(): Boolean = ids.any { id ->
        if (!viewValuesEdited.containsKey(id)) return@any false
        flatten(viewValuesEdited[id]) != flatten(viewValues[id])
    }

    private fun flatten(value: Any?): Any? =
        if (value is SpannableStringBuilder) value.toString() else value

    private fun isAnyRequiredFieldBlank(): Boolean = requiredFieldIds.any { (id, required) ->
        required && (currentValue(id) as? String)?.isBlank() == true
    }

    /**
     * Back handler that confirms once and then gets out of the way.
     *
     * The confirmed flag is one-shot on purpose: after the user chooses to discard, the handler
     * re-issues Back and must not intercept it a second time.
     */
    private class DiscardChangesBackHandler(
        private val fragment: AppFragment,
        private val hasAnythingChanged: () -> Boolean,
    ) {
        private val discardConfirmed = AtomicBoolean(false)
        private val requestKey = "${DiscardChangesDialog::class.java.name}:${fragment.id}"

        init {
            fragment.setFragmentResultListener(requestKey) { _, _ ->
                discardConfirmed.set(true)
                fragment.activity?.onBackPressed()
            }
        }

        fun onBackPressed(): Boolean {
            if (!hasAnythingChanged() || discardConfirmed.get()) return false
            DiscardChangesDialog.show(fragment.parentFragmentManager, requestKey)
            return true
        }
    }
}
