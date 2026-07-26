package com.discord.widgets.friends

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import com.discord.R
import com.discord.utilities.simple_pager.SimplePager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

class WidgetFriendsAdd : AppFragment(R.layout.widget_friends_add) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = getString(R.string.friends_section_add_friend)
        view.findViewById<SimplePager>(R.id.add_friend_view_pager).apply {
            adapter = SimplePager.Adapter(
                childFragmentManager,
                SimplePager.Item(getString(R.string.username)) { WidgetFriendsAddById() },
                SimplePager.Item(getString(R.string.nearby_scan)) { WidgetFriendsFindNearby() },
            )
            setTabLayout(view.findViewById(R.id.action_bar_tabs))
        }
    }
}

class WidgetFriendsAddById : AppFragment(R.layout.widget_friends_add_by_id) {
    private var loading = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val input = view.findViewById<TextInputLayout>(R.id.friends_add_text_edit_wrap)
        val send = view.findViewById<MaterialButton>(R.id.friends_add_send)
        fun updateButton() {
            send.isEnabled = !loading && input.editText?.text?.isNotBlank() == true
            send.setText(if (loading) R.string.loading else R.string.add_friend_add_manually)
        }
        input.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { input.error = null; updateButton() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        fun submit(captchaToken: String? = null) {
            val parsed = FriendUsernameValidator.parse(input.editText?.text?.toString().orEmpty())
            if (parsed == null) {
                input.error = getString(R.string.add_friend_error_username_only)
                    .replace("!!{username}!!", input.editText?.text?.toString().orEmpty().substringBefore('#'))
                return
            }
            loading = true
            updateButton()
            submitRequest(parsed, captchaToken) { result ->
                view.post {
                    if (!isAdded) return@post
                    loading = false
                    updateButton()
                    when (result) {
                        AddFriendResult.Success -> {
                            input.editText?.text?.clear()
                            Toast.makeText(context, getString(R.string.add_friend_confirmation).replace("!!{name}!!", parsed.username), Toast.LENGTH_SHORT).show()
                        }
                        is AddFriendResult.Error -> input.error = result.message
                        is AddFriendResult.Captcha -> onCaptcha(result.siteKey, result.rqtoken) { token ->
                            if (token == null) input.error = getString(R.string.captcha_failed) else submit(token)
                        }
                    }
                }
            }
        }
        send.setOnClickListener { submit() }
        input.editText?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        initialQuery?.takeIf(String::isNotBlank)?.let { input.editText?.setText(it) }
        view.findViewById<TextView>(R.id.friend_add_username_indicator).apply {
            text = ownUsername.orEmpty()
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        updateButton()
    }

    companion object {
        @JvmField var initialQuery: String? = null
        @JvmField var ownUsername: String? = null
        @JvmField var submitRequest: (UsernameTag, String?, (AddFriendResult) -> Unit) -> Unit = { _, _, done ->
            done(AddFriendResult.Error("Unable to send friend request."))
        }
        @JvmField var onCaptcha: (String, String?, (String?) -> Unit) -> Unit = { _, _, done -> done(null) }
    }
}

class WidgetFriendsFindNearby : AppFragment(R.layout.widget_friends_nearby) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.nearby_friends_searching_lottie).visibility = View.GONE
        view.findViewById<View>(R.id.nearby_friends_recycler).visibility = View.GONE
        view.findViewById<View>(R.id.nearby_friends_learn_more).visibility = View.GONE
        view.findViewById<TextView>(R.id.nearby_friends_searching_title).setText(R.string.nearby_scan)
        view.findViewById<TextView>(R.id.nearby_friends_searching_body).setText(R.string.add_friend_nearby_connection_error)
        view.findViewById<MaterialButton>(R.id.nearby_friends_enable).apply {
            setText(R.string.retry)
            setOnClickListener { onRetry() }
        }
    }

    companion object { @JvmField var onRetry: () -> Unit = {} }
}
