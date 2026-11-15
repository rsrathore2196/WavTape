package com.example

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.components.rememberVisibleInfiniteFloat
import com.example.ui.screens.VisibleItemArtwork
import com.example.ui.util.LocalArtworkRequestsActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeViewportEffectsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private var parentArtwork by mutableStateOf(true)
    private var parentEffects by mutableStateOf(true)
    private val effects = BooleanArray(2)
    private val artwork = BooleanArray(2)
    private val compositions = IntArray(2)
    private val phaseUpdates = IntArray(2)
    private val phases = arrayOfNulls<State<Float>>(2)

    private fun show(horizontal: Boolean = false, animate: Boolean = false) = compose.setContent {
        MaterialTheme {
            val noSpeculation = remember {
                // The probes below model already-composed prefetched/retained
                // consumers. Avoid Robolectric's idle prefetch repost loop.
                object : LazyListPrefetchStrategy {
                    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {}
                    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {}
                    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {}
                }
            }
            list = rememberLazyListState(prefetchStrategy = noSpeculation)
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalArtworkRequestsActive provides parentArtwork,
                LocalVisualEffectsActive provides parentEffects) {
                Column {
                    if (horizontal) LazyRow(state = list, modifier = Modifier.width(160.dp).height(80.dp)) {
                        items(8, key = { "row_$it" }) { Text("Row $it", Modifier.width(80.dp).height(80.dp)) }
                    } else LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(160.dp)) {
                        items(8, key = { "row_$it" }) { Text("Row $it", Modifier.fillMaxWidth().height(80.dp)) }
                    }
                    // Keep both consumers composed even outside the list viewport,
                    // as lazy prefetch and retained item content can do in Home.
                    for ((probe, key) in listOf("row_0", "row_3").withIndex()) {
                        VisibleItemArtwork(list, key) {
                            val activeEffects = LocalVisualEffectsActive.current
                            val activeArtwork = LocalArtworkRequestsActive.current
                            if (animate) {
                                val spec = remember { infiniteRepeatable(tween<Float>(10_000, easing = LinearEasing)) }
                                val phase = rememberVisibleInfiniteFloat(0f, 1f, spec, "viewport_probe_$probe")
                                SideEffect { phases[probe] = phase }
                                LaunchedEffect(phase) { snapshotFlow { phase.value }.collect { phaseUpdates[probe]++ } }
                            }
                            SideEffect {
                                effects[probe] = activeEffects
                                artwork[probe] = activeArtwork
                                compositions[probe]++
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun retainedOffscreenEffectsPauseAndResumeAtTheSameViewportBoundaryAsArtwork() {
        show()
        compose.waitForIdle()
        compose.runOnIdle {
            assertArrayEquals(booleanArrayOf(true, false), effects)
            assertArrayEquals(booleanArrayOf(true, false), artwork)
            scope.launch { list.scrollToItem(3) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertArrayEquals(booleanArrayOf(false, true), effects)
            assertArrayEquals(booleanArrayOf(false, true), artwork)
            assertEquals(3, list.firstVisibleItemIndex)
            assertEquals(8, list.layoutInfo.totalItemsCount)
        }
    }

    @Test fun offsetChangesInsideTheVisibleRowDoNotRecomposeEffectsConsumers() {
        show()
        compose.waitForIdle()
        val before = compositions.copyOf()
        repeat(5) {
            compose.runOnIdle { scope.launch { list.scrollBy(3f) } }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            assertTrue(list.firstVisibleItemScrollOffset > 0)
            assertArrayEquals(booleanArrayOf(true, false), effects)
            assertArrayEquals("Pixel scrolling must not restart visual consumers", before, compositions)
        }
    }

    @Test fun horizontalArtistConsumersUseTheirOwnViewportBoundary() {
        show(horizontal = true)
        compose.waitForIdle()
        compose.runOnIdle {
            assertArrayEquals(booleanArrayOf(true, false), effects)
            scope.launch { list.scrollToItem(3) }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertArrayEquals(booleanArrayOf(false, true), effects) }
    }

    @Test fun actualInfiniteAnimationStopsOffscreenAndResumesItsRetainedPhaseWhenVisible() {
        compose.mainClock.autoAdvance = false
        show(animate = true)
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val firstPhase = phases[0]!!.value
        val hiddenUpdates = phaseUpdates[1]
        compose.mainClock.advanceTimeBy(128)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("The visible effect keeps its original motion", phases[0]!!.value > firstPhase)
            assertEquals("The prefetched effect must not schedule animation updates", hiddenUpdates, phaseUpdates[1])
            parentArtwork = false
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val imagePausedPhase = phases[0]!!.value
        compose.mainClock.advanceTimeBy(128)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Pausing image requests must preserve visible motion", phases[0]!!.value > imagePausedPhase)
            scope.launch { list.scrollToItem(3) }
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val stoppedPhase = phases[0]!!.value
        val stoppedUpdates = phaseUpdates[0]
        val enteringPhase = phases[1]!!.value
        compose.mainClock.advanceTimeBy(128)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Leaving the viewport freezes phase", stoppedPhase, phases[0]!!.value, 0f)
            assertEquals("No background animation updates while retained offscreen", stoppedUpdates, phaseUpdates[0])
            assertTrue(phases[1]!!.value > enteringPhase)
            scope.launch { list.scrollToItem(0) }
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val resumedPhase = phases[0]!!.value
        assertTrue("Reentry retains elapsed motion instead of restarting", resumedPhase >= stoppedPhase)
        compose.mainClock.advanceTimeBy(128)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(phases[0]!!.value > resumedPhase) }
    }

    @Test fun visibleItemsRespectBothParentEffectsAndParentArtworkSuspension() {
        show()
        compose.waitForIdle()
        compose.runOnIdle { parentEffects = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertArrayEquals(booleanArrayOf(false, false), effects)
            assertArrayEquals(booleanArrayOf(true, false), artwork)
            parentEffects = true
            parentArtwork = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertArrayEquals("Artwork admission must not pause visible animations", booleanArrayOf(true, false), effects)
            assertArrayEquals(booleanArrayOf(false, false), artwork)
            parentArtwork = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertArrayEquals(booleanArrayOf(true, false), effects)
            assertArrayEquals(booleanArrayOf(true, false), artwork)
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
    }
}
