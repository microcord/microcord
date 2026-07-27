package com.discord.widgets.chat.input

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.util.Xml
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.discord.R
import com.lytefast.flexinput.fragment.FlexInputFragment
import org.xmlpull.v1.XmlPullParser

/** Restores the XML attributes that the public FlexInput build loses on fragment recreation. */
class AppFlexInputFragment : FlexInputFragment() {
    private var inflatedFromXml = false

    override fun onInflate(context: Context, attrs: AttributeSet, savedInstanceState: Bundle?) {
        inflatedFromXml = true
        super.onInflate(context, attrs, savedInstanceState)
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
    }
}
