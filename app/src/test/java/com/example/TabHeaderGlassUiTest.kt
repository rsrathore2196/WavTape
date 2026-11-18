package com.example

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.performance.LiquidGlassProfile
import com.example.ui.components.*
import com.example.ui.theme.*
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabHeaderGlassUiTest {
    @get:Rule val compose = createComposeRule()
    private var profile by mutableStateOf(LiquidGlassProfile.PREMIUM)
    private var palette by mutableStateOf(DarkAppColors)
    private var page by mutableIntStateOf(0)
    private var glass by mutableStateOf(true)
    private var background by mutableStateOf(Color.White)
    private var sourceDraws = 0
    private var compositions = 0
    private var settingsOpened = 0
    private var underlayClicks = 0
    private val tag get() = if (page == 0) "home_fixed_header" else "page_header_$page"

    private fun show() {
        compose.setContent { MaterialTheme {
            val parentBackdrop = rememberLiquidGlassBackdrop()
            CompositionLocalProvider(LocalAppColors provides palette, LocalDynamicGlassEnabled provides glass,
                LocalLiquidGlassProfile provides profile, LocalLiquidGlassBackdrop provides parentBackdrop) {
                SideEffect { compositions++ }
                // Match production: the dock's recording contains the tab, whose
                // header must use its own content-only recording to avoid feedback.
                Box(Modifier.fillMaxSize().xtremeSharedBackdropSource().testTag("header_scene")) {
                    TabHeaderScaffold(header = {
                        if (page == 0) HomeAppHeader({ settingsOpened++ })
                        else AccentPageHeader(if (page == 1) "Search Online Music" else "Your Library",
                            "Saved music", tag)
                    }) {
                        Canvas(Modifier.fillMaxSize().clickable { underlayClicks++ }) {
                            sourceDraws++; drawRect(background)
                        }
                    }
                }
            }
        } }
    }

    @Test fun allThreeHeadersStayCompactNeutralAndReadableAcrossProfilesAndThemes() {
        show()
        for (colors in listOf(DarkAppColors, LightAppColors)) {
            for (rendering in listOf(LiquidGlassProfile.PREMIUM, LiquidGlassProfile.BALANCED, LiquidGlassProfile.PERFORMANCE)) {
                for (tab in 0..2) {
                    compose.runOnIdle { palette = colors; profile = rendering; page = tab }
                    val header = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    assertTrue("Normal headers remain compact", header.height <= 80f)
                    val surface = sample("${colors.isDark}-${rendering.name}-$tab")
                    assertEquals(surface.red, surface.green, .035f)
                    assertEquals(surface.green, surface.blue, .035f)
                    assertTrue("White headings over white artwork retain text contrast", contrast(Color.White, surface) >= 4.5f)
                    assertTrue("Subtitles retain text contrast", contrast(Color.White.copy(alpha = .9f).compositeOver(surface), surface) >= 4.5f)
                }
            }
        }
    }

    @Test fun artworkUpdatesReachTheHeaderWithoutFeedbackOrAnIdleDrawLoop() {
        background = Color.Red
        show()
        val red = sample("red")
        val before = compositions
        compose.runOnIdle { background = Color.Blue }
        sample("blue-first")
        compose.mainClock.advanceTimeByFrame()
        val blue = sample("blue")
        assertTrue("Header must sample the scrolling source's colors", red.red > blue.red + .08f && blue.blue > red.blue + .08f)
        assertEquals("A source-only draw must not recompose headers", before, compositions)
        val settled = sourceDraws
        repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertEquals("The header never captures itself or starts a capture loop", settled, sourceDraws)
        compose.onNodeWithText("PLAYER").performTouchInput { click() }
        assertEquals("Covered rows cannot be tapped through the header", 0, underlayClicks)
        compose.onNodeWithTag("top_settings_button").performTouchInput { click() }
        assertEquals(1, settingsOpened)
    }

    @Test fun glassDisabledRemainsNeutralAndReadableWithCustomLightSurfaces() {
        glass = false
        show()
        for (colors in listOf(DarkAppColors, LightAppColors)) {
            compose.runOnIdle {
                palette = colors.copy(primaryAccent = Color.Green, secondaryAccent = Color.Magenta, cardBackground = Color.White)
            }
            val surface = sample("static-${colors.isDark}")
            assertTrue(contrast(Color.White, surface) >= 4.5f)
            assertEquals(surface.red, surface.green, .025f)
            assertEquals(surface.green, surface.blue, .025f)
        }
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    private fun sample(name: String): Color {
        val file = File("build/reports/tab-header-glass/$name.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag("header_scene").captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val header = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return Color(ImageIO.read(file).getRGB(header.center.x.toInt(), (header.bottom - 6f).toInt()))
    }
}
