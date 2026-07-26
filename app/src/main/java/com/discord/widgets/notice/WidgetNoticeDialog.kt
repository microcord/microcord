package com.discord.widgets.notice

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppActivity
import com.discord.app.AppDialog
import com.discord.app.AppLog
import com.discord.i18n.i18nRender
import com.discord.stores.StoreStream
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.utilities.view.text.LinkifiedTextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

/**
 * The app's generic modal: a header, a body, an optional confirm and cancel button, and optional
 * extra layouts inflated above or below the body.
 *
 * Everything is passed through the arguments bundle rather than constructor parameters so the
 * dialog survives process death, with two exceptions that cannot be parcelled: [listenerMap] and
 * [onDismiss]. Losing those on recreation is the reference's behaviour too — a restored dialog
 * still dismisses correctly, it just no longer runs the caller's action.
 *
 * ### Notice queue
 * A dialog built with a `noticeType` marks itself seen through
 * [com.discord.stores.StoreNotices.markDialogSeen] as soon as it binds, so the queue does not offer
 * it again. The type travels as a plain [String] rather than as `StoreNotices.Dialog.Type` because
 * it has to survive the arguments bundle.
 */
class WidgetNoticeDialog : AppDialog(R.layout.widget_notice_dialog) {

    /** Click handlers keyed by view id, plus [ON_SHOW] for a one-shot bind hook. */
    var listenerMap: Map<Int, (View) -> Unit>? = null

    var onDismiss: (() -> Unit)? = null

    /**
     * Delivers a link tap inside the body text to the caller.
     *
     * `LinkMovementMethod` consumes the touch before the dialog's own dismiss-on-outside-tap logic
     * runs, so a cancelable dialog whose body is a link would otherwise stay open behind the
     * browser. Dismissing on `ACTION_UP` restores that.
     */
    class ActionLinkMovementMethod(private val linkAction: () -> Unit) : LinkMovementMethod() {
        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            val handled = super.onTouchEvent(widget, buffer, event)
            if (event.action == MotionEvent.ACTION_UP) linkAction()
            return handled
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        arguments?.let { arguments ->
            val themeValue = TypedValue()
            context?.theme?.resolveAttribute(
                arguments.getInt(ARG_THEME_ID, R.attr.dialogTheme),
                themeValue,
                true,
            )
            setStyle(STYLE_NO_TITLE, themeValue.resourceId)
        }
        return super.onCreateDialog(savedInstanceState)
    }

    override fun onDismiss(dialog: DialogInterface) {
        onDismiss?.invoke()
        super.onDismiss(dialog)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val arguments = argumentsOrDefault
        isCancelable = arguments.getBoolean(ARG_CANCELABLE, true)
        arguments.getString(ARG_NOTICE_TYPE)?.let { type ->
            StoreStream.getNotices().markDialogSeen(type)
        }

        val header = view.findViewById<TextView>(R.id.notice_header)
        val headerText = arguments.getCharSequence(ARG_HEADER_TEXT)
        header.text = headerText
        view.findViewById<LinearLayout>(R.id.notice_header_container).visibility =
            if (headerText.isNullOrBlank()) View.GONE else View.VISIBLE

        val body = view.findViewById<LinkifiedTextView>(R.id.notice_body_text)
        body.text = arguments.getCharSequence(ARG_BODY_TEXT)
        if (isCancelable) body.movementMethod = ActionLinkMovementMethod(::dismiss)
        body.textAlignment = arguments.getInt(ARG_BODY_TEXT_ALIGNMENT, View.TEXT_ALIGNMENT_TEXT_START)

        val okButton = view.findViewById<MaterialButton>(R.id.notice_ok)
        okButton.setTextAndVisibilityBy(arguments.getCharSequence(ARG_OK_TEXT))
        val cancelButton = view.findViewById<MaterialButton>(R.id.CANCEL_BUTTON)
        cancelButton.setTextAndVisibilityBy(arguments.getCharSequence(ARG_CANCEL_TEXT))

        val bodyContainer = view.findViewById<LinearLayout>(R.id.notice_body_container)
        insertLayout(bodyContainer, arguments.getInt(ARG_ABOVE_LAYOUT_ID), index = 0)
        insertLayout(bodyContainer, arguments.getInt(ARG_BELOW_LAYOUT_ID), index = bodyContainer.childCount)

        okButton.setOnClickListener { dismiss() }
        cancelButton.setOnClickListener { dismiss() }

        // A caller listener runs *before* the dismiss, and replaces the plain dismiss listener
        // installed above when it targets one of the two buttons.
        listenerMap?.forEach { (viewId, listener) ->
            view.findViewById<View>(viewId)?.setOnClickListener {
                listener(view)
                dismiss()
            }
        }
        if (context != null) listenerMap?.get(ON_SHOW)?.invoke(view)
    }

