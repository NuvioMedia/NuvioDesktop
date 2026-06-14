package com.nuvio.app.desktop

internal object DesktopPlayerRegistry {

    internal data class Entry(
        val stop: () -> Unit,
        val close: () -> Unit,
    )

    private val activePlayers = linkedMapOf<String, Entry>()
    private val closeThreads = mutableListOf<Thread>()

    @Synchronized
    fun register(id: String, stop: () -> Unit, close: () -> Unit) {
        activePlayers[id] = Entry(stop, close)
        DesktopRuntimeLog.info("playerRegistry register id=$id active=${activePlayers.size} ids=${activePlayers.keys}")
    }

    @Synchronized
    fun unregister(id: String) {
        activePlayers.remove(id)
        DesktopRuntimeLog.info("playerRegistry unregister id=$id active=${activePlayers.size} ids=${activePlayers.keys}")
    }

    @Synchronized
    fun releaseAll(reason: String) {
        if (activePlayers.isEmpty()) {
            DesktopRuntimeLog.info("playerRegistry releaseAll reason=$reason active=0")
            return
        }
        DesktopRuntimeLog.info("playerRegistry releaseAll start reason=$reason active=${activePlayers.size} ids=${activePlayers.keys}")
        val snapshot = activePlayers.toMap()
        snapshot.forEach { (id, entry) ->
            runCatching { entry.stop() }
                .onSuccess { DesktopRuntimeLog.info("playerRegistry stop success id=$id reason=$reason") }
                .onFailure { DesktopRuntimeLog.error("playerRegistry stop failed id=$id reason=$reason", it) }
        }
        DesktopRuntimeLog.info("playerRegistry releaseAll done reason=$reason")
    }

    /**
     * Trigger the native close path on every registered player. Compose's
     * `exitApplication` does not always dispose the player surface before the
     * JVM begins shutting down, so the on-dispose closeNative chain never runs
     * for a Windows-X close. This entry point is what makes the croix actually
     * tear MPV down. It is safe to call alongside [releaseAll]: registered
     * player backends are responsible for making their close action idempotent.
     */
    @Synchronized
    fun closeAll(reason: String) {
        if (activePlayers.isEmpty()) {
            DesktopRuntimeLog.info("playerRegistry closeAll reason=$reason active=0")
            return
        }
        DesktopRuntimeLog.info("playerRegistry closeAll start reason=$reason active=${activePlayers.size} ids=${activePlayers.keys}")
        val snapshot = activePlayers.toMap()
        activePlayers.clear()
        snapshot.forEach { (id, entry) ->
            runCatching { entry.close() }
                .onSuccess { DesktopRuntimeLog.info("playerRegistry close success id=$id reason=$reason") }
                .onFailure { DesktopRuntimeLog.error("playerRegistry close failed id=$id reason=$reason", it) }
        }
        DesktopRuntimeLog.info("playerRegistry closeAll done reason=$reason")
    }

    @Synchronized
    fun trackCloseThread(thread: Thread) {
        closeThreads.removeAll { !it.isAlive }
        closeThreads.add(thread)
        DesktopRuntimeLog.info(
            "playerRegistry trackCloseThread name=${thread.name} daemon=${thread.isDaemon} tracked=${closeThreads.size}",
        )
    }

    fun awaitAllCloses(timeoutMs: Long) {
        val threads = synchronized(this) {
            closeThreads.removeAll { !it.isAlive }
            closeThreads.toList()
        }
        if (threads.isEmpty()) {
            DesktopRuntimeLog.info("playerRegistry awaitAllCloses none timeoutMs=$timeoutMs")
            return
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        DesktopRuntimeLog.info("playerRegistry awaitAllCloses count=${threads.size} timeoutMs=$timeoutMs")
        threads.forEach { thread ->
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remaining == 0L) return@forEach
            runCatching { thread.join(remaining) }
        }
        val alive = threads.filter { it.isAlive }
        DesktopRuntimeLog.info(
            "playerRegistry awaitAllCloses done stillAlive=${alive.size} alive=${alive.joinToString { "${it.name}:${it.state}" }}",
        )
    }
}
