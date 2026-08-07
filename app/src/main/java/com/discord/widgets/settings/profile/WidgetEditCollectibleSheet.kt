package com.discord.widgets.settings.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.collectibles.CollectibleProduct
import com.discord.api.user.AvatarDecorationData
import com.discord.api.user.Nameplate
import com.discord.api.premium.PremiumTier
import com.discord.app.AppBottomSheet
import com.discord.i18n.I18nHooks
import com.discord.stores.StoreCollectibles
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.premium.grantsAccessToCollectibles
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.recycler.MaxHeightRecyclerView
import com.discord.views.AvatarDecorationView
import com.discord.views.NameplateView
import com.discord.widgets.user.profile.ProfileFrameView
import com.discord.widgets.settings.premium.WidgetSettingsPremium
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date
import rx.subscriptions.CompositeSubscription

/**
 * Picks an owned avatar decoration or nameplate for the profile being edited.
 *
 * Tapping a tile only previews it; the choice is reported on Apply and the edit screen holds it as a
 * pending change until its own save, matching the reference. In a guild the empty tile clears the
 * override rather than the item, so it reads "Use Default" and previews the account-wide item.
 */
class WidgetEditCollectibleSheet : AppBottomSheet() {

    enum class Kind { AVATAR_DECORATION, NAMEPLATE, PROFILE_EFFECT, PROFILE_FRAME }

    private sealed class Option {
        object None : Option()
        data class Owned(val product: CollectibleProduct) : Option()

        val skuId: Long? get() = (this as? Owned)?.product?.skuId
    }

    private val kind: Kind
        get() = Kind.values()[argumentsOrDefault.getInt(ARG_KIND)]
    private val guildId: Long?
        get() = argumentsOrDefault.takeIf { it.containsKey(ARG_GUILD_ID) }?.getLong(ARG_GUILD_ID)

    private var selectedSkuId: Long? = null
    private var options: List<Option> = emptyList()
    private lateinit var adapter: OptionAdapter

    private lateinit var title: TextView
    private lateinit var decorationPreview: FrameLayout
    private lateinit var avatar: SimpleDraweeView
    private lateinit var avatarDecoration: AvatarDecorationView
    private lateinit var nameplatePreview: FrameLayout
    private lateinit var effectPreview: FrameLayout
    private lateinit var effectImage: SimpleDraweeView
    private lateinit var framePreview: FrameLayout
    private lateinit var frameView: ProfileFrameView
    private lateinit var nameplateView: NameplateView
    private lateinit var nameplateAvatar: SimpleDraweeView
    private lateinit var nameplateName: TextView
    private lateinit var name: TextView
    private lateinit var detail: TextView
    private lateinit var section: TextView
    private lateinit var grid: MaxHeightRecyclerView
    private lateinit var empty: TextView
    private lateinit var loading: View
    private lateinit var error: TextView
    private lateinit var apply: MaterialButton

    override fun getContentViewResId() = R.layout.widget_edit_collectible_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setBottomSheetCollapsedStateDisabled()
        title = view.findViewById(R.id.collectible_sheet_title)
        decorationPreview = view.findViewById(R.id.collectible_sheet_decoration_preview)
        avatar = view.findViewById(R.id.collectible_sheet_avatar)
        avatarDecoration = view.findViewById(R.id.collectible_sheet_avatar_decoration)
        nameplatePreview = view.findViewById(R.id.collectible_sheet_nameplate_preview)
        effectPreview = view.findViewById(R.id.collectible_sheet_effect_preview)
        effectImage = view.findViewById(R.id.collectible_sheet_effect_image)
        framePreview = view.findViewById(R.id.collectible_sheet_frame_preview)
        frameView = view.findViewById(R.id.collectible_sheet_frame)
        frameView.half = ProfileFrameView.Half.BOTH
        nameplateView = view.findViewById(R.id.collectible_sheet_nameplate)
        nameplateAvatar = view.findViewById(R.id.collectible_sheet_nameplate_avatar)
        nameplateName = view.findViewById(R.id.collectible_sheet_nameplate_name)
        name = view.findViewById(R.id.collectible_sheet_name)
        detail = view.findViewById(R.id.collectible_sheet_detail)
        section = view.findViewById(R.id.collectible_sheet_section)
        grid = view.findViewById(R.id.collectible_sheet_grid)
        empty = view.findViewById(R.id.collectible_sheet_empty)
        loading = view.findViewById(R.id.collectible_sheet_loading)
        error = view.findViewById(R.id.collectible_sheet_error)
        apply = view.findViewById(R.id.collectible_sheet_apply)