    /** Inflates [layoutId] into [container] at [index]; a zero id means "nothing to insert". */
    private fun insertLayout(container: LinearLayout, @LayoutRes layoutId: Int, index: Int): View? {
        if (layoutId == 0) return null
        return layoutInflater.inflate(layoutId, container as ViewGroup, false)
            .also { container.addView(it, index) }
    }

    /** Reports a failure to show, tagged with the call site recorded when the dialog was built. */
    fun logOnStartError(exception: Exception) {
        AppLog.e(
            "failed to start WidgetNoticeDialog from ${arguments?.getString(ARG_STACK_TRACE_CALLER_FRAME)}",
            exception,
        )
    }

    /**
     * Fluent builder for the common "title, message, confirm, cancel" case.
     *
     * Every setter has a `@StringRes` overload because most call sites pass a resource; the
     * `CharSequence` overloads exist for already-formatted text.
     */
    class Builder(val context: Context) {
        private var title: CharSequence = ""
        private var message: CharSequence = ""
        private var confirmText: String = ""
        private var cancelText: String = ""
        private var onConfirm: (View) -> Unit = {}
        private var onCancel: (View) -> Unit = {}
        private var tag: String? = null
        @AttrRes private var themeId: Int? = null
        private var isCancelable: Boolean = false

        fun setTitle(text: CharSequence) = apply { title = text }

        fun setTitle(@StringRes stringResId: Int) = apply { title = context.getString(stringResId) }

        fun setMessage(text: CharSequence) = apply { message = text }

        fun setMessage(@StringRes stringResId: Int) = apply { message = context.i18nRender(stringResId) }

        fun setPositiveButton(text: String, onConfirm: (View) -> Unit = {}) = apply {
            confirmText = text
            this.onConfirm = onConfirm
        }

        fun setPositiveButton(@StringRes stringResId: Int, onConfirm: (View) -> Unit = {}) =
            setPositiveButton(context.getString(stringResId), onConfirm)

        fun setNegativeButton(text: String, onCancel: (View) -> Unit = {}) = apply {
            cancelText = text
            this.onCancel = onCancel
        }

        fun setNegativeButton(@StringRes stringResId: Int, onCancel: (View) -> Unit = {}) =
            setNegativeButton(context.getString(stringResId), onCancel)

        fun setDialogAttrTheme(@AttrRes theme: Int) = apply { themeId = theme }

        fun setTag(tag: String) = apply { this.tag = tag }

        fun setCancelable(cancelable: Boolean) = apply { isCancelable = cancelable }

        fun show(fragmentManager: FragmentManager) {
            show(
                fragmentManager = fragmentManager,
                headerText = title,
                bodyText = message,
                okText = confirmText,
                cancelText = cancelText,
                listenerMap = mapOf(R.id.notice_ok to onConfirm, R.id.CANCEL_BUTTON to onCancel),
                dialogTheme = themeId,
                cancelable = isCancelable,
                tag = tag,
            )
        }
    }

