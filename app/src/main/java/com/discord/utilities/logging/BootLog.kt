package com.discord.utilities.logging

enum class BootLogEvent(val line: String) {
    PROCESS_START("Starting MantiCord runtime"),
    PLATFORM_READY("Platform services ready"),
    NATIVE_READY("Native components loaded"),
    PERSISTENCE_READY("Persistent storage ready"),
    STORE_GRAPH_READY("Store graph created"),
    REST_READY("API transport configured"),
    GATEWAY_READY("Gateway transport configured"),
    SESSION_READY("Session state restored"),
    CACHE_RESTORE_STARTED("Restoring local caches"),
    CACHE_RESTORE_FINISHED("Local caches restored"),
    RUNTIME_READY("Application runtime ready"),
    GATEWAY_DISCOVERY("Discovering gateway endpoint"),
    GATEWAY_SOCKET_OPEN("Gateway socket opened"),
    GATEWAY_HELLO("Gateway handshake received"),
    GATEWAY_IDENTIFY("Authenticating gateway session"),
    GATEWAY_DATA_READY("Gateway initial state received"),
    CACHE_HYDRATION_STARTED("Hydrating account caches"),
    CACHE_HYDRATION_FINISHED("Account caches hydrated"),
    GATEWAY_CONNECTED("Gateway connection ready"),
    OPENING_INTERFACE("Opening application interface"),
}

object BootLog {
    internal const val MAX_LINES = 80
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val listeners = LinkedHashSet<(List<String>) -> Unit>()

    fun record(event: BootLogEvent) {
        val update: Pair<List<String>, List<(List<String>) -> Unit>> = synchronized(this) {
            if (lines.size == MAX_LINES) lines.removeFirst()
            lines.addLast(event.line)
            lines.toList() to listeners.toList()
        }
        update.second.forEach { listener -> runCatching { listener(update.first) } }
    }

    fun observe(listener: (List<String>) -> Unit): () -> Unit {
        val snapshot = synchronized(this) {
            listeners += listener
            lines.toList()
        }
        runCatching { listener(snapshot) }
        return { synchronized(this) { listeners -= listener } }
    }

    internal fun resetForTest() = synchronized(this) {
        lines.clear()
        listeners.clear()
    }
}
