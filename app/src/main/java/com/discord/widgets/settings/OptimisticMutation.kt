package com.discord.widgets.settings

internal class OptimisticMutation<T>(
    initial: T,
    private val render: (T) -> Unit,
    private val showError: (Throwable) -> Unit,
) {
    var state: T = initial
        private set
    private var revision = 0

    fun accept(value: T) {
        revision++
        state = value
        render(value)
    }

    fun submit(value: T, request: (T, (Result<Unit>) -> Unit) -> Unit) {
        val previous = state
        val attempt = ++revision
        state = value
        render(value)
        try {
            request(value) { result ->
                if (attempt != revision || result.isSuccess) return@request
                state = previous
                render(previous)
                showError(result.exceptionOrNull() ?: IllegalStateException("Notification setting update failed"))
            }
        } catch (error: Throwable) {
            if (attempt == revision) {
                state = previous
                render(previous)
                showError(error)
            }
        }
    }
}
