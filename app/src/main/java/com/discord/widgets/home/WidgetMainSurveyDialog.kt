package com.discord.widgets.home

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.utilities.uri.UriHandler
import com.google.android.material.button.MaterialButton

/**
 * The "would you take a survey?" prompt shown over the home surface.
 *
 * Accepting opens the survey URL in the browser through [UriHandler] — surveys are hosted off-app —
 * and declining just dismisses. Both arms close the dialog, so the prompt is offered once per
 * qualifying session rather than nagging.
 *
 * The dialog dismisses itself immediately if either the survey id or its URL is missing, because
 * without both there is nothing to open and nothing to attribute the response to.
 */
class WidgetMainSurveyDialog : AppDialog(R.layout.view_dialog_confirmation) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val surveyId = arguments?.getString(EXTRA_SURVEY_ID)
        val surveyUrl = arguments?.getString(EXTRA_SURVEY_URL)
        val surveyBody = arguments?.getString(EXTRA_SURVEY_BODY)
        val surveyTitle = arguments?.getString(EXTRA_SURVEY_TITLE)
        if (surveyUrl.isNullOrEmpty() || surveyId.isNullOrEmpty()) {
            dismiss()
            return
        }

        view.findViewById<TextView>(R.id.view_dialog_confirmation_header).text = surveyTitle
        view.findViewById<TextView>(R.id.view_dialog_confirmation_text).text = surveyBody
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_cancel).apply {
            setText(R.string.no_thanks)
            setOnClickListener { dismiss() }
        }
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_confirm).apply {
            setText(R.string.okay)
            setOnClickListener { button ->
                UriHandler.handle(button.context, surveyUrl)
                dismiss()
            }
        }
        onSurveyViewed(surveyId)
    }

    companion object {
        private const val EXTRA_SURVEY_ID = "extra_survey_id"
        private const val EXTRA_SURVEY_URL = "extra_survey_url"
        private const val EXTRA_SURVEY_BODY = "extra_survey_body"
        private const val EXTRA_SURVEY_TITLE = "extra_survey_title"

        private const val TAG = "WidgetMainSurveyDialog"

        /**
         * The reference raises `AnalyticsTracker.surveyViewed(surveyId)` here. That event is not in
         * this project's `AnalyticsTracker` yet, so the call site is kept as a seam rather than
         * approximated with a different event name.
         */
        @JvmField
        var onSurveyViewed: (surveyId: String) -> Unit = {}

        fun show(
            fragmentManager: FragmentManager,
            surveyId: String,
            surveyUrl: String,
            surveyBody: String,
            surveyTitle: String,
        ) {
            WidgetMainSurveyDialog().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_SURVEY_ID, surveyId)
                    putString(EXTRA_SURVEY_URL, surveyUrl)
                    putString(EXTRA_SURVEY_BODY, surveyBody)
                    putString(EXTRA_SURVEY_TITLE, surveyTitle)
                }
            }.show(fragmentManager, TAG)
        }
    }
}