        selectedSkuId = savedInstanceState?.takeIf { it.containsKey(STATE_SELECTED) }?.getLong(STATE_SELECTED)
            ?: argumentsOrDefault.takeIf { it.containsKey(ARG_SELECTED_SKU_ID) }?.getLong(ARG_SELECTED_SKU_ID)

        title.setText(
            when (kind) {
                Kind.AVATAR_DECORATION -> R.string.collectibles_change_avatar_decoration
                Kind.NAMEPLATE -> R.string.collectibles_change_nameplate
                Kind.PROFILE_EFFECT -> R.string.collectibles_change_profile_effect
                Kind.PROFILE_FRAME -> R.string.collectibles_change_profile_frame
            },
        )
        section.setText(
            when (kind) {
                Kind.AVATAR_DECORATION -> R.string.collectibles_your_decorations
                Kind.NAMEPLATE -> R.string.collectibles_your_nameplates
                Kind.PROFILE_EFFECT -> R.string.collectibles_your_profile_effects
                Kind.PROFILE_FRAME -> R.string.collectibles_your_profile_frames
            },
        )
        empty.setText(
            when (kind) {
                Kind.AVATAR_DECORATION -> R.string.collectibles_empty_decorations
                Kind.NAMEPLATE -> R.string.collectibles_empty_nameplates
                Kind.PROFILE_EFFECT -> R.string.collectibles_empty_profile_effects
                Kind.PROFILE_FRAME -> R.string.collectibles_empty_profile_frames
            },
        )
        decorationPreview.visibility = View.GONE
        nameplatePreview.visibility = View.GONE
        effectPreview.visibility = View.GONE
        framePreview.visibility = View.GONE
        when (kind) {
            Kind.AVATAR_DECORATION -> decorationPreview.visibility = View.VISIBLE
            Kind.NAMEPLATE -> nameplatePreview.visibility = View.VISIBLE
            Kind.PROFILE_EFFECT -> effectPreview.visibility = View.VISIBLE
            Kind.PROFILE_FRAME -> framePreview.visibility = View.VISIBLE
        }

        StoreStream.getUsers().getMe().let { me ->
            val url = IconUtils.getForUser(me)
            avatar.setImageURI(url)
            nameplateAvatar.setImageURI(url)
            nameplateName.text = me.username
        }

