package com.discord.widgets.chat.input

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.databinding.CommandCategoryItemBinding
import com.discord.models.commands.Application

class ChatInputApplicationsAdapter : RecyclerView.Adapter<ApplicationCategoryViewHolder>() {
    private var data: List<Application> = emptyList()
    lateinit var onClickApplication: (Application) -> Unit
    private var selectedApplication: Long = 0

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = data.size

    override fun getItemId(position: Int): Long = data[position].id

    fun getPositionOfApplication(applicationId: Long): Int =
        data.indexOfFirst { it.id == applicationId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ApplicationCategoryViewHolder =
        ApplicationCategoryViewHolder(
            CommandCategoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onClickApplication,
        )

    override fun onBindViewHolder(holder: ApplicationCategoryViewHolder, position: Int) {
        val application = data[position]
        holder.bind(application, selectedApplication == application.id)
    }

    fun selectApplication(applicationId: Long) {
        if (selectedApplication != applicationId) {
            selectedApplication = applicationId
            notifyDataSetChanged()
        }
    }

    fun setApplicationData(data: List<Application>) {
        this.data = data
        notifyDataSetChanged()
    }
}
