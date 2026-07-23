package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.discord.R

class ChatActionItem(context: Context, attrs: AttributeSet?) : CardView(context, attrs) {
    init {
        LayoutInflater.from(context).inflate(R.layout.view_chat_action_item, this)
        val image = requireNotNull<ImageView>(findViewById(R.id.chat_action_item_image))
        val text = requireNotNull<TextView>(findViewById(R.id.chat_action_item_text))
        context.obtainStyledAttributes(attrs, R.styleable.ChatActionItem, 0, 0).run {
            text.text = getString(R.styleable.ChatActionItem_action_item_text)
            image.setImageResource(
                getResourceId(
                    R.styleable.ChatActionItem_action_item_img_src,
                    R.drawable.drawable_circle_white_1,
                ),
            )
            recycle()
        }
    }
}
