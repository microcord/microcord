package com.discord.widgets.guilds.invite

import com.discord.app.AppComponent
import com.discord.app.AppFragment
import com.discord.models.domain.ModelInvite
import com.discord.stores.StoreInviteSettings
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.BehaviorSubject
import java.io.Closeable

/**
 * Owns one in-flight `POST /channels/{id}/invites` and publishes its lifecycle.
 *
 * The generator is deliberately *not* a store: an invite is generated per screen, and the last
 * generated invite must die with the screen so a second visit does not show a stale link. The
 * subject therefore starts on [GenerationState.INIT] and is completed by [close].
 *
 * A failure keeps [InviteGenerationState.lastGeneratedInvite] — the reference `copy`es only the
 * state field — so the screen can fall back to the previous link (or to the guild's vanity URL,
 * see `WidgetInviteModel.create`) rather than blanking out.
 */
class InviteGenerator(
    private val inviteSettings: StoreInviteSettings = InviteSettingsSource.store,
) : Closeable {

    /**
     * Ordinals taken from the class's static initialiser, not from the alphabetical order a
     * decompiler prints: `INIT(0), GENERATING(1), SUCCESS(2), FAILURE(3)`.
     */
    enum class GenerationState {
        INIT,
        GENERATING,
        SUCCESS,
        FAILURE,
    }

    data class InviteGenerationState(
        val lastGeneratedInvite: ModelInvite? = null,
        val state: GenerationState = GenerationState.INIT,
    )

    private val generationStateSubject: BehaviorSubject<InviteGenerationState> =
        BehaviorSubject.create(InviteGenerationState())

    val generationState: Observable<InviteGenerationState> get() = generationStateSubject

    fun generate(fragment: AppFragment, channelId: Long) {
        moveTo(GenerationState.GENERATING)
        inviteSettings.generateInvite(channelId)
            .ui(fragment)
            .appSubscribe(
                errorClass = InviteGenerator::class.java,
                context = fragment.context,
                errorHandler = { moveTo(GenerationState.FAILURE) },
                onNext = ::handleGeneratedInvite,
            )
    }

    /**
     * Generates an invite for [channelId], binding the request to [appComponent] so it terminates
     * with the view model rather than with the view.
     */
    fun generateForAppComponent(appComponent: AppComponent, channelId: Long) {
        moveTo(GenerationState.GENERATING)
        inviteSettings.generateInvite(channelId)
            .computationBuffered()
            .ui(appComponent)
            .appSubscribe(
                InviteGenerator::class.java,
                errorHandler = { moveTo(GenerationState.FAILURE) },
            ) { invite -> handleGeneratedInvite(invite) }
    }

    override fun close() = generationStateSubject.onCompleted()

    private fun handleGeneratedInvite(invite: ModelInvite) =
        generationStateSubject.onNext(InviteGenerationState(invite, GenerationState.SUCCESS))

    private fun moveTo(state: GenerationState) {
        val current = generationStateSubject.value ?: InviteGenerationState()
        generationStateSubject.onNext(current.copy(state = state))
    }
}
