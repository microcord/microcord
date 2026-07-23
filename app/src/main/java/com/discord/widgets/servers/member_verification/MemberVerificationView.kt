package com.discord.widgets.servers.member_verification

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.discord.R
import com.google.android.material.button.MaterialButton

class MemberVerificationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CardView(context, attrs, defStyleAttr) {
    private val verified: TextView
    private val button: MaterialButton
    private val icon: ImageView
    private val title: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_member_verification, this)
        verified = requireNotNull(findViewById(R.id.member_verification_verified_check))
        button = requireNotNull(findViewById(R.id.member_verification_verify_button))
        icon = requireNotNull(findViewById(R.id.member_verification_verify_icon))
        title = requireNotNull(findViewById(R.id.member_verification_verify_text))
    }

    @JvmOverloads
    fun configure(
        verificationImageIcon: Int,
        verificationTitleText: Int,
        isVerified: Boolean,
        verificationCallback: (() -> Unit)? = null,
    ) {
        icon.setImageResource(verificationImageIcon)
        title.setText(verificationTitleText)
        button.setOnClickListener { verificationCallback?.invoke() }
        verified.visibility = if (isVerified) View.VISIBLE else View.GONE
        button.visibility = if (isVerified) View.GONE else View.VISIBLE
    }
}
