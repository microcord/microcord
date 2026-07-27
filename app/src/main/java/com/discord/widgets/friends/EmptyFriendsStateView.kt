package com.discord.widgets.friends

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
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

    /**
     * Points the button at the add-friend screen, tagging the analytics event with where the empty
     * state was shown ("Friends" from the friends tab, "Direct Messages" from the DM list).
     *
     * Delegates to [WidgetFriendsAdd.show] rather than building the routing intent by hand, so the
     * "Add Friend Modal" analytics event cannot be skipped by this entry point.
     */
    fun updateView(addFriendSource: String) {
        addFriend.setOnClickListener {
            WidgetFriendsAdd.show(context, source = addFriendSource)
        }
    }

    fun setOnAddFriendClicked(callback: () -> Unit) {
        addFriend.setOnClickListener { callback() }
    }
}
