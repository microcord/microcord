package com.discord.widgets.chat.input.expression

import com.discord.utilities.persister.Persister
import rx.Observable

/**
 * Which expression-tray tab is selected, and the only way to change it.
 *
 * Declared here in the feature package, as `StickerStore` is, so that when
 * `com.discord.stores.StoreExpressionPickerNavigation` is reconstructed it implements this
 * interface and [ExpressionPickerNavigationStores.navigation] resolves to the real store with no
 * change to any caller. The reference's public surface is exactly these two methods.
 *
 * The selection is *store* state rather than tray state because it outlives the tray: the reference
 * persists it under `CACHE_KEY_SELECTED_EXPRESSION_TRAY_TAB` and restores it on next launch.
 */
interface ExpressionPickerNavigationStore {

    /** Distinct-until-changed; replays the current tab to every new subscriber. */
    fun observeSelectedTab(): Observable<ExpressionTrayTab>

    fun onSelectTab(expressionTrayTab: ExpressionTrayTab)
}

/** The single resolution point for [ExpressionPickerNavigationStore]. */
object ExpressionPickerNavigationStores {
    /** Replace with `StoreStream.getExpressionPickerNavigation()` when that store lands. */
    val navigation: ExpressionPickerNavigationStore by lazy { PersistedExpressionPickerNavigationStore() }
}

/**
 * Stand-in for `com.discord.stores.StoreExpressionPickerNavigation`.
 *
 * Keeps the reference's persistence — same cache key, same `EMOJI` default, so an existing cache
 * file is read identically — but not its store machinery: no `Dispatcher` confinement and no
 * `ObservationDeck`, because a single enum needs neither. `Persister.getObservable()` already
 * provides the replaying, mutation-driven stream that `connectRx` would.
 */
internal class PersistedExpressionPickerNavigationStore(
    private val selectedTabPersister: Persister<ExpressionTrayTab> =
        Persister(CACHE_KEY_SELECTED_EXPRESSION_TRAY_TAB, ExpressionTrayTab.EMOJI),
) : ExpressionPickerNavigationStore {

    override fun observeSelectedTab(): Observable<ExpressionTrayTab> =
        selectedTabPersister.getObservable().distinctUntilChanged()

    override fun onSelectTab(expressionTrayTab: ExpressionTrayTab) {
        selectedTabPersister.set(expressionTrayTab, persist = true)
    }

    private companion object {
        const val CACHE_KEY_SELECTED_EXPRESSION_TRAY_TAB = "CACHE_KEY_SELECTED_EXPRESSION_TRAY_TAB"
    }
}
