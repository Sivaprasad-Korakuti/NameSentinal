package com.example.ancnameguard

/** Tiny in-process state holder shared by the service and the UI. Main thread only. */
object GuardState {
    enum class Status { DISABLED, STANDBY, LISTENING }

    var running = false
    var status = Status.DISABLED
    var bluetooth: String? = null
    var transcript = "Tap “Enable Auto-Guard” to begin…"
    var alert = false
    var level = 0f

    private val listeners = mutableSetOf<() -> Unit>()

    fun add(l: () -> Unit) { listeners.add(l) }
    fun remove(l: () -> Unit) { listeners.remove(l) }
    fun changed() { listeners.toList().forEach { it() } }
}