    companion object {
        private const val ARG_ABOVE_LAYOUT_ID = "above_layout_id"
        private const val ARG_BELOW_LAYOUT_ID = "below_layout_id"
        private const val ARG_BODY_TEXT = "body_text"
        private const val ARG_BODY_TEXT_ALIGNMENT = "body_text_alignment"
        private const val ARG_CANCELABLE = "cancelable"
        private const val ARG_CANCEL_TEXT = "cancel_text"
        private const val ARG_HEADER_TEXT = "header_text"
        private const val ARG_NOTICE_TYPE = "notice_type"
        private const val ARG_OK_TEXT = "ok_text"
        private const val ARG_STACK_TRACE_CALLER_FRAME = "stack_trace_caller_frame"
        private const val ARG_THEME_ID = "theme_id"

        /** Listener-map key for the "the dialog just bound its view" hook. */
        const val ON_SHOW = 0

        /**
         * @param listenerMap click handlers by view id. [ON_SHOW] runs once when the view binds,
         *   which is how the input-modal variant seeds its text field.
         * @param noticeType the `StoreNotices.Dialog.Type` name, or `null` for a dialog that is not
         *   part of the notice queue.
         * @param aboveLayoutId / [belowLayoutId] extra layouts inflated into the body container.
         */
        @JvmStatic
        fun show(
            fragmentManager: FragmentManager,
            headerText: CharSequence? = null,
            bodyText: CharSequence,
            okText: CharSequence?,
            cancelText: CharSequence? = null,
            listenerMap: Map<Int, (View) -> Unit>? = null,
            noticeType: String? = null,
            @LayoutRes aboveLayoutId: Int? = null,
            @LayoutRes belowLayoutId: Int? = null,
            @AttrRes dialogTheme: Int? = null,
            cancelable: Boolean? = null,
            tag: String? = null,
            bodyTextAlignment: Int = View.TEXT_ALIGNMENT_TEXT_START,
            onDismiss: (() -> Unit)? = null,
        ) {
            val dialog = WidgetNoticeDialog()
            dialog.listenerMap = listenerMap
            dialog.onDismiss = onDismiss
            dialog.arguments = Bundle().apply {
                putCharSequence(ARG_HEADER_TEXT, headerText)
                putCharSequence(ARG_BODY_TEXT, bodyText)
                putCharSequence(ARG_OK_TEXT, okText)
                putCharSequence(ARG_CANCEL_TEXT, cancelText)
                noticeType?.let { putString(ARG_NOTICE_TYPE, it) }
                aboveLayoutId?.let { putInt(ARG_ABOVE_LAYOUT_ID, it) }
                belowLayoutId?.let { putInt(ARG_BELOW_LAYOUT_ID, it) }
                dialogTheme?.let { putInt(ARG_THEME_ID, it) }
                cancelable?.let { putBoolean(ARG_CANCELABLE, it) }
                putInt(ARG_BODY_TEXT_ALIGNMENT, bodyTextAlignment)
                putString(ARG_STACK_TRACE_CALLER_FRAME, callerFrame())
            }
            dialog.show(fragmentManager, tag ?: WidgetNoticeDialog::class.java.simpleName)
        }

        /**
         * Shows the modal with a text or password field above the buttons.
         *
         * @param onOKClicked receives the field's contents.
         * @param shouldHideKeyboard closes the keyboard before running the caller's action, which
         *   matters when the action navigates away.
         */
        @JvmStatic
        fun showInputModal(
            activity: AppActivity,
            headerText: CharSequence,
            bodyText: CharSequence,
            hintText: CharSequence,
            onOKClicked: (Context, String) -> Unit,
            onCancelClicked: (() -> Unit)? = null,
            cancelable: Boolean? = null,
            isPassword: Boolean = false,
            okText: CharSequence? = null,
            shouldHideKeyboard: Boolean = false,
        ) {
            val inputLayout = if (isPassword) {
                R.layout.view_input_modal_password
            } else {
                R.layout.view_input_modal_text_no_suggestions
            }
            var input: TextInputLayout? = null
            val listeners = mutableMapOf<Int, (View) -> Unit>(
                ON_SHOW to { view ->
                    input = view.findViewById<TextInputLayout>(R.id.view_input)?.also { it.hint = hintText }
                },
                R.id.notice_ok to { view ->
                    if (shouldHideKeyboard) activity.hideKeyboard(view)
                    onOKClicked(view.context, input?.editText?.text?.toString().orEmpty())
                },
            )
            onCancelClicked?.let { cancel ->
                listeners[R.id.CANCEL_BUTTON] = { view ->
                    if (shouldHideKeyboard) activity.hideKeyboard(view)
                    cancel()
                }
            }
            show(
                fragmentManager = activity.supportFragmentManager,
                headerText = headerText,
                bodyText = bodyText,
                okText = okText ?: activity.getString(R.string.confirm),
                cancelText = activity.getString(R.string.cancel),
                listenerMap = listeners,
                belowLayoutId = inputLayout,
                cancelable = cancelable,
            )
        }

        /**
         * The frame that asked for this dialog, kept so a failure to show can name it.
         *
         * Index 4 skips `getStackTrace`, this method, `show` and the companion accessor, matching
         * the reference's depth.
         */
        private fun callerFrame(): String =
            Thread.currentThread().stackTrace.getOrNull(4)?.toString().orEmpty()
    }
}
