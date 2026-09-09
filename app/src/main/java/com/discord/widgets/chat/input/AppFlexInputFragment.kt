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
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager.widget.ViewPager
import com.discord.R
import com.discord.app.appActivityViewModels
import com.discord.widgets.chat.input.applauncher.AppLauncherEntrypoint
import com.discord.widgets.chat.input.applauncher.WidgetAppLauncher
import com.google.android.material.tabs.TabLayout
import com.lytefast.flexinput.fragment.AddContentDialogFragment
import com.lytefast.flexinput.fragment.FlexInputFragmentCompat
import com.lytefast.flexinput.managers.KeyboardManager
import org.xmlpull.v1.XmlPullParser

/** Restores the XML attributes that the public FlexInput build loses on fragment recreation. */
class AppFlexInputFragment : FlexInputFragmentCompat() {
    private var inflatedFromXml = false
    var onAttachmentsCleared: () -> Unit = {}

    private val flexInputViewModel: AppFlexInputViewModel by appActivityViewModels {
        AppFlexInputViewModel()
    }

    private val addContentTabCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fragmentManager: FragmentManager,
            fragment: Fragment,
            view: View,
            savedInstanceState: Bundle?,
        ) {
            if (fragment !is AddContentDialogFragment) return
            // The attachment dialog hides the emoji tray through the library's own state, which
            // this app doesn't otherwise observe — routing it through the shared view model too
            // keeps the composer's own tray height in sync instead of leaving it stuck open.
            flexInputViewModel.hideExpressionTray()
            view.post {
                val tabs = view.findViewById<TabLayout>(com.lytefast.flexinput.R.id.content_tabs) ?: return@post
                val pager = view.findViewById<ViewPager>(com.lytefast.flexinput.R.id.content_pager) ?: return@post
                tabs.clearOnTabSelectedListeners()
                pager.clearOnPageChangeListeners()
                tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab) { pager.currentItem = tab.position }
                    override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                    override fun onTabReselected(tab: TabLayout.Tab) = Unit
                })
                pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                    override fun onPageSelected(position: Int) { tabs.getTabAt(position)?.select() }
                })
                pager.currentItem = 0
                tabs.getTabAt(0)?.select()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.registerFragmentLifecycleCallbacks(addContentTabCallbacks, false)
    }

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
        onAttachmentsCleared = {}
        super.onDestroyView()
    }

    override fun onDestroy() {
        childFragmentManager.unregisterFragmentLifecycleCallbacks(addContentTabCallbacks)
        super.onDestroy()
    }

    override fun clearAttachments() {
        super.clearAttachments()
        onAttachmentsCleared()
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureAppsButton(view) {
            val channelId = (parentFragment as? WidgetChatInput)?.currentChannelId() ?: return@configureAppsButton
            WidgetAppLauncher.show(parentFragmentManager, channelId, AppLauncherEntrypoint.TEXT)
        }
    }

    private companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        fun inputMethodManager(context: Context) =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
}

internal fun configureAppsButton(root: View, onClick: () -> Unit) {
    val addButton = root.findViewById<ImageView>(R.id.add_btn)
    root.findViewById<ImageView>(R.id.gift_btn)?.apply {
        addButton?.let { source ->
            layoutParams.width = source.layoutParams.width
            layoutParams.height = source.layoutParams.height
            minimumWidth = source.minimumWidth
            minimumHeight = source.minimumHeight
            setPadding(source.paddingLeft, source.paddingTop, source.paddingRight, source.paddingBottom)
            background = source.background?.constantState?.newDrawable(resources)?.mutate()
            imageTintList = source.imageTintList
        }
        visibility = View.VISIBLE
        setImageResource(R.drawable.ic_compass)
        contentDescription = context.getString(R.string.apps)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        isFocusable = true
        isEnabled = true
        setOnClickListener { onClick() }
    }
}
