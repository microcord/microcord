package com.discord.utilities.captcha

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import com.discord.app.AppLog
import com.discord.utilities.lifecycle.ActivityProvider
import com.discord.widgets.captcha.WidgetCaptchaBottomSheet
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Shows the challenge over the foreground screen and blocks the calling request until it is
 * answered.
 *
 * Challenges are serialised, since several requests can be challenged at once and solving one
 * usually unblocks the rest. [TIMEOUT_MS] bounds the whole call including time spent queued.
 */
class ActivityCaptchaSolver(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val activityProvider: () -> Activity? = { ActivityProvider.getActivity() },
) : CaptchaSolver {

    private val gate = Semaphore(1, /* fair = */ true)

    override fun solve(challenge: CaptchaErrorBody): CaptchaSolution {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ActivityCaptchaSolver.solve blocks and must not be called on the main thread"
        }
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)

        if (!tryAcquire(deadlineNanos)) {
            throw CaptchaCancellationException(CaptchaCancellationException.REASON_TIMED_OUT)
        }
        try {
            return await(challenge, deadlineNanos)
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

    private fun await(challenge: CaptchaErrorBody, deadlineNanos: Long): CaptchaSolution {
        val challengeId = UUID.randomUUID().toString()
        val outcomes = ArrayBlockingQueue<Outcome>(1)

        if (!mainHandler.post { showPrompt(challengeId, challenge, outcomes) }) {
            throw CaptchaCancellationException(CaptchaCancellationException.REASON_NO_HOST)
        }

        val outcome = try {
            outcomes.poll(remaining(deadlineNanos), TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            abandon(challengeId)
            throw CaptchaCancellationException(CaptchaCancellationException.REASON_DECLINED)
        }

        return when (outcome) {
            is Outcome.Solved -> outcome.solution
            is Outcome.Failed -> throw CaptchaCancellationException(outcome.reason)
            null -> {
                abandon(challengeId)
                throw CaptchaCancellationException(CaptchaCancellationException.REASON_TIMED_OUT)
            }
        }
    }

    // Nothing may escape onto the main thread: this runs for a background request, and a fragment
    // manager in a bad state has to become a declined challenge rather than a crash.
    private fun showPrompt(
        challengeId: String,
        challenge: CaptchaErrorBody,
        outcomes: ArrayBlockingQueue<Outcome>,
    ) {
        runCatching {
            val activity = activityProvider() as? FragmentActivity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                outcomes.offer(Outcome.Failed(CaptchaCancellationException.REASON_NO_HOST))
                return@runCatching
            }
            WidgetCaptchaBottomSheet.show(
                fragmentManager = activity.supportFragmentManager,
                challengeId = challengeId,
                challenge = challenge,
            ) { solution ->
                outcomes.offer(
                    if (solution != null) {
                        Outcome.Solved(solution)
                    } else {
                        Outcome.Failed(CaptchaCancellationException.REASON_DECLINED)
                    },
                )
            }
        }.onFailure { failure ->
            AppLog.w("Captcha", "could not show the challenge prompt", failure)
            outcomes.offer(Outcome.Failed(CaptchaCancellationException.REASON_NO_HOST))
        }
    }

    private fun abandon(challengeId: String) {
        mainHandler.post { runCatching { WidgetCaptchaBottomSheet.cancel(challengeId) } }
    }

    private fun remaining(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private sealed class Outcome {
        class Solved(val solution: CaptchaSolution) : Outcome()
        class Failed(val reason: String) : Outcome()
    }

    companion object {
        const val TIMEOUT_MS = 5L * 60L * 1000L
    }
}
