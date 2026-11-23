package com.example

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.headerArtworkEdgeBlend
import com.example.ui.components.playerArtworkEdgeBlend
import com.example.ui.components.PlayerGlassBackground
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerArtworkBlendUiTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(sdk = [28, 36])
    fun lighterLowerReflectionKeepsTheWholeUpperPlayerAndBothSeamsIntact() {
        var inset by mutableStateOf(70.dp)
        var coverHeight by mutableStateOf(160.dp)
        val bounds = mutableStateOf<Rect?>(null)
        val pixels = IntArray(96 * 96) { index ->
            val row = index / 96
            val column = index % 96
            when {
                row < 24 -> if (column < 48) Color.Red else Color.Yellow
                row > 71 -> if (column < 48) Color.Blue else Color.Cyan
                else -> Color.Green
            }.toArgb()
        }
        val field = com.example.ui.util.artworkColorField(pixels, 96, 96)
        val evenlyBlurred = com.example.ui.util.ArtworkColorField(field.pixels, field.width, field.height, field.pixels)
        val backdrop = mutableStateOf(evenlyBlurred)
        compose.setContent {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicAlbumGlow = true),
                LocalDynamicGlassEnabled provides true) {
                // Offset the player and cover independently to check root-to-local alignment.
                Box(Modifier.size(240.dp, 360.dp)) {
                    Box(Modifier.offset(x = 9.dp, y = 15.dp).size(200.dp, 320.dp).testTag("mirrored_cover_player")) {
                        PlayerGlassBackground(bounds, colorField = backdrop)
                        Box(Modifier.offset(x = 20.dp, y = inset).size(160.dp, coverHeight)
                            .onGloballyPositioned { bounds.value = it.boundsInRoot() })
                    }
                }
            }
        }
        fun capture(): java.awt.image.BufferedImage {
            val file = java.io.File.createTempFile("mirrored-player-", ".png")
            return try {
                compose.onNodeWithTag("mirrored_cover_player").captureRoboImage(file.absolutePath,
                    roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                javax.imageio.ImageIO.read(file)
            } finally { file.delete() }
        }
        fun verify() {
            compose.runOnIdle { backdrop.value = evenlyBlurred }
            val original = capture()
            compose.runOnIdle { backdrop.value = field }
            val image = capture()
            val start = (image.height * inset.value / 320f).toInt()
            val end = (image.height * (inset.value + coverHeight.value) / 320f).toInt()
            fun regionPixels(bitmap: java.awt.image.BufferedImage, y: Int, height: Int) =
                bitmap.getRGB(0, y, bitmap.width, height, null, 0, bitmap.width)
            assertArrayEquals(regionPixels(original, 0, end), regionPixels(image, 0, end))
            assertFalse(regionPixels(original, end + 12, image.height - end - 12)
                .contentEquals(regionPixels(image, end + 12, image.height - end - 12)))
            fun assertReflected(x: Int, inside: Int, outside: Int) {
                val original = java.awt.Color(image.getRGB(x, inside))
                val reflected = java.awt.Color(image.getRGB(x, outside))
                // Bilinear filtering can round channels differently under a flipped transform.
                assertEquals(original.red.toDouble(), reflected.red.toDouble(), 2.0)
                assertEquals(original.green.toDouble(), reflected.green.toDouble(), 2.0)
                assertEquals(original.blue.toDouble(), reflected.blue.toDouble(), 2.0)
            }
            for (x in listOf(image.width / 4, image.width * 3 / 4)) {
                for (offset in listOf(5, 15, 23)) {
                    assertReflected(x, start + offset, start - offset - 1)
                }
                assertReflected(x, end - 1, end)
            }
            val left = image.width / 4
            val right = image.width * 3 / 4
            val upperLeft = java.awt.Color(image.getRGB(left, start - 7))
            val lowerLeft = java.awt.Color(image.getRGB(left, end + 7))
            assertTrue(upperLeft.red > upperLeft.blue)
            assertTrue(lowerLeft.blue > lowerLeft.red)
            assertNotEquals(image.getRGB(left, start - 7), image.getRGB(right, start - 7))
            assertNotEquals(image.getRGB(left, end + 7), image.getRGB(right, end + 7))
        }
        verify()
        compose.runOnIdle {
            inset = 100.dp
            coverHeight = 100.dp
        }
        compose.waitForIdle()
        verify()
    }

    @Test fun contrastingArtworkMarginsMatchTheUnchangedBackdropAndTheCenterStaysOriginal() {
        var cover by mutableStateOf(false)
        val backdrop = Brush.verticalGradient(listOf(Color(0xffac326e), Color(0xff8e7627)))
        compose.setContent {
            Box(Modifier.size(200.dp, 320.dp).background(backdrop).testTag("artwork_blend")) {
                if (cover) Canvas(Modifier.matchParentSize().playerArtworkEdgeBlend(true)) {
                    // A high-contrast edge would expose even a small hard seam.
                    drawRect(Color.White)
                    drawRect(Color.Black, Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
                }
            }
        }
        fun capture(): java.awt.image.BufferedImage {
            val file = java.io.File.createTempFile("artwork-blend-", ".png")
            return try {
                compose.onNodeWithTag("artwork_blend").captureRoboImage(file.absolutePath,
                    roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                javax.imageio.ImageIO.read(file)
            } finally { file.delete() }
        }
        val before = capture()
        compose.runOnIdle { cover = true }
        compose.waitForIdle()
        val after = capture()
        for (y in 0..(after.height * .015f).toInt()) for (x in 0 until after.width) {
            assertEquals("The actual top boundary must reveal only the original backdrop", before.getRGB(x, y), after.getRGB(x, y))
            val bottom = after.height - y - 1
            assertEquals("The actual bottom boundary must reveal only the original backdrop", before.getRGB(x, bottom), after.getRGB(x, bottom))
        }
        for (y in listOf(after.height / 3, after.height / 2, after.height * 2 / 3)) {
            assertEquals(Color.White.toArgb(), after.getRGB(after.width / 4, y))
            assertEquals(Color.Black.toArgb(), after.getRGB(after.width * 3 / 4, y))
        }
        // Verify that the fade starts gradually, rather than as a visible horizontal band.
        for (x in listOf(after.width / 4, after.width * 3 / 4)) for (edge in listOf(false, true)) {
            val samples = (1..8).map { index ->
                val y = if (edge) after.height - index - 1 else index
                java.awt.Color(after.getRGB(x, y))
            }
            assertTrue(samples.zipWithNext().all { (a, b) -> maxOf(kotlin.math.abs(a.red - b.red),
                kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue)) <= 3 })
        }
    }

    @Test fun headerArtworkEdgeBlendPreservesCentralEightyPercentAndFadesOnlyTopAndBottomTenPercent() {
        var cover by mutableStateOf(false)
        val backdrop = Color.Black
        compose.setContent {
            Box(Modifier.size(200.dp, 400.dp).background(backdrop).testTag("header_blend")) {
                if (cover) Canvas(Modifier.matchParentSize().headerArtworkEdgeBlend(topFade = 0.10f, bottomFade = 0.10f, topMargin = 0.01f, bottomMargin = 0.02f)) {
                    drawRect(Color.White)
                }
            }
        }
        fun capture(): java.awt.image.BufferedImage {
            val file = java.io.File.createTempFile("header-blend-", ".png")
            return try {
                compose.onNodeWithTag("header_blend").captureRoboImage(file.absolutePath,
                    roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                javax.imageio.ImageIO.read(file)
            } finally { file.delete() }
        }
        compose.waitForIdle()
        compose.runOnIdle { cover = true }
        compose.waitForIdle()
        val after = capture()

        // 1. Extreme top and bottom edges (0% and 100%) must be completely blended/transparent
        val topPixel = java.awt.Color(after.getRGB(after.width / 2, 0))
        val bottomPixel = java.awt.Color(after.getRGB(after.width / 2, after.height - 1))
        assertEquals(0, topPixel.red)
        assertEquals(0, bottomPixel.red)

        // 2. Central 80% (from 10% to 90% of height) must be fully opaque
        val y15 = (after.height * 0.15f).toInt()
        val y50 = (after.height * 0.50f).toInt()
        val y85 = (after.height * 0.85f).toInt()
        for (y in listOf(y15, y50, y85)) {
            val centerPixel = java.awt.Color(after.getRGB(after.width / 2, y))
            assertEquals("Central 80% at y=$y must be full white", 255, centerPixel.red)
            assertEquals("Central 80% at y=$y must be full white", 255, centerPixel.green)
            assertEquals("Central 80% at y=$y must be full white", 255, centerPixel.blue)
        }
    }
}

