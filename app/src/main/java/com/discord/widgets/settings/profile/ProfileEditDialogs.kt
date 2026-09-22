package com.discord.widgets.settings.profile

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.discord.api.user.RecentAvatar
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppDialog
import com.discord.databinding.BottomSheetProfileAvatarSelectorBinding
import com.discord.databinding.BottomSheetSimpleSelectorItemBinding
import com.discord.databinding.ProfileRecentAvatarItemBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.google.android.material.button.MaterialButton
import com.jaredrummler.android.colorpicker.ColorPanelView
import com.jaredrummler.android.colorpicker.ColorPickerView

class ProfileAvatarSelectorSheet : AppBottomSheet() {
    private var _binding: BottomSheetProfileAvatarSelectorBinding? = null
    private val binding get() = requireNotNull(_binding)
    override fun getContentViewResId() = R.layout.bottom_sheet_profile_avatar_selector

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = BottomSheetProfileAvatarSelectorBinding.bind(view).also { _binding = it }
        setBottomSheetCollapsedStateDisabled()
        val labels = argumentsOrDefault.getCharSequenceArray(ARG_LABELS).orEmpty()
        val destructiveIndex = argumentsOrDefault.getInt(ARG_DESTRUCTIVE_INDEX, -1)
        binding.profileAvatarActions.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = SimpleRecyclerAdapter(labels.toList()) { inflater, parent ->
                OptionViewHolder(
                    BottomSheetSimpleSelectorItemBinding.inflate(inflater, parent, false),
                    destructiveIndex,
                )
            }
        }
        argumentsOrDefault.getLong(ARG_USER_ID, -1L).takeIf { it > 0L }?.let(::loadRecentAvatars)
    }

    override fun onPause() {
        super.onPause()
        dismiss()
    }
    override fun onDestroyView() {
        binding.profileAvatarActions.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun loadRecentAvatars(userId: Long) {
        binding.profileAvatarRecentHeader.isVisible = true
        binding.profileAvatarRecentLoading.isVisible = true
        RestAPI.api.getRecentAvatars()
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                ProfileAvatarSelectorSheet::class.java,
                onNext = { response -> renderRecentAvatars(userId, response.avatars) },
                errorHandler = { renderRecentAvatars(userId, emptyList()) },
            )
    }

    private fun renderRecentAvatars(userId: Long, avatars: List<RecentAvatar>) {
        val binding = _binding ?: return
        binding.profileAvatarRecentLoading.isVisible = false
        val visible = avatars.isNotEmpty()
        binding.profileAvatarRecentHeader.isVisible = visible
        binding.profileAvatarRecentScroll.isVisible = visible
        binding.profileAvatarRecentContainer.apply {
            removeAllViews()
            avatars.forEachIndexed { index, avatar ->
                val tile = ProfileRecentAvatarItemBinding.inflate(layoutInflater, this, false).root
                tile.contentDescription = avatar.description
                    ?: getString(R.string.user_settings_recent_avatar_number, index + 1)
                MGImages.setImage(
                    tile,
                    IconUtils.getForArchivedUserAvatar(
                        userId = userId,
                        avatarId = avatar.id,
                        storageHash = avatar.storageHash,
                        allowAnimation = true,
                        size = RECENT_AVATAR_SIZE,
                    ),
                    useSmallCache = false,
                )
                tile.setOnClickListener {
                    parentFragmentManager.setFragmentResult(
                        REQUEST_KEY,
                        bundleOf(
                            RESULT_RECENT_AVATAR_ID to avatar.id,
                            RESULT_RECENT_AVATAR_HASH to avatar.storageHash,
                        ),
                    )
                    dismiss()
                }
                addView(tile)
            }
        }
    }

    private inner class OptionViewHolder(
        private val binding: BottomSheetSimpleSelectorItemBinding,
        private val destructiveIndex: Int,
    ) : SimpleRecyclerAdapter.ViewHolder<CharSequence>(binding.root) {
        override fun bind(data: CharSequence) {
            binding.selectComponentSheetItemIcon.visibility = View.GONE
            binding.selectComponentSheetItemDescription.visibility = View.GONE
            binding.selectComponentSheetItemTitle.apply {
                text = data
                setTextColor(
                    if (bindingAdapterPosition == destructiveIndex) {
                        ColorCompat.getColor(this, R.color.status_red_500)
                    } else {
                        ColorCompat.getThemedColor(this, R.attr.colorHeaderPrimary)
                    },
                )
            }
            binding.root.setOnClickListener {
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(RESULT_INDEX to bindingAdapterPosition),
                )
                dismiss()
            }
        }
    }

    companion object {
        internal const val REQUEST_KEY = "PROFILE_AVATAR_SELECTOR"
        internal const val RESULT_INDEX = "selected_index"
        internal const val RESULT_RECENT_AVATAR_ID = "recent_avatar_id"
        internal const val RESULT_RECENT_AVATAR_HASH = "recent_avatar_hash"
        internal const val TAG = "ProfileAvatarSelectorSheet"
        private const val ARG_LABELS = "labels"
        private const val ARG_DESTRUCTIVE_INDEX = "destructive_index"
        private const val ARG_USER_ID = "user_id"
        private const val RECENT_AVATAR_SIZE = 128

        fun show(
            fragmentManager: FragmentManager,
            labels: Array<String>,
            destructiveIndex: Int,
            userId: Long? = null,
        ) {
            ProfileAvatarSelectorSheet().apply {
                arguments = bundleOf(
                    ARG_LABELS to labels,
                    ARG_DESTRUCTIVE_INDEX to destructiveIndex,
                    ARG_USER_ID to (userId ?: -1L),
                )
            }.show(fragmentManager, TAG)
        }
    }
}

