package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.widgets.chat.input.expression.ExpressionPickerNavigationStore
import com.discord.widgets.chat.input.expression.ExpressionTrayTab
import rx.Observable

class StoreExpressionPickerNavigation(
    private val observationDeck: ObservationDeck,
    private val dispatcher: Dispatcher,
    private val selectedTabPersister: Persister<ExpressionTrayTab> =
        Persister(CACHE_KEY_SELECTED_EXPRESSION_TRAY_TAB, ExpressionTrayTab.EMOJI),
) : StoreV2(), ExpressionPickerNavigationStore {
    private var selectedTab = selectedTabPersister.get()

    override fun observeSelectedTab(): Observable<ExpressionTrayTab> =
        observationDeck.connectRx(this, updateOnConnect = false) { selectedTab }.distinctUntilChanged()

    override fun onSelectTab(expressionTrayTab: ExpressionTrayTab) = dispatcher.schedule {
        selectedTab = expressionTrayTab
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        selectedTabPersister.set(selectedTab, persist = true)
    }

    companion object {
        const val CACHE_KEY_SELECTED_EXPRESSION_TRAY_TAB = "CACHE_KEY_SELECTED_EXPRESSION_TRAY_TAB"
    }
}
