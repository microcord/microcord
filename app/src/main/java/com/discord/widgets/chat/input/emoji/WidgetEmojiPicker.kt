package com.discord.widgets.chat.input.emoji

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.emoji.GuildEmoji
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.stores.StoreStream
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.JsonParser
import java.util.Locale

class WidgetEmojiPicker : AppFragment(R.layout.widget_emoji_picker) {
    interface Host {
        fun onEmojiPicked(text: String)
        fun onEmojiBackspace()
    }

    private lateinit var emojiAdapter: EmojiAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private var categories: List<EmojiCategory> = emptyList()
    private var selectedCategory = "people"
    internal var onPicked: ((PickerEmoji) -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedCategory = savedInstanceState?.getString(STATE_CATEGORY) ?: selectedCategory
        categories = EmojiCatalog.load(requireContext()) + customCategory()
        emojiAdapter = EmojiAdapter(::pick)
        categoryAdapter = CategoryAdapter(categories.map(EmojiCategory::key), ::selectCategory)

        view.findViewById<RecyclerView>(R.id.chat_input_emoji_picker_recycler).apply {
            layoutManager = GridLayoutManager(context, 8)
            adapter = emojiAdapter
        }
        view.findViewById<RecyclerView>(R.id.emoji_picker_category_recycler).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = categoryAdapter
        }
        view.findViewById<View>(R.id.emoji_inline_search_button).visibility = View.GONE
        view.findViewById<TextInputEditText>(R.id.emoji_search_input).apply {
            visibility = View.VISIBLE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter(s?.toString().orEmpty())
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        view.findViewById<View>(R.id.emoji_search_clear).setOnClickListener {
            view.findViewById<TextInputEditText>(R.id.emoji_search_input).text?.clear()
        }
        view.findViewById<View>(R.id.emoji_picker_backspace_icon).setOnClickListener { host()?.onEmojiBackspace() }
        view.findViewById<View>(R.id.emoji_picker_unicode_emoji_shortcut_button).visibility = View.GONE
        selectCategory(selectedCategory)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CATEGORY, selectedCategory)
        super.onSaveInstanceState(outState)
    }

    private fun host(): Host? = parentFragment?.parentFragment as? Host

    private fun pick(emoji: PickerEmoji) {
        onPicked?.invoke(emoji) ?: host()?.onEmojiPicked(emoji.inputText + " ")
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun selectCategory(key: String) {
        selectedCategory = categories.firstOrNull { it.key == key }?.key ?: categories.firstOrNull()?.key.orEmpty()
        categoryAdapter.selected = selectedCategory
        emojiAdapter.items = categories.firstOrNull { it.key == selectedCategory }?.items.orEmpty()
        showEmpty(emojiAdapter.items.isEmpty())
    }

    private fun filter(query: String) {
        val normalized = query.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) {
            selectCategory(selectedCategory)
            return
        }
        emojiAdapter.items = categories.flatMap(EmojiCategory::items).filter {
            it.names.any { name -> normalized in name.lowercase(Locale.ROOT) }
        }
        showEmpty(emojiAdapter.items.isEmpty())
    }

    private fun showEmpty(empty: Boolean) {
        view?.findViewById<com.discord.app.AppViewFlipper>(R.id.chat_input_emoji_picker_view_flipper)
            ?.displayedChild = if (empty) 1 else 0
    }

    private fun customCategory(): List<EmojiCategory> {
        val items = StoreStream.getGuilds().getGuilds().values
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .flatMap { guild -> guild.emojis.filter { it.available != false }.map(PickerEmoji::Custom) }
            .distinctBy { (it as PickerEmoji.Custom).emoji.id }
        return if (items.isEmpty()) emptyList() else listOf(EmojiCategory("custom", items))
    }

    internal data class EmojiCategory(val key: String, val items: List<PickerEmoji>)

    internal sealed class PickerEmoji {
        abstract val names: List<String>
        abstract val inputText: String
        abstract fun imageUri(context: Context): String

        data class Unicode(val emoji: ModelEmojiUnicode) : PickerEmoji() {
            override val names get() = emoji.names
            override val inputText get() = emoji.surrogates
            override fun imageUri(context: Context) = emoji.getImageUri(true, 64, context)
        }

        data class Custom(val emoji: GuildEmoji) : PickerEmoji() {
            override val names get() = listOf(emoji.name)
            override val inputText get() = "<${if (emoji.animated) "a" else ""}:${emoji.name}:${emoji.id}>"
            override fun imageUri(context: Context) =
                "https://cdn.discordapp.com/emojis/${emoji.id}.${if (emoji.animated) "gif" else "png"}?size=64&quality=lossless"
        }
    }

    internal object EmojiCatalog {
        fun load(context: Context): List<EmojiCategory> = runCatching {
            context.assets.open("data/emojis.json").bufferedReader().use { parse(it.readText()) }
        }.getOrDefault(emptyList())

        fun parse(json: String): List<EmojiCategory> = JsonParser.parseString(json).asJsonObject.entrySet().map { (key, values) ->
            EmojiCategory(key, values.asJsonArray.map { value ->
                val item = value.asJsonObject
                PickerEmoji.Unicode(ModelEmojiUnicode(
                    names = item.getAsJsonArray("names").map { it.asString },
                    surrogates = item.get("surrogates").asString,
                ))
            })
        }
    }

    private class EmojiAdapter(private val onClick: (PickerEmoji) -> Unit) : RecyclerView.Adapter<EmojiHolder>() {
        var items: List<PickerEmoji> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EmojiHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.emoji_picker_emoji_item, parent, false) as SimpleDraweeView,
        )

        override fun onBindViewHolder(holder: EmojiHolder, position: Int) = holder.bind(items[position], onClick)
        override fun getItemCount() = items.size
    }

    private class EmojiHolder(private val image: SimpleDraweeView) : RecyclerView.ViewHolder(image) {
        fun bind(item: PickerEmoji, onClick: (PickerEmoji) -> Unit) {
            image.setImageURI(item.imageUri(image.context))
            image.contentDescription = item.names.firstOrNull().orEmpty()
            image.setOnClickListener { onClick(item) }
        }
    }

    private class CategoryAdapter(
        private val categories: List<String>,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<CategoryHolder>() {
        var selected: String = ""
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryHolder {
            val size = (48 * parent.resources.displayMetrics.density).toInt()
            return CategoryHolder(ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(size, ViewGroup.LayoutParams.MATCH_PARENT)
                setPadding(size / 4, size / 4, size / 4, size / 4)
            })
        }

        override fun onBindViewHolder(holder: CategoryHolder, position: Int) =
            holder.bind(categories[position], categories[position] == selected, onClick)

        override fun getItemCount() = categories.size
    }

    private class CategoryHolder(private val image: ImageView) : RecyclerView.ViewHolder(image) {
        fun bind(category: String, selected: Boolean, onClick: (String) -> Unit) {
            image.setImageResource(categoryIcon(category))
            image.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(
                image.context,
                if (selected) R.color.brand_new_500 else R.color.primary_dark_300,
            ))
            image.contentDescription = category.replaceFirstChar { it.titlecase(Locale.ROOT) }
            image.setOnClickListener { onClick(category) }
        }
    }

    companion object {
        private const val STATE_CATEGORY = "emoji_category"

        private fun categoryIcon(category: String) = when (category) {
            "people" -> R.drawable.ic_emoji_picker_category_people
            "nature" -> R.drawable.ic_emoji_picker_category_nature
            "food" -> R.drawable.ic_emoji_picker_category_food
            "activity" -> R.drawable.ic_emoji_picker_category_activity
            "travel" -> R.drawable.ic_emoji_picker_category_travel
            "objects" -> R.drawable.ic_emoji_picker_category_objects
            "symbols" -> R.drawable.ic_emoji_picker_category_symbols
            "flags" -> R.drawable.ic_emoji_picker_category_flags
            else -> R.drawable.ic_emoji_picker_category_custom
        }
    }
}