        adapter = OptionAdapter()
        grid.adapter = adapter
        apply.setOnClickListener {
            val product = options.firstOrNull { it.skuId == selectedSkuId } as? Option.Owned
            if (product?.product?.isLocked() == true) {
                WidgetSettingsPremium.launch(requireContext())
                dismiss()
                return@setOnClickListener
            }
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                bundleOf(
                    RESULT_KIND to kind.ordinal,
                    RESULT_SKU_ID to (selectedSkuId ?: NO_SKU),
                ),
            )
            dismiss()
        }
        StoreStream.getCollectibles().fetchPurchases()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedSkuId?.let { outState.putLong(STATE_SELECTED, it) }
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        // connectRx emits on the store thread; without this the first render lands (synchronously,
        // on the caller) and every later one is dropped, leaving the sheet spinning forever.
        StoreStream.getCollectibles().observeState()
            .ui(this)
            .appSubscribe(WidgetEditCollectibleSheet::class.java) { state -> render(state) }
    }

    private fun render(state: StoreCollectibles.State) {
        val type = when (kind) {
            Kind.AVATAR_DECORATION -> CollectibleProduct.TYPE_AVATAR_DECORATION
            Kind.NAMEPLATE -> CollectibleProduct.TYPE_NAMEPLATE
            Kind.PROFILE_EFFECT -> CollectibleProduct.TYPE_PROFILE_EFFECT
            Kind.PROFILE_FRAME -> CollectibleProduct.TYPE_PROFILE_FRAME
        }
        val owned = state.purchases.values
            .filter { product -> product.items.any { it.type == type } && product.isActive() }
        options = listOf(Option.None) + owned.map(Option::Owned)
        adapter.submit(options)
        // A failed or in-flight fetch is not an empty inventory, and saying so hides real breakage.
        loading.visibility = if (state.isFetching && !state.hasFetched) View.VISIBLE else View.GONE
        empty.visibility = if (state.hasFetched && owned.isEmpty()) View.VISIBLE else View.GONE
        error.visibility = if (state.failed && !state.hasFetched) View.VISIBLE else View.GONE
        // The grid still holds the "none" tile when nothing is owned, and that is the only way to
        // clear a collectible already applied — hiding it stranded anyone whose inventory lapsed.
        grid.visibility = if (state.hasFetched) View.VISIBLE else View.GONE
        renderSelection()
    }

    private fun renderSelection() {
        val selected = options.firstOrNull { it.skuId == selectedSkuId } as? Option.Owned
        val product = selected?.product
        name.text = product?.name ?: getString(
            if (guildId != null) R.string.collectibles_use_default else R.string.avatar_decoration_none,
        )
        val locked = product?.isLocked() == true
        detail.text = when {
            locked && kind == Kind.AVATAR_DECORATION -> getString(R.string.collectibles_nitro_locked_decoration)
            locked -> getString(R.string.collectibles_nitro_locked_nameplate)
            else -> product?.detailText().orEmpty()
        }
        detail.visibility = if (detail.text.isNullOrBlank()) View.GONE else View.VISIBLE
        when (kind) {
            Kind.AVATAR_DECORATION -> avatarDecoration.bind(product?.avatarDecoration(), animate = true)
            Kind.NAMEPLATE ->
                nameplateView.bind(product?.nameplate(), NameplateView.Placement.MEMBER_LIST, selected = true)
            Kind.PROFILE_EFFECT -> effectImage.setImageURI(product?.profileEffectThumbnail())
            Kind.PROFILE_FRAME -> frameView.bind(product?.profileFrame())
        }
        // Owning a Nitro-exclusive item is not enough once the subscription lapses, so the button
        // becomes the upsell rather than the tile becoming unselectable.
        apply.setText(
            when {
                !locked -> R.string.avatar_decoration_modal_apply
                isPremium -> R.string.collectibles_upgrade_to_unlock
                else -> R.string.collectibles_unlock_with_nitro
            },
        )
        apply.isEnabled = locked || selectedSkuId != originalSkuId()
        adapter.notifyDataSetChanged()
    }

    private fun originalSkuId(): Long? = argumentsOrDefault
        .takeIf { it.containsKey(ARG_SELECTED_SKU_ID) }?.getLong(ARG_SELECTED_SKU_ID)

    /** Owned, but Nitro-exclusive and the subscription no longer covers it. */
    private fun CollectibleProduct.isLocked() = isPremium && !canUseCollectibles

    private val canUseCollectibles: Boolean
        get() = StoreStream.getUsers().getMe().premiumTier.grantsAccessToCollectibles()

    private val isPremium: Boolean
        get() = StoreStream.getUsers().getMe().premiumTier != PremiumTier.NONE

    private fun CollectibleProduct.detailText(): String? {
        val context = context ?: return null
        expiresAt?.let {
            return I18nHooks.format(
                context,
                R.string.collectibles_expires_date,
                mapOf("date" to DATE_FORMAT.format(Date(it.dateTimeMillis))),
            ).toString()
        }
        purchasedAt?.let {
            return I18nHooks.format(
                context,
                R.string.collectibles_acquired_date,
                mapOf("date" to DATE_FORMAT.format(Date(it.dateTimeMillis))),
            ).toString()
        }
        return summary.takeIf(String::isNotBlank)
    }

    private inner class OptionAdapter : RecyclerView.Adapter<OptionViewHolder>() {
        private var data: List<Option> = emptyList()

        fun submit(options: List<Option>) {
            data = options
            notifyDataSetChanged()
        }

        override fun getItemCount() = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = OptionViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.widget_edit_collectible_sheet_item, parent, false),
        )

        override fun onBindViewHolder(holder: OptionViewHolder, position: Int) = holder.bind(data[position])
    }

    private inner class OptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: SimpleDraweeView = itemView.findViewById(R.id.collectible_tile_image)
        private val label: TextView = itemView.findViewById(R.id.collectible_tile_label)
        private val lock: View = itemView.findViewById(R.id.collectible_tile_lock)

        fun bind(option: Option) {
            itemView.layoutParams = itemView.layoutParams.apply { height = itemView.resources.tileSize() }
            itemView.isSelected = option.skuId == selectedSkuId
            itemView.setOnClickListener {
                selectedSkuId = option.skuId
                renderSelection()
            }
            when (option) {
                Option.None -> {
                    image.visibility = View.GONE
                    lock.visibility = View.GONE
                    label.visibility = View.VISIBLE
                    label.setText(
                        if (guildId != null) R.string.collectibles_use_default else R.string.avatar_decoration_none,
                    )
                }
                is Option.Owned -> {
                    image.visibility = View.VISIBLE
                    label.visibility = View.GONE
                    lock.visibility = if (option.product.isLocked()) View.VISIBLE else View.GONE
                    image.setImageURI(option.product.tileImageUrl())
                    itemView.contentDescription = option.product.name
                }
            }
        }
    }

    private fun CollectibleProduct.tileImageUrl(): String? = when (kind) {
        Kind.AVATAR_DECORATION -> avatarDecoration()?.imageUrls(animate = false)?.firstOrNull()
        Kind.NAMEPLATE -> nameplate()?.staticImageUrl()
        Kind.PROFILE_EFFECT -> profileEffectThumbnail()
        Kind.PROFILE_FRAME -> profileFrame()?.topStaples()?.firstOrNull()?.assetUrl(skuId)
    }

    private fun android.content.res.Resources.tileSize() = (TILE_HEIGHT_DP * displayMetrics.density).toInt()

    companion object {
        const val REQUEST_KEY = "EDIT_COLLECTIBLE"
        const val RESULT_KIND = "kind"
        const val RESULT_SKU_ID = "sku_id"

        /** Reported instead of a SKU when the user picked None, or Use Default in a guild. */
        const val NO_SKU = -1L

        private const val ARG_KIND = "kind"
        private const val ARG_GUILD_ID = "guild_id"
        private const val ARG_SELECTED_SKU_ID = "selected_sku_id"
        private const val STATE_SELECTED = "state_selected"
        private const val TAG = "WidgetEditCollectibleSheet"
        private const val TILE_HEIGHT_DP = 88

        private val DATE_FORMAT: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

        fun show(fragmentManager: FragmentManager, kind: Kind, guildId: Long?, selectedSkuId: Long?) {
            WidgetEditCollectibleSheet().apply {
                arguments = bundleOf(ARG_KIND to kind.ordinal).apply {
                    guildId?.let { putLong(ARG_GUILD_ID, it) }
                    selectedSkuId?.let { putLong(ARG_SELECTED_SKU_ID, it) }
                }
            }.show(fragmentManager, TAG)
        }

        fun registerForResult(fragment: Fragment, onPicked: (Kind, Long?) -> Unit) {
            fragment.parentFragmentManager.setFragmentResultListener(
                REQUEST_KEY,
                fragment.viewLifecycleOwner,
            ) { _, bundle ->
                val kind = Kind.values()[bundle.getInt(RESULT_KIND)]
                onPicked(kind, bundle.getLong(RESULT_SKU_ID).takeIf { it != NO_SKU })
            }
        }
    }
}
