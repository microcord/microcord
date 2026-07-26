package com.discord.stores

import com.discord.app.AppLog
import com.discord.models.domain.ModelGift
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Every gift code the session has seen, and where each one is in the resolve → redeem → owned
 * lifecycle.
 *
 * The map is keyed by two different kinds of string and that is deliberate:
 *
 * - a real gift **code**, for a link the user opened or a code the client generated, and
 * - a synthetic `"<skuId>:<planId>"` **combo id** ([makeComboId]), used as the slot for
 *   "my gifts for this SKU" while that request is in flight.
 *
 * Once [fetchMyGiftsForSku] or [generateGiftCode] succeeds the combo slot is removed and the real
 * codes take its place, so the combo id only ever holds [GiftState.Loading],
 * [GiftState.LoadFailed] or [GiftState.Invalid].
 *
 * This store is intentionally **not** a [StoreV2]: it publishes through a [BehaviorSubject] rather
 * than the observation deck, because the gift chat row and the redeem sheet both want the value
 * immediately on subscribe rather than on the next dispatch boundary.
 */
class StoreGifting(
    val dispatcher: Dispatcher,
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    /**
     * Called after a successful [acceptGift] so the newly granted SKU shows up in the library.
     * Wired to `StoreLibrary::fetchApplications` by [StoreStream]; inert by default so the store is
     * testable on its own.
     */
    private val onGiftRedeemed: () -> Unit = {},
    /**
     * Reference calls `AnalyticsTracker.giftResolved` / `giftResolvedFailed` here.
     *
     * Only the failure event exists on the reconstructed [AnalyticsTracker]; the success side is
     * injectable and defaults to a no-op so this store does not have to grow an analytics schema
     * that the analytics slice owns. Point it at `AnalyticsTracker.giftResolved` when that lands.
     */
    private val onGiftResolvedAnalytics: (ModelGift) -> Unit = {},
) : Store() {

    /** A [GiftState] that has a gift attached, so a row can render the product while acting on it. */
    interface HasGift {
        val gift: ModelGift
    }

    sealed class GiftState {
        object Loading : GiftState()

        /** The request never reached the server. Retried the next time the code is requested. */
        object LoadFailed : GiftState()

        /** The server rejected the code. Terminal — never retried. */
        object Invalid : GiftState()

        data class Resolved(override val gift: ModelGift) : GiftState(), HasGift
        data class Redeeming(override val gift: ModelGift) : GiftState(), HasGift
        data class Revoking(override val gift: ModelGift) : GiftState(), HasGift

        /**
         * Redemption failed. [canRetry] is true only for transport-level failures; a rejection
         * carrying a Discord error code is terminal and reports it in [errorCode].
         */
        data class RedeemedFailed(
            override val gift: ModelGift,
            val canRetry: Boolean,
            val errorCode: Int?,
        ) : GiftState(), HasGift
    }

    var knownGifts: HashMap<String, GiftState> = HashMap()
        private set

    private val knownGiftsSubject =
        BehaviorSubject.create<Map<String, GiftState>>(emptyMap<String, GiftState>())

    /**
     * Requests [giftCode] and then tracks it.
     *
     * Emits [GiftState.Loading] for a code nothing has asked about yet, so a chat row can bind
     * before the dispatcher has run the fetch.
     */
    fun requestGift(giftCode: String): Observable<GiftState> {
        dispatcher.schedule { fetchGift(giftCode) }
        return knownGiftsSubject
            .map { gifts -> gifts[giftCode] ?: GiftState.Loading }
            .distinctUntilChanged()
    }

    /** Every resolved gift whose sender is [userId] — the "gifts you sent" list. */
    fun getMyResolvedGifts(userId: Long): Observable<List<ModelGift>> =
        knownGiftsSubject.map { giftMap ->
            giftMap.values
                .filterIsInstance<GiftState.Resolved>()
                .filter { it.gift.user?.id == userId }
                .map(GiftState.Resolved::gift)
        }

    /**
     * Re-requests a code that is absent, or that previously failed at the transport level.
     *
     * A code already [GiftState.Resolved] is only re-requested when its gift is *incomplete* — the
     * chat-row resolve returns a gift without its store listing, and the redeem sheet needs the
     * listing to show what is being given.
     */
    @StoreThread
    internal fun fetchGift(giftCode: String) {
        val existing = knownGifts[giftCode]
        if (knownGifts.containsKey(giftCode) && existing !is GiftState.LoadFailed) {
            if (existing !is GiftState.Resolved) return
            if (existing.gift.isComplete()) return
        }
        setGifts(giftCode, GiftState.Loading)
        val api = restApi() ?: return
        api.resolveGiftCode(giftCode, withApplication = true, withSubscription = true)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGifting::class.java,
                errorHandler = { error ->
                    dispatcher.schedule {
                        AnalyticsTracker.giftResolvedFailed(giftCode)
                        setGifts(giftCode, error.toFailureState())
                    }
                },
            ) { gift ->
                dispatcher.schedule {
                    onGiftResolvedAnalytics(gift)
                    setGifts(giftCode, GiftState.Resolved(gift))
                }
            }
    }

    /** Loads the codes the user already generated for [skuId] (+ optional [planId]). */
    fun fetchMyGiftsForSku(skuId: Long, planId: Long?) {
        val comboId = makeComboId(skuId, planId)
        if (knownGifts.containsKey(comboId) && knownGifts[comboId] !is GiftState.LoadFailed) return
        dispatcher.schedule { setGifts(comboId, GiftState.Loading) }
        val api = restApi() ?: return
        api.resolveSkuIdGift(skuId, planId)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGifting::class.java,
                errorHandler = { error ->
                    dispatcher.schedule {
                        if (error.type != RestApiError.Type.DISCORD_REQUEST_ERROR &&
                            error.type != RestApiError.Type.NETWORK
                        ) {
                            AppLog.e("Fetching Gift Error", Exception(error.type.toString()))
                        }
                        setGifts(comboId, error.toFailureState())
                    }
                },
            ) { gifts ->
                dispatcher.schedule {
                    removeGiftCode(comboId)
                    clearGiftsForSku(skuId, planId)
                    gifts.forEach { setGifts(it.code, GiftState.Resolved(it)) }
                }
            }
    }

    /**
     * Creates a new gift code for [skuId].
     *
     * Note the reference quirk, reproduced: the error path classifies the failure and then
     * unconditionally overwrites the slot with [GiftState.LoadFailed], so a rejected generate never
     * shows as [GiftState.Invalid] even though the branch computes it. Callers distinguish the two
     * through [onError] instead.
     */
    @JvmOverloads
    fun generateGiftCode(
        skuId: Long,
        planId: Long? = null,
        onSuccess: ((ModelGift) -> Unit)? = null,
        onError: ((RestApiError) -> Unit)? = null,
    ) {
        val comboId = makeComboId(skuId, planId)
        if (knownGifts[comboId] is GiftState.Loading) return
        dispatcher.schedule { setGifts(comboId, GiftState.Loading) }
        val api = restApi() ?: return
        api.generateGiftCode(RestAPIParams.GenerateGiftCode(skuId, planId))
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGifting::class.java,
                errorHandler = { error ->
                    dispatcher.schedule {
                        if (error.type != RestApiError.Type.DISCORD_REQUEST_ERROR &&
                            error.type != RestApiError.Type.NETWORK
                        ) {
                            AppLog.e("Generate Gift Code Error", Exception(error.type.toString()))
                        }
                        setGifts(comboId, error.toFailureState())
                        setGifts(comboId, GiftState.LoadFailed)
                    }
                    onError?.invoke(error)
                },
            ) { newGift ->
                dispatcher.schedule {
                    removeGiftCode(comboId)
                    setGifts(newGift.code, GiftState.Resolved(newGift))
                }
                onSuccess?.invoke(newGift)
            }
    }

    /**
     * Redeems [gift].
     *
     * Two server rejections are not really failures and are folded back into
     * [GiftState.Resolved] with the gift corrected, so the sheet says "already used" / "already
     * owned" instead of showing a retry button:
     *
     * - [GIFTING_ALREADY_USED_ERROR_CODE] — the code is spent, so `uses` is set to `maxUses`.
     * - [GIFTING_ALREADY_OWNED_ERROR_CODE] — this account already redeemed it, so `redeemed` is set.
     */
    fun acceptGift(gift: ModelGift) = dispatcher.schedule {
        setGifts(gift.code, GiftState.Redeeming(gift))
        val api = restApi()
        if (api == null) {
            setGifts(gift.code, GiftState.RedeemedFailed(gift, canRetry = true, errorCode = null))
            return@schedule
        }
        api.acceptGift(gift.code)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGifting::class.java,
                errorHandler = { error ->
                    dispatcher.schedule {
                        if (error.type != RestApiError.Type.DISCORD_BAD_REQUEST) {
                            setGifts(
                                gift.code,
                                GiftState.RedeemedFailed(gift, canRetry = true, errorCode = null),
                            )
                            return@schedule
                        }
                        error.showErrorToasts = false
                        when (error.code) {
                            GIFTING_ALREADY_USED_ERROR_CODE -> setGifts(
                                gift.code,
                                GiftState.Resolved(gift.copy(uses = gift.maxUses)),
                            )

                            GIFTING_ALREADY_OWNED_ERROR_CODE -> setGifts(
                                gift.code,
                                GiftState.Resolved(gift.copy(redeemed = true)),
                            )

                            else -> setGifts(
                                gift.code,
                                GiftState.RedeemedFailed(gift, canRetry = false, errorCode = error.code),
                            )
                        }
                    }
                },
            ) {
                dispatcher.schedule {
                    setGifts(gift.code, GiftState.Resolved(gift.copy(redeemed = true)))
                }
                onGiftRedeemed()
            }
    }

    /**
     * Revokes a code the user generated.
     *
     * A failure puts the gift back to [GiftState.Resolved] rather than to an error state: the code
     * is still valid, so the row should keep offering "revoke".
     */
    fun revokeGiftCode(gift: ModelGift) {
        if (knownGifts.containsKey(gift.code) && knownGifts[gift.code] is GiftState.Revoking) return
        dispatcher.schedule { setGifts(gift.code, GiftState.Revoking(gift)) }
        val api = restApi() ?: return
        api.revokeGiftCode(gift.code)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGifting::class.java,
                errorHandler = { error ->
                    dispatcher.schedule {
                        AppLog.e("Revoking Gift Error", Exception(error.type.toString()))
                        setGifts(gift.code, GiftState.Resolved(gift))
                    }
                },
            ) { dispatcher.schedule { removeGiftCode(gift.code) } }
    }

    @StoreThread
    fun handlePreLogout() {
        knownGifts.clear()
        knownGiftsSubject.onNext(HashMap(knownGifts))
    }

    @StoreThread
    internal fun setGifts(code: String, giftState: GiftState) {
        knownGifts[code] = giftState
        knownGiftsSubject.onNext(HashMap(knownGifts))
    }

    @StoreThread
    private fun removeGiftCode(code: String) {
        knownGifts.remove(code)
        knownGiftsSubject.onNext(HashMap(knownGifts))
    }

    /**
     * Drops every resolved gift for [skuId] before the freshly fetched ones are inserted, so a code
     * revoked on another client disappears here too.
     *
     * When [subscriptionPlanId] is given, only gifts on that plan are dropped; entries that are not
     * [GiftState.Resolved] are always kept, because they may belong to an unrelated in-flight code.
     */
    @StoreThread
    private fun clearGiftsForSku(skuId: Long, subscriptionPlanId: Long?) {
        knownGifts = HashMap(
            knownGifts.filter { (_, state) ->
                if (state !is GiftState.Resolved) return@filter true
                if (state.gift.skuId != skuId) return@filter true
                subscriptionPlanId != null && state.gift.subscriptionPlanId != subscriptionPlanId
            },
        )
        knownGiftsSubject.onNext(HashMap(knownGifts))
    }

    /**
     * `DISCORD_REQUEST_ERROR` means the server said the code is bad — terminal, and the toast is
     * suppressed because the row renders the failure itself. Everything else, including
     * `NETWORK`, is retryable.
     */
    private fun RestApiError.toFailureState(): GiftState =
        if (type == RestApiError.Type.DISCORD_REQUEST_ERROR) {
            showErrorToasts = false
            GiftState.Invalid
        } else {
            GiftState.LoadFailed
        }

    companion object {
        /** `RestAPIAbortCodes.GIFTING_ALREADY_USED_ERROR_CODE`. */
        const val GIFTING_ALREADY_USED_ERROR_CODE = 50050

        /** `RestAPIAbortCodes.GIFTING_ALREADY_OWNED_ERROR_CODE`. */
        const val GIFTING_ALREADY_OWNED_ERROR_CODE = 50051

        fun makeComboId(skuId: Long, planId: Long?): String = "$skuId:$planId"
    }
}
