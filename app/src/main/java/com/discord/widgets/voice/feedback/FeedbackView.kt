package com.discord.widgets.voice.feedback

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R

class FeedbackView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val happyRating: View
    private val issueSectionHeader: TextView
    private val issuesCard: CardView
    private val issuesRecycler: RecyclerView
    private val neutralRating: View
    private val ratingContainer: ConstraintLayout
    private val ratingSummaryPrompt: TextView
    private val sadRating: View
    private val issuesAdapter = IssuesAdapter()
    private val viewToFeedbackRatingMap: Map<View, FeedbackRating>

    init {
        LayoutInflater.from(context).inflate(R.layout.feedback_view, this, true)
        happyRating = requireNotNull(findViewById(R.id.feedback_happy_rating))
        issueSectionHeader = requireNotNull(findViewById(R.id.feedback_issue_section_header))
        issuesCard = requireNotNull(findViewById(R.id.feedback_issues_card))
        issuesRecycler = requireNotNull(findViewById(R.id.feedback_issues_recycler))
        neutralRating = requireNotNull(findViewById(R.id.feedback_neutral_rating))
        ratingContainer = requireNotNull(findViewById(R.id.feedback_rating_container))
        ratingSummaryPrompt = requireNotNull(findViewById(R.id.feedback_rating_summary_prompt))
        sadRating = requireNotNull(findViewById(R.id.feedback_sad_rating))
        viewToFeedbackRatingMap = mapOf(
            sadRating to FeedbackRating.BAD,
            neutralRating to FeedbackRating.NEUTRAL,
            happyRating to FeedbackRating.GOOD,
        )

        issuesRecycler.layoutManager = LinearLayoutManager(context)
        issuesRecycler.adapter = issuesAdapter
        issuesRecycler.setHasFixedSize(false)
    }

    fun updateView(
        ratingSummaryPromptText: String?,
        selectedFeedbackRating: FeedbackRating,
        onSadRatingClick: () -> Unit,
        onNeutralRatingClick: () -> Unit,
        onHappyRatingClick: () -> Unit,
        issuesHeaderText: String,
        feedbackIssues: List<FeedbackIssue>,
        onIssueClick: (FeedbackIssue) -> Unit,
    ) {
        ratingContainer.visibility = if (ratingSummaryPromptText != null) View.VISIBLE else View.GONE
        ratingSummaryPrompt.text = ratingSummaryPromptText
        sadRating.setOnClickListener { onSadRatingClick() }
        neutralRating.setOnClickListener { onNeutralRatingClick() }
        happyRating.setOnClickListener { onHappyRatingClick() }
        viewToFeedbackRatingMap.forEach { (view, rating) ->
            view.isSelected = rating == selectedFeedbackRating
        }

        val issuesVisibility = if (feedbackIssues.isEmpty()) View.GONE else View.VISIBLE
        issueSectionHeader.text = issuesHeaderText
        issueSectionHeader.visibility = issuesVisibility
        issuesCard.visibility = issuesVisibility
        issuesRecycler.visibility = issuesVisibility
        issuesAdapter.onIssueClick = onIssueClick
        issuesAdapter.replaceItems(feedbackIssues)
    }

    private class IssuesAdapter : RecyclerView.Adapter<IssueViewHolder>() {
        private var items: List<FeedbackIssue> = emptyList()
        var onIssueClick: (FeedbackIssue) -> Unit = {}

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = items[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IssueViewHolder {
            val item = TextView(parent.context, null, 0, R.style.UiKit_Settings_Item).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            return IssueViewHolder(item)
        }

        override fun onBindViewHolder(holder: IssueViewHolder, position: Int) {
            val issue = items[position]
            holder.item.text = holder.item.resources.getString(issue.reasonStringRes)
            holder.item.setOnClickListener { onIssueClick(issue) }
        }

        fun replaceItems(items: List<FeedbackIssue>) {
            this.items = items.toList()
            notifyDataSetChanged()
        }
    }

    private class IssueViewHolder(val item: TextView) : RecyclerView.ViewHolder(item)
}
