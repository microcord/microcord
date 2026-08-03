package com.discord.widgets.applicationdirectory

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.api.applicationdirectory.ApplicationDirectoryCategory
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.databinding.WidgetApplicationDirectoryBinding
import com.discord.databinding.WidgetApplicationDirectoryItemBinding
import com.discord.dialogs.SelectorDialog
import com.discord.stores.StoreApplicationDirectory
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import java.text.NumberFormat
import java.util.concurrent.TimeUnit
import rx.Scheduler
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

class WidgetApplicationDirectory : AppFragment(R.layout.widget_application_directory) {
    private var binding: WidgetApplicationDirectoryBinding? = null
    private val viewModel: ApplicationDirectoryViewModel by appViewModels { ApplicationDirectoryViewModel() }
    private val adapter = ApplicationAdapter { WidgetApplicationDirectoryDetail.launch(requireContext(), it.id) }
    private var displayedCategories: List<ApplicationDirectoryCategory> = emptyList()
    private var selectedCategoryId: Int? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetApplicationDirectoryBinding.bind(view).also { this.binding = it }
        binding.toolbar.setNavigationOnClickListener { activity?.onBackPressed() }
        binding.applications.layoutManager = LinearLayoutManager(requireContext())
        binding.applications.adapter = adapter
        binding.loadMore.setOnClickListener { viewModel.loadMore() }
        binding.message.setOnClickListener { viewModel.retry() }
        binding.category.setOnClickListener { showCategorySelector() }
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                viewModel.search(s?.toString().orEmpty(), selectedCategory())
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState ?: return
        selectedCategoryId = savedInstanceState.getInt(STATE_CATEGORY_ID).takeUnless { it == NO_CATEGORY }
        binding?.search?.setText(savedInstanceState.getString(STATE_QUERY).orEmpty())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_QUERY, binding?.search?.text?.toString().orEmpty())
        outState.putInt(STATE_CATEGORY_ID, selectedCategoryId ?: NO_CATEGORY)
        super.onSaveInstanceState(outState)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).appSubscribe(javaClass) { render(it) }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun selectedCategory(): Int? = selectedCategoryId

    private fun showCategorySelector() {
        val binding = binding ?: return
        if (displayedCategories.isEmpty()) {
            viewModel.retryCategories()
            return
        }
        val options = listOf(getString(R.string.application_directory_all_categories)) +
            displayedCategories.map(ApplicationDirectoryCategory::name)
        SelectorDialog.show(
            parentFragmentManager,
            getString(R.string.application_directory_choose_category),
            Array<CharSequence>(options.size, options::get),
        ) { position ->
            viewModel.search(binding.search.text?.toString().orEmpty(), displayedCategories.getOrNull(position - 1)?.id)
        }
    }

    private fun render(state: StoreApplicationDirectory.State) {
        val binding = binding ?: return
        if (displayedCategories != state.categories) {
            displayedCategories = state.categories
        }
        val search = state.search
        selectedCategoryId = search.categoryId
        binding.category.setText(
            state.categories.firstOrNull { it.id == search.categoryId }?.name
                ?: getString(
                    if (state.categoriesError && state.categories.isEmpty()) R.string.application_directory_categories_error
                    else R.string.application_directory_all_categories,
                ),
        )
        binding.category.isEnabled = !state.categoriesLoading
        binding.category.setIsLoading(state.categoriesLoading)
        adapter.submit(search.applications)
        val loading = search.loading && search.applications.isEmpty()
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) binding.progress.start() else binding.progress.stop()
        binding.applications.visibility = if (search.applications.isEmpty()) View.GONE else View.VISIBLE
        binding.loadMore.visibility = if (search.canLoadMore || search.loading && search.applications.isNotEmpty()) View.VISIBLE else View.GONE
        binding.loadMore.isEnabled = !search.loading
        binding.loadMore.setIsLoading(search.loading && search.applications.isNotEmpty())
        binding.message.visibility = if (search.error || search.empty) View.VISIBLE else View.GONE
        binding.message.setText(if (search.error) R.string.application_directory_error else R.string.application_directory_empty)
        binding.message.isClickable = search.error
        binding.message.isFocusable = search.error
    }

    companion object {
        private const val STATE_QUERY = "application_directory_query"
        private const val STATE_CATEGORY_ID = "application_directory_category_id"
        private const val NO_CATEGORY = Int.MIN_VALUE

        fun launch(context: Context) = AppScreenRouter.launch(context, WidgetApplicationDirectory::class.java)
    }
}

class ApplicationDirectoryViewModel(
    private val store: StoreApplicationDirectory = StoreStream.getApplicationDirectory(),
    debounceScheduler: Scheduler = Schedulers.computation(),
) : AppViewModel<StoreApplicationDirectory.State>(store.getState()) {
    private val searchDebouncer = ApplicationDirectorySearchDebouncer(debounceScheduler, store::search)

    init {
        store.observeState().ui(this).subscribe(::updateViewState)
        store.loadCategories()
        store.search("")
    }

    fun search(query: String, categoryId: Int?) = searchDebouncer.submit(query, categoryId)
    fun loadMore() = store.loadNextPage()
    fun retry() = withViewState { store.search(it.search.query, it.search.categoryId) }
    fun retryCategories() = store.loadCategories(force = true)

    override fun onCleared() {
        searchDebouncer.clear()
        super.onCleared()
    }
}

internal class ApplicationDirectorySearchDebouncer(
    scheduler: Scheduler,
    search: (String, Int?) -> Unit,
) {
    private data class Query(val text: String, val categoryId: Int?)
    private val queries = PublishSubject.create<Query>()
    private val subscription = queries.debounce(300, TimeUnit.MILLISECONDS, scheduler)
        .distinctUntilChanged()
        .subscribe { search(it.text, it.categoryId) }

    fun submit(query: String, categoryId: Int?) = queries.onNext(Query(query, categoryId))
    fun clear() = subscription.unsubscribe()
}

private class ApplicationAdapter(
    private val onClick: (ApplicationDirectoryApplication) -> Unit,
) : RecyclerView.Adapter<ApplicationAdapter.Holder>() {
    private var items = emptyList<ApplicationDirectoryApplication>()

    fun submit(value: List<ApplicationDirectoryApplication>) {
        if (items == value) return
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        WidgetApplicationDirectoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        onClick,
    )

    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    class Holder(
        private val binding: WidgetApplicationDirectoryItemBinding,
        private val onClick: (ApplicationDirectoryApplication) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(application: ApplicationDirectoryApplication) {
            binding.name.text = application.name
            binding.description.text = application.directoryEntry?.shortDescription
                ?.takeIf(String::isNotBlank) ?: application.description
            val installs = application.approximateUserInstallCount ?: application.approximateGuildCount
            binding.metadata.text = installs?.let {
                binding.root.context.getString(
                    R.string.application_directory_installs,
                    NumberFormat.getIntegerInstance().format(it),
                )
            }.orEmpty()
            MGImages.setImage(
                binding.icon,
                application.icon?.let { IconUtils.getApplicationIcon(application.id, it) },
            )
            binding.root.contentDescription = application.name
            binding.root.setOnClickListener { onClick(application) }
        }
    }
}
