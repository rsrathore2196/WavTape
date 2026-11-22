package com.example.playback

import com.example.data.model.MusicTrack
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** One optional provider lookup per playing occurrence and source policy. */
internal class FastStartUpgrade(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var attemptedKey: String? = null

    fun request(key: String, stillPlaying: () -> Boolean, resolve: suspend () -> MusicTrack?,
        onReady: (MusicTrack) -> Unit) {
        if (attemptedKey == key || !stillPlaying()) return
        cancel()
        attemptedKey = key
        job = scope.launch {
            try {
                val candidate = BackgroundWorkCoordinator.localDataPreparation { resolve() }
                if (candidate != null && stillPlaying()) {
                    job = null
                    onReady(candidate)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.d("FastStartUpgrade", "The starting stream remains active: ${error.javaClass.simpleName}")
            } finally {
                if (job === currentCoroutineContext()[Job]) job = null
            }
        }
    }

    fun cancel(reset: Boolean = false) {
        job?.cancel()
        job = null
        if (reset) attemptedKey = null
    }
}
