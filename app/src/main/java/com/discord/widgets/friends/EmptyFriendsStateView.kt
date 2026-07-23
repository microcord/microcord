package com.discord.widgets.friends

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.app.AppActivity
import com.google.android.material.button.MaterialButton

class EmptyFriendsStateView : ConstraintLayout {
    private lateinit var addFriend: MaterialButton
    private lateinit var title: TextView

    constructor(context: Context) : super(context) {
        inflateContent()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        inflateContent()
        initialize(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        inflateContent()
        initialize(attrs)
    }

    private fun inflateContent() {
        LayoutInflater.from(context).inflate(R.layout.empty_friends_state_view, this)
        addFriend = requireNotNull(findViewById(R.id.empty_friends_state_add_friend))
        title = requireNotNull(findViewById(R.id.empty_friends_state_title))
    }

    fun initialize(attrs: AttributeSet?) {
        val textSize = context.obtainStyledAttributes(attrs, intArrayOf(R.attr.ef_title_text_size)).run {
            try {
                getResourceId(0, R.dimen.uikit_textsize_xlarge)
            } finally {
                recycle()
            }
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(textSize))
    }

    fun updateView(addFriendSource: String) {
        addFriend.setOnClickListener { WidgetFriendsAddContract.show(context, addFriendSource) }
    }
}

internal object WidgetFriendsAddContract {
    const val EXTRA_SCREEN = "com.discord.intent.extra.EXTRA_SCREEN"
    const val EXTRA_SOURCE = "com.discord.intent.extra.ADD_FRIEND_SOURCE"
    const val SCREEN = "com.discord.widgets.friends.WidgetFriendsAdd"

    fun show(context: Context, source: String) {
        context.startActivity(
            Intent(context, AppActivity.Main::class.java)
                .putExtra(EXTRA_SCREEN, SCREEN)
                .putExtra(Intent.EXTRA_TEXT, null as String?)
                .putExtra(EXTRA_SOURCE, source),
        )
    }
}
