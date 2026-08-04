package com.discord.utilities.mfa

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import com.discord.R
import com.discord.api.auth.mfa.MfaChallenge
import com.discord.api.auth.mfa.MfaMethodType
import com.discord.app.AppLog
import com.discord.utilities.lifecycle.ActivityProvider
import com.discord.widgets.mfa.WidgetMfaBottomSheet
import com.discord.widgets.notice.WidgetNoticeDialog
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Prompts over the foreground screen and blocks the calling request until verification completes.
 *
 * Challenges are serialised, since several requests can be challenged at once and one elevation
 * usually satisfies the rest. [TIMEOUT_MS] bounds the whole call including time spent queued.
 */
class ActivityMfaSolver(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val activityProvider: () -> Activity? = { ActivityProvider.getActivity() },
) : MfaSolver {

    private val gate = Semaphore(1, /* fair = */ true)

    override fun solve(challenge: MfaChallenge): MfaElevation {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ActivityMfaSolver.solve blocks and must not be called on the main thread"
        }
        val offered = challenge.methods.filter { it.type in SUPPORTED }
        if (offered.isEmpty()) {
            explainUnsupported()
            throw MfaCancellationException(MfaCancellationException.REASON_UNSUPPORTED)
        }

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        if (!tryAcquire(deadlineNanos)) {
            throw MfaCancellationException(MfaCancellationException.REASON_TIMED_OUT)
        }
        try {
            return await(challenge.copy(methods = offered), deadlineNanos)
        } finally {
            gate.release()
        }
    }

    private fun tryAcquire(deadlineNanos: Long): Boolean = try {
        gate.tryAcquire(remaining(deadlineNanos), TimeUnit.NANOSECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun await(challenge: MfaChallenge, deadlineNanos: Long): MfaElevation {
        val challengeId = UUID.randomUUID().toString()
        val outcomes = ArrayBlockingQueue<Outcome>(1)

        if (!mainHandler.post { showPrompt(challengeId, challenge, outcomes) }) {
            throw MfaCancellationException(MfaCancellationException.REASON_NO_HOST)
        }

        val outcome = try {
            outcomes.poll(remaining(deadlineNanos), TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            abandon(challengeId)
            throw MfaCancellationException(MfaCancellationException.REASON_DECLINED)
        }

        return when (outcome) {
            is Outcome.Verified -> outcome.elevation
            is Outcome.Failed -> throw MfaCancellationException(outcome.reason)
            null -> {
                abandon(challengeId)
                throw MfaCancellationException(MfaCancellationException.REASON_TIMED_OUT)
            }
        }
    }

    // Nothing may escape onto the main thread: this runs for a background request, and a fragment
    // manager in a bad state has to become a declined challenge rather than a crash.
    private fun showPrompt(
        challengeId: String,
        challenge: MfaChallenge,
        outcomes: ArrayBlockingQueue<Outcome>,
    ) {
        runCatching {
            val activity = activityProvider() as? FragmentActivity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                outcomes.offer(Outcome.Failed(MfaCancellationException.REASON_NO_HOST))
                return@runCatching
            }
            WidgetMfaBottomSheet.show(
                fragmentManager = activity.supportFragmentManager,
                challengeId = challengeId,
                challenge = challenge,
            ) { elevation ->
                outcomes.offer(
                    if (elevation != null) {
                        Outcome.Verified(elevation)
                    } else {
                        Outcome.Failed(MfaCancellationException.REASON_DECLINED)
                    },
                )
            }
        }.onFailure { failure ->
            AppLog.w("MFA", "could not show the verification prompt", failure)
            outcomes.offer(Outcome.Failed(MfaCancellationException.REASON_NO_HOST))
        }
    }

    /** Passkey-only accounts cannot be verified here; say so rather than failing silently. */
    private fun explainUnsupported() {
        mainHandler.post {
            runCatching {
                val activity = activityProvider() as? FragmentActivity ?: return@runCatching
                if (activity.isFinishing || activity.isDestroyed) return@runCatching
                WidgetNoticeDialog.Builder(activity)
                    .setTitle(R.string.two_fa_passkey_unsupported_title)
                    .setMessage(R.string.two_fa_passkey_unsupported_body)
                    .setPositiveButton(R.string.okay)
                    .show(activity.supportFragmentManager)
            }
        }
    }

    private fun abandon(challengeId: String) {
        mainHandler.post { runCatching { WidgetMfaBottomSheet.cancel(challengeId) } }
    }

    private fun remaining(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private sealed class Outcome {
        class Verified(val elevation: MfaElevation) : Outcome()
        class Failed(val reason: String) : Outcome()
    }

    companion object {
        const val TIMEOUT_MS = 5L * 60L * 1000L

        /** WebAuthn is absent: it needs hardware attestation bound to the retail package name. */
        val SUPPORTED = setOf(
            MfaMethodType.TOTP,
            MfaMethodType.BACKUP,
            MfaMethodType.SMS,
            MfaMethodType.PASSWORD,
        )
    }
}
