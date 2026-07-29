package com.discord.widgets.chat.input

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.util.Xml
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.discord.R
import com.lytefast.flexinput.fragment.FlexInputFragment
import com.lytefast.flexinput.managers.KeyboardManager
import org.xmlpull.v1.XmlPullParser

/** Restores the XML attributes that the public FlexInput build loses on fragment recreation. */
class AppFlexInputFragment : FlexInputFragment() {
    private var inflatedFromXml = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        setKeyboardManager(object : KeyboardManager {
            override fun requestDisplay(editText: EditText) {
                editText.requestFocus()
                inputMethodManager(context).showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun requestHide() {
                val token = activity?.currentFocus?.windowToken ?: view?.windowToken ?: return
                inputMethodManager(context).hideSoftInputFromWindow(token, 0)
            }
        })
    }

    override fun onInflate(context: Context, attrs: AttributeSet, savedInstanceState: Bundle?) {
        inflatedFromXml = true
        super.onInflate(context, attrs, savedInstanceState)
    }

    override fun onDestroyView() {
        inflatedFromXml = false
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        if (!inflatedFromXml) {
            val parser = resources.getLayout(R.layout.widget_chat_input)
            try {
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG &&
                        parser.getAttributeResourceValue(ANDROID_NAMESPACE, "id", 0) == R.id.chat_input_widget
                    ) {
                        onInflate(requireContext(), Xml.asAttributeSet(parser), savedInstanceState)
                        break
                    }
                    parser.next()
                }
            } finally {
                parser.close()
            }
        }
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    private companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        fun inputMethodManager(context: Context) =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
}
