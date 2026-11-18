package com.example

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import com.example.data.model.ServerHomeShelf
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.HomeScreenContent
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.github.takahirom.roborazzi.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAppHeaderUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private var settingsOpened = 0
    private lateinit var renderedPalette: AppThemeColors
    private var refreshes = 0
    private var refreshing by mutableStateOf(false)

    private fun show(colors: AppThemeColors = DarkAppColors, fontScale: Float = 1f) {
        renderedPalette = colors
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppColors provides colors, LocalDynamicGlassEnabled provides true,
                    LocalVisualEffectsActive provides false, LocalArtworkRequestsActive provides false,
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        androidx.compose.ui.platform.LocalDensity.current.density, fontScale)) {
                    val prefetch = remember { object : LazyListPrefetchStrategy {
                        override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {}
                        override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {}
                        override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {}
                    } }
                    list = rememberLazyListState(prefetchStrategy = prefetch)
                    scope = rememberCoroutineScope()
                    Box(Modifier.fillMaxSize().testTag("header_review")) {
                        HomeScreenContent(userProfile = UserProfile(name = "RS Rathore"), listState = list,
                            serverShelves = (1..3).map { ServerHomeShelf("s$it", "Shelf $it", tracks = listOf(
                                MusicTrack("song$it", "Song $it", "Artist", "Album", 180_000, "", ""))) },
                            onTrackClick = { _, _ -> }, onToggleFavorite = {},
                            onOpenSettings = { settingsOpened++ }, isRefreshing = refreshing,
                            onRefresh = { refreshes++; refreshing = true })
                    }
                }
            }
        }
    }

    @Test fun headerStaysFixedWhileGreetingScrollsAndSettingsRemainsReachable() {
        show()
        assertGreetingBelowHeader()
        val header = bounds("home_fixed_header")
        val settings = bounds("top_settings_button")
        capture("dark")
        compose.runOnIdle { scope.launch { list.scrollToItem(3) } }
        compose.waitForIdle()
        assertEquals(header, bounds("home_fixed_header"))
        assertEquals(settings, bounds("top_settings_button"))
        compose.onNodeWithText("RS RATHORE").assertDoesNotExist()
        compose.onNodeWithTag("top_settings_button").assertIsDisplayed().performClick()
        assertEquals(1, settingsOpened)
        compose.runOnIdle { scope.launch { list.scrollToItem(0) } }
        compose.waitForIdle()
        val feed = bounds("home_screen")
        compose.onNodeWithTag("home_screen").performTouchInput {
            swipe(start = androidx.compose.ui.geometry.Offset(centerX, header.bottom - feed.top + 24f),
                end = androidx.compose.ui.geometry.Offset(centerX, height - 24f), durationMillis = 500)
        }
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(header, bounds("home_fixed_header"))
        val progress = compose.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNode().boundsInRoot
        assertTrue("Pull refresh must render below the fixed header", progress.top >= header.bottom)
        compose.onNodeWithTag("top_settings_button").performClick()
        assertEquals(2, settingsOpened)
        capture("scrolled-refreshing")
    }

    @Test fun lightHeaderOffsetsGreetingAndUsernameWithoutChangingTheirTypography() {
        show(LightAppColors)
        assertGreetingBelowHeader()
        val greeting = compose.onNodeWithTag("home_greeting_text")
        assertEquals(16.1.sp * 1.05f, layout(greeting).layoutInput.style.fontSize)
        assertEquals(21.sp * 1.05f, layout(compose.onNodeWithText("RS RATHORE")).layoutInput.style.fontSize)
        val hero = bounds("infinite_radio_hero_card")
        assertEquals(hero.left + with(compose.density) { 8.dp.toPx() }, bounds("home_greeting_text").left, 1f)
        assertEquals(bounds("home_greeting_text").left, bounds("home_username_text").left, 1f)
        capture("light")
        assertBrandFits()
    }

    @Test @Config(qualifiers = "w320dp-h720dp-port")
    fun compactCustomAccentHeaderKeepsBrandAndSettingsInsideItsBounds() {
        show(DarkAppColors.copy(primaryAccent = androidx.compose.ui.graphics.Color(0xFFB89AFF)), fontScale = 1.3f)
        assertGreetingBelowHeader()
        capture("compact-custom-accent")
        assertBrandFits()
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeFeedScrollDoesNotMoveTheHeader() {
        show()
        assertGreetingBelowHeader()
        capture("landscape-initial")
        assertBrandFits()
        val header = bounds("home_fixed_header")
        compose.runOnIdle { scope.launch { list.scrollToItem(3) } }
        compose.waitForIdle()
        assertEquals(header, bounds("home_fixed_header"))
        compose.onNodeWithTag("top_settings_button").assertIsDisplayed().performClick()
        assertEquals(1, settingsOpened)
        capture("landscape")
    }

    private fun assertGreetingBelowHeader() {
        assertTrue(bounds("home_greeting").top >= bounds("home_fixed_header").bottom)
        assertEquals("The feed scrolls behind the fixed glass header", bounds("home_fixed_header").top,
            bounds("home_screen").top, 1f)
        compose.onNodeWithTag("home_fixed_header").onChildren().filter(hasText("RS RATHORE")).assertCountEquals(0)
    }

    private fun assertBrandFits() {
        compose.onNodeWithTag("home_header_logo").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("top_settings_button_surface", useUnmergedTree = true)
            .assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        compose.onNodeWithTag("top_settings_button").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val header = bounds("home_fixed_header")
        val settings = bounds("top_settings_button")
        for (word in listOf("XTREME", "PLAYER")) {
            val node = compose.onNodeWithText(word).assertIsDisplayed()
            val measured = layout(node)
            assertEquals(1, measured.lineCount)
            assertFalse(measured.isLineEllipsized(0))
            // Text layout rounds its integer width; allow the subpixel remainder.
            assertTrue("$word should fit completely", measured.getLineRight(0) <= measured.size.width + 1f)
            val box = node.fetchSemanticsNode().boundsInRoot
            assertTrue(box.left >= header.left && box.right < settings.left)
        }
        assertTrue(settings.right <= header.right && settings.bottom <= header.bottom)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun capture(name: String) {
        val file = File("build/reports/home-header-glass-review/$name.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag("header_review").captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        if (name in listOf("dark", "light", "compact-custom-accent")) {
            val headerFile = File(file.parentFile, "$name-header.png")
            compose.onNodeWithTag("home_fixed_header").captureRoboImage(headerFile.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            val pixels = javax.imageio.ImageIO.read(headerFile)
            val actual = androidx.compose.ui.graphics.Color(pixels.getRGB(pixels.width / 2, pixels.height * 3 / 4))
            assertEquals("The glass surface stays neutral", actual.red, actual.green, .03f)
            assertEquals(actual.green, actual.blue, .03f)
            if (renderedPalette.isDark) assertTrue("Dark glass remains subtle", actual.red < .25f)
            else assertTrue("Light glass must not become a heavy gray slab", actual.red > .75f)
            assertEquals(com.example.ui.components.tabHeaderAccentColor(renderedPalette.primaryAccent, renderedPalette.isDark),
                layout(compose.onNodeWithText("XTREME")).layoutInput.style.color)
            assertEquals(renderedPalette.textPrimary, layout(compose.onNodeWithText("PLAYER")).layoutInput.style.color)
        }
    }
}
