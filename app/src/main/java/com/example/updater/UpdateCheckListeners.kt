package com.example.updater

/** One check can have both an automatic caller and manual callers waiting for its result. */
internal class UpdateCheckListeners {
    private val lock = Any()
    private val pending = mutableListOf<(UpdateStatus) -> Unit>()
    private var result: UpdateStatus? = null

    fun add(listener: ((UpdateStatus) -> Unit)?) {
        if (listener == null) return
        val completed = synchronized(lock) {
            result.also { if (it == null) pending.add(listener) }
        }
        if (completed != null) deliver(listener, completed)
    }

    fun complete(status: UpdateStatus) {
        val listeners = synchronized(lock) {
            if (result != null) return
            result = status
            pending.toList().also { pending.clear() }
        }
        listeners.forEach { deliver(it, status) }
    }

    private fun deliver(listener: (UpdateStatus) -> Unit, status: UpdateStatus) {
        try { listener(status) }
        catch (error: Exception) {
            // A screen callback must not turn a successful check into an update error
            // or prevent another screen's callback from receiving the result.
            android.util.Log.w("AppUpdateManager", "Update result listener failed", error)
        }
    }
}
