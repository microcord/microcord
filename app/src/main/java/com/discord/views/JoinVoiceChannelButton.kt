package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import com.discord.R
import com.google.android.material.button.MaterialButton

class JoinVoiceChannelButton(context: Context, attrs: AttributeSet?) : MaterialButton(context, attrs) {
    init {
        setText(R.string.join_voice)
    }

    fun disableWithToast(button: MaterialButton, toastMessage: Int) {
        button.alpha = 0.3f
        button.setOnClickListener {
            Toast.makeText(button.context, toastMessage, Toast.LENGTH_SHORT).show()
        }
    }
}
