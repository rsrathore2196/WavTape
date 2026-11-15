package com.example

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import com.example.service.HeadAwareAudioCacheEvictor
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HeadAwareAudioCacheEvictorTest {
    private val mib = 1024L * 1024L
    private inner class Harness(bytes: Long) {
        val evictor = HeadAwareAudioCacheEvictor(bytes)
        val spans = linkedMapOf<Pair<String, Long>, CacheSpan>()
        val removed = mutableListOf<CacheSpan>()
        var refuseRemoval = false
        val cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { proxy, method, args ->
            when (method.name) {
                "removeSpan" -> {
                    val span = args!![0] as CacheSpan
                    removed += span
                    if (!refuseRemoval) {
                        spans.remove(span.key to span.position)
                        evictor.onSpanRemoved(proxy as Cache, span)
                    }
                    null
                }
                "toString" -> "CacheHarness"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as Cache
        fun add(key: String, position: Long, size: Long, age: Long): CacheSpan =
            CacheSpan(key, position, size, age, File("/$key-$position-$age")).also {
                spans[key to position] = it; evictor.onSpanAdded(cache, it)
            }
        fun touch(span: CacheSpan, age: Long): CacheSpan = CacheSpan(span.key, span.position, span.length, age,
            File("/${span.key}-${span.position}-$age")).also {
            spans[span.key to span.position] = it
            evictor.onSpanTouched(cache, span, it)
        }
        fun has(key: String, offset: Long = 0) = spans.containsKey(key to offset)
    }
    @Test fun ordinaryOldestSpansAreEvictedBeforeProtectedOpeningSpans() {
        val h = Harness(16 * mib)
        h.add("head", 0, 2 * mib, 1)
        h.add("old-body", 4 * mib, 6 * mib, 2)
        h.add("new-body", 4 * mib, 6 * mib, 3)
        h.add("incoming", 4 * mib, 4 * mib, 4)
        assertTrue(h.has("head")); assertFalse(h.has("old-body", 4 * mib))
        assertTrue(h.has("new-body", 4 * mib)); assertEquals(12 * mib, h.evictor.currentBytes)
    }
    @Test fun headBudgetDemotesOlderHeadsAndNeverExceedsItsFractionOrAbsoluteCap() {
        val h = Harness(16 * mib)
        h.add("a", 0, 2 * mib, 1); h.add("b", 0, 2 * mib, 2); h.add("c", 0, 2 * mib, 3)
        assertEquals(4 * mib, h.evictor.protectedBytes)
        h.evictor.setMaximumBytes(h.cache, 4 * mib)
        assertTrue(h.evictor.protectedBytes <= mib)
        assertTrue(h.evictor.currentBytes <= 4 * mib)
        assertFalse(h.has("a"))
        h.evictor.setMaximumBytes(h.cache, 8L * 1024 * mib)
        assertEquals(96 * mib, h.evictor.maximumHeadBytes)
    }
    @Test fun touchesAndReplacementSpansRetainAccurateAccountingAndRecency() {
        val h = Harness(20 * mib)
        val a = h.add("a", 4 * mib, 4 * mib, 1)
        h.add("b", 4 * mib, 4 * mib, 2)
        h.touch(a, 5)
        h.add("b", 4 * mib, 2 * mib, 6)
        assertEquals(6 * mib, h.evictor.currentBytes)
        h.evictor.onSpanRemoved(h.cache, a) // Stale file removal after a touch.
        assertEquals(6 * mib, h.evictor.currentBytes)
        h.evictor.setMaximumBytes(h.cache, 4 * mib)
        assertFalse(h.has("a", 4 * mib)); assertTrue(h.has("b", 4 * mib))
        assertEquals(2 * mib, h.evictor.currentBytes)
    }
    @Test fun restoredSpansUnknownWritesAndDynamicZeroBudgetAreBounded() {
        val h = Harness(10 * mib)
        h.add("a", 0, 2 * mib, 1); h.add("a", 4 * mib, 8 * mib, 2)
        h.evictor.onCacheInitialized()
        h.evictor.onStartFile(h.cache, "new", 0, -1)
        assertEquals(10 * mib, h.evictor.currentBytes)
        h.evictor.onStartFile(h.cache, "new", 0, 3 * mib)
        assertTrue(h.evictor.currentBytes <= 7 * mib)
        h.evictor.setMaximumBytes(h.cache, 0)
        assertEquals(0, h.evictor.currentBytes); assertEquals(0, h.evictor.protectedBytes)
    }
    @Test(timeout = 1_000) fun refusedRemovalIsAttemptedOnlyOncePerVictimWithoutSpinning() {
        val h = Harness(20 * mib)
        h.add("a", 0, 2 * mib, 1); h.add("b", 4 * mib, 8 * mib, 2)
        h.refuseRemoval = true
        h.evictor.setMaximumBytes(h.cache, 0)
        assertEquals(2, h.removed.size)
        assertEquals(10 * mib, h.evictor.currentBytes)
        h.refuseRemoval = false
        h.evictor.setMaximumBytes(h.cache, 0)
        assertEquals(0, h.evictor.currentBytes)
    }
}
