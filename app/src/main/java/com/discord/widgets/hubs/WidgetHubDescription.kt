package com.discord.widgets.hubs

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.isHQDirectoryChannel
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.i18n.I18nSpans
import com.discord.models.hubs.DirectoryEntryCategory
import com.discord.stores.utilities.Loading
import com.discord.stores.utilities.handleResponse
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.validators.BasicTextInputValidator
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.InputValidator
import com.discord.utilities.view.validators.ValidationManager
import com.discord.views.LoadingButton
import com.discord.views.ScreenTitleView
import com.discord.views.ThemedColor
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Describes a server before listing it in a hub directory, and edits an existing listing.
 *
 * Binds `R.layout.widget_hub_description`. Two required fields: a free-text description and a
 * category picked through [WidgetRadioSelectorBottomSheet]. The category has no input widget of its
 * own — it is a tappable row — so its validation error is rendered into the separate
 * `category_error` text view through a [Input.GenericInput].
 *
 * Finishes with [DescriptionResult] once the listing call succeeds.
 */
class WidgetHubDescription : AppFragment(R.layout.widget_hub_description) {

    private val args: HubDescriptionArgs by hubArgs()

    private val viewModel: WidgetHubDescriptionViewModel by appViewModels {
        WidgetHubDescriptionViewModel(
            channelId = args.channelId,
            guildId = args.guildId,
            isEditing = args.isEditing,
            primaryCategoryId = args.primaryCategoryId,
        )
    }

    private lateinit var header: ScreenTitleView
    private lateinit var descriptionLayout: TextInputLayout
    private lateinit var descriptionInput: TextInputEditText
    private lateinit var categoryLayout: LinearLayout
    private lateinit var categoryText: TextView
    private lateinit var categoryError: TextView
    private lateinit var addServerButton: LoadingButton

    private val validationManager: ValidationManager by lazy {
        ValidationManager(
            Input.TextInputLayoutInput(
                DESCRIPTION_FIELD,
                descriptionLayout,
                BasicTextInputValidator.createRequiredInputValidator(R.string.member_verification_form_required_item),
            ),
            Input.GenericInput(
                CATEGORY_FIELD,
                categoryLayout,
                object : InputValidator<LinearLayout> {
                    override fun getErrorMessage(view: LinearLayout): CharSequence? =
                        if (viewModel.getCategory() != null) null
                        else view.context.getString(R.string.billing_address_address_error_required)
                },
            ) { _, errorMessage ->
                categoryError.text = errorMessage
                categoryError.visibility = View.VISIBLE
                true
            },
        )
    }

    /** Rows for the category sheet, with the current pick checked. */
    private val selectorArgs: RadioSelectorItems
        get() = RadioSelectorItems(
            title = requireContext().getString(R.string.categories),
            items = viewModel.withViewState { state ->
                val isHQ = state.channel?.isHQDirectoryChannel() == true
                DirectoryEntryCategory.getCategories(isHQ).map { category ->
                    RadioSelectorItem(
                        id = category.key,
                        text = category.getTitle(requireContext()),
                        selected = viewModel.getCategory()?.key == category.key,
                    )
                }
            },
        )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        header = view.findViewById(R.id.header)
        descriptionLayout = view.findViewById(R.id.description_layout)
        descriptionInput = view.findViewById(R.id.description)
        categoryLayout = view.findViewById(R.id.category_layout)
        categoryText = view.findViewById(R.id.category)
        categoryError = view.findViewById(R.id.category_error)
        addServerButton = view.findViewById(R.id.add_server)

        descriptionInput.setText(args.description)
        categoryLayout.setOnClickListener {
            WidgetRadioSelectorBottomSheet.show(childFragmentManager, selectorArgs) { id ->
                viewModel.setCategory(id)
            }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHubDescription::class.java) { configureUI(it) }
    }

    private fun configureUI(state: HubDescriptionState) {
        val context = context ?: return

        header.setTitle(
            I18nSpans.render(
                getString(
                    if (args.isEditing) R.string.hub_entry_update_description_title
                    else R.string.hub_customize_existing_guild_title,
                ),
                "guildName" to state.guildName,
            ),
        )

        addServerButton.setOnClickListener { maybeAddServer() }
        addServerButton.setIsLoading(state.addServerAsync is Loading)
        addServerButton.setText(getString(if (args.isEditing) R.string.save else R.string.hub_add_server_cta))

        val category = state.selectedCategory
        categoryText.setTextColor(
            ThemedColor.resolve(
                context,
                if (category != null) R.attr.colorTextNormal else R.attr.colorTextMuted,
            ),
        )
        categoryText.text = category?.getTitle(context) ?: getString(R.string.categories)
        if (category != null) categoryError.visibility = View.GONE

        state.addServerAsync.handleResponse(context) { success ->
            storeDirectories().addServerToDirectory(args.channelId, success.response)
            hideKeyboard(view)
            val activity = activity ?: return@handleResponse
            activity.setResult(
                Activity.RESULT_OK,
                DescriptionResult(success.response.guild.id, args.hubName).toArgsIntent(),
            )
            activity.finish()
        }
    }

    private fun maybeAddServer() {
        if (validationManager.validate(showErrors = true)) {
            viewModel.addServer(args.channelId, args.guildId, descriptionInput.text?.toString().orEmpty())
        }
    }

    private companion object {
        const val DESCRIPTION_FIELD = "description"
        const val CATEGORY_FIELD = "category"
    }
}
