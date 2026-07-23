package com.discord.widgets.servers.creator_monetization_eligibility.onboarding

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import com.discord.R

class AcceptTermsStepCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ConstraintLayout(context, attrs) {
    private val stepNumber: Int
    private val imageSrcRes: Int
    private val titleStringRes: Int
    private val descriptionStringRes: Int
    private val termsUrl: String?

    init {
        context.obtainStyledAttributes(attrs, R.styleable.AcceptTermsStepCard).run {
            stepNumber = requiredInt(R.styleable.AcceptTermsStepCard_step_number)
            imageSrcRes = requiredResource(R.styleable.AcceptTermsStepCard_image_src)
            titleStringRes = requiredResource(R.styleable.AcceptTermsStepCard_step_title)
            descriptionStringRes = requiredResource(R.styleable.AcceptTermsStepCard_step_description)
            termsUrl = getString(R.styleable.AcceptTermsStepCard_terms_url)
            recycle()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val customChildren = children.toList()
        LayoutInflater.from(context).inflate(R.layout.view_creator_monetization_accept_terms_step_card, this)
        val number = requireNotNull<TextView>(findViewById(R.id.step_number))
        val image = requireNotNull<ImageView>(findViewById(R.id.image))
        val imagePadding = requireNotNull<Space>(findViewById(R.id.image_padding))
        val title = requireNotNull<TextView>(findViewById(R.id.title))
        val description = requireNotNull<TextView>(findViewById(R.id.description))
        number.text = stepNumber.toString()
        image.setImageResource(imageSrcRes)
        title.setText(titleStringRes)
        description.text = termsUrl?.let {
            linkedDescription(context.getString(descriptionStringRes).replace("{fullTermsUrl}", it))
        } ?: context.getText(descriptionStringRes)
        if (termsUrl != null) description.movementMethod = LinkMovementMethod.getInstance()

        var previousId = description.id
        customChildren.forEach { child ->
            child.layoutParams = LayoutParams(child.layoutParams).apply {
                width = 0
                topToBottom = previousId
                startToEnd = imagePadding.id
                endToEnd = LayoutParams.PARENT_ID
            }
            previousId = child.id
        }
    }

    private fun android.content.res.TypedArray.requiredInt(index: Int): Int {
        check(hasValue(index)) { "Required integer attribute is missing" }
        return getInt(index, 0)
    }

    private fun android.content.res.TypedArray.requiredResource(index: Int): Int {
        check(hasValue(index)) { "Required resource attribute is missing" }
        return getResourceId(index, 0)
    }

    companion object {
        private val MARKDOWN_LINK = Regex("\\[([^]]+)]\\(([^)]+)\\)")

        internal fun linkedDescription(value: String): CharSequence {
            val (text, range, url) = parseMarkdownLink(value)
            if (range == null || url == null) return text
            return SpannableString(text).apply {
                setSpan(
                    URLSpan(url),
                    range.first,
                    range.last + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        internal fun parseMarkdownLink(value: String): Triple<String, IntRange?, String?> {
            val match = MARKDOWN_LINK.find(value) ?: return Triple(value, null, null)
            val label = match.groupValues[1]
            val start = match.range.first
            return Triple(
                value.replaceRange(match.range, label),
                start until start + label.length,
                match.groupValues[2],
            )
        }
    }
}
