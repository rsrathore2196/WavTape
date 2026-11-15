package com.example.service

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/** Short opening spans have a bounded second chance; every byte still shares the audio quota.
 * Media3 invokes all callbacks under its cache monitor. No filesystem scans occur on reads. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class HeadAwareAudioCacheEvictor(initialBytes: Long) : CacheEvictor {
    companion object {
        internal const val HEAD_REGION_BYTES = 4L * 1024L * 1024L
        internal const val MAX_HEAD_BYTES = 96L * 1024L * 1024L
    }
    var maximumBytes = initialBytes.coerceAtLeast(0L)
        private set
    internal val maximumHeadBytes get() = minOf(MAX_HEAD_BYTES, maximumBytes / 4L)
    internal var currentBytes = 0L
        private set
    internal var protectedBytes = 0L
        private set
    private data class Location(val key: String, val position: Long)
    private val byLocation = HashMap<Location, CacheSpan>()
    private val order = Comparator<CacheSpan> { a, b ->
        val age = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        if (age != 0) age else a.compareTo(b)
    }
    private val ordinary = TreeSet(order)
    private val heads = TreeSet(order)
    private var evicting = false

    fun setMaximumBytes(cache: Cache, bytes: Long) = synchronized(cache) {
        maximumBytes = bytes.coerceAtLeast(0L)
        boundHeads()
        evict(cache, 0L)
    }
    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        // Unknown-size writes cannot be reserved. Their committed length is enforced
        // by onSpanAdded; a negative length must never cause arithmetic overflow.
        evict(cache, length.coerceAtLeast(0L))
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        if (!span.isCached || span.length <= 0L) return
        remove(Location(span.key, span.position))
        byLocation[Location(span.key, span.position)] = span
        currentBytes += span.length
        // A cache file is indivisible. Charge its full size to the protected budget,
        // including a fragment which extends beyond the opening region.
        if (span.position < HEAD_REGION_BYTES && span.length <= maximumHeadBytes) {
            heads.add(span); protectedBytes += span.length
        } else ordinary.add(span)
        boundHeads()
        evict(cache, 0L)
    }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        val location = Location(span.key, span.position)
        val retained = byLocation[location] ?: return
        // Ignore a delayed removal for a file already replaced at the same offset.
        if (retained.file != span.file) return
        remove(location)
    }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        val location = Location(oldSpan.key, oldSpan.position)
        val retained = byLocation[location] ?: return
        val wasHead = heads.remove(retained)
        ordinary.remove(retained)
        currentBytes += newSpan.length - retained.length
        if (wasHead) protectedBytes += newSpan.length - retained.length
        byLocation[location] = newSpan
        if (wasHead) heads.add(newSpan) else ordinary.add(newSpan)
        // Touches preserve demotion: repeated reads cannot permanently re-protect
        // a head which already exhausted the bounded opening-byte allowance.
        boundHeads()
        evict(cache, 0L)
    }
    private fun remove(location: Location) {
        val old = byLocation.remove(location) ?: return
        currentBytes -= old.length
        if (heads.remove(old)) protectedBytes -= old.length else ordinary.remove(old)
    }
    private fun boundHeads() {
        while (heads.isNotEmpty() && protectedBytes > maximumHeadBytes) {
            val span = heads.pollFirst() ?: break
            protectedBytes -= span.length
            ordinary.add(span)
        }
    }
    private fun evict(cache: Cache, incoming: Long) {
        if (evicting) return
        evicting = true
        try {
            // A broken/locked removal must not spin under Media3's monitor or hold
            // up playback. Each victim is attempted once per eviction callback.
            val attempted = HashSet<Location>()
            while (currentBytes > maximumBytes || incoming > maximumBytes - currentBytes) {
                val victim = ordinary.firstOrNull { Location(it.key, it.position) !in attempted }
                    ?: heads.firstOrNull { Location(it.key, it.position) !in attempted } ?: break
                attempted += Location(victim.key, victim.position)
                try { cache.removeSpan(victim) }
                catch (_: Cache.CacheException) { /* Retry on a later cache callback. */ }
            }
        } finally { evicting = false }
    }
}