class ProfileDiscardChangesDialog : AppDialog(R.layout.profile_discard_changes_dialog) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_cancel).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_confirm).setOnClickListener {
            parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle.EMPTY)
            dismiss()
        }
    }

    companion object {
        internal const val REQUEST_KEY = "PROFILE_DISCARD_CHANGES"
        internal const val TAG = "ProfileDiscardChangesDialog"

        fun show(fragmentManager: FragmentManager) =
            ProfileDiscardChangesDialog().show(fragmentManager, TAG)
    }
}

class ProfileColorPickerDialog : AppDialog(R.layout.profile_color_picker_dialog) {
    private var selectedColor = Color.BLACK
    private var bindingHex = false
    private var restoredColor: Int? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        restoredColor = savedInstanceState?.takeIf { it.containsKey(STATE_COLOR) }?.getInt(STATE_COLOR)
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        selectedColor = argumentsOrDefault.getInt(ARG_INITIAL_COLOR, Color.BLACK)
        val picker = view.findViewById<ColorPickerView>(R.id.profile_color_picker)
        val panel = view.findViewById<ColorPanelView>(R.id.profile_color_preview)
        val hex = view.findViewById<EditText>(R.id.profile_color_hex)

        restoredColor?.let { selectedColor = it }
        picker.setAlphaSliderVisible(false)
        picker.color = selectedColor
        renderColor(panel, hex, selectedColor)
        picker.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                picker.post {
                    selectedColor = picker.color
                    renderColor(panel, hex, selectedColor)
                }
            }
            false
        }
        hex.doAfterTextChanged { value ->
            if (bindingHex) return@doAfterTextChanged
            val parsed = runCatching { Color.parseColor("#${value.toString()}") }.getOrNull() ?: return@doAfterTextChanged
            selectedColor = parsed
            picker.color = parsed
            panel.color = parsed
        }
        val target = argumentsOrDefault.getString(ARG_TARGET).orEmpty()
        view.findViewById<TextView>(R.id.profile_color_use_default).setOnClickListener {
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                bundleOf(RESULT_HAS_COLOR to false, RESULT_TARGET to target),
            )
            dismiss()
        }
        view.findViewById<Button>(R.id.profile_color_select).apply {
            backgroundTintList = ColorStateList.valueOf(ColorCompat.getThemedColor(view.context, R.attr.color_brand))
            setOnClickListener {
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(
                        RESULT_HAS_COLOR to true,
                        RESULT_COLOR to (selectedColor and 0xFFFFFF),
                        RESULT_TARGET to target,
                    ),
                )
                dismiss()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_COLOR, selectedColor)
        super.onSaveInstanceState(outState)
    }

    private fun renderColor(panel: ColorPanelView, hex: EditText, color: Int) {
        panel.color = color
        bindingHex = true
        hex.setText(String.format("%06X", color and 0xFFFFFF))
        hex.setSelection(hex.length())
        bindingHex = false
    }

    companion object {
        internal const val REQUEST_KEY = "PROFILE_COLOR_PICKER"
        internal const val RESULT_COLOR = "color"
        internal const val RESULT_HAS_COLOR = "has_color"
        internal const val RESULT_TARGET = "target"
        internal const val TAG = "ProfileColorPickerDialog"
        private const val ARG_INITIAL_COLOR = "initial_color"
        private const val ARG_TARGET = "target"
        private const val STATE_COLOR = "selected_color"

        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            initialColor: Int,
            target: String = "",
        ): ProfileColorPickerDialog =
            (fragmentManager.findFragmentByTag(TAG) as? ProfileColorPickerDialog)
                ?: ProfileColorPickerDialog().apply {
                    arguments = bundleOf(ARG_INITIAL_COLOR to initialColor, ARG_TARGET to target)
                    show(fragmentManager, TAG)
                }
    }
}
