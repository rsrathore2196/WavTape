package com.example

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.OnboardingScreen
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
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingAppearanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun glossyOnboardingUsesTheDarkThemesAccentsAndKeepsProfileChoices() = verify(false)
    @Test fun glossyOnboardingUsesTheLightThemesAccentsAndKeepsProfileChoices() = verify(true)

    @Test fun bothLogosKeepTheSuppliedCyanBlueBrandAcrossThemeChanges() {
        val purple = DarkAppColors.copy(primaryAccent = Color(0xFFDD44EE), secondaryAccent = Color(0xFFB52CD1))
        val green = DarkAppColors.copy(primaryAccent = Color(0xFF33DD77), secondaryAccent = Color(0xFF22BB66))
        var colors by mutableStateOf(purple)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAppColors provides colors) {
            OnboardingScreen(onComplete = { _, _, _, _, _ -> })
        } } }
        fun logoPixels(tag: String, name: String): BufferedImage {
            val file = File("/tmp/$tag-$name.png")
            compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath, RoborazziOptions(taskType = RoborazziTaskType.Record))
            val image = ImageIO.read(file)
            var blue = 0
            var cyan = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val pixel = java.awt.Color(image.getRGB(x, y))
                if (pixel.blue >= 160 && pixel.blue > pixel.red + 35 && pixel.blue > pixel.green + 20) blue++
                if (pixel.blue >= 160 && pixel.green >= 140 && pixel.green > pixel.red + 40) cyan++
            }
            assertTrue("WavTape's supplied blue and cyan colors must remain visible", blue > 30 && cyan > 30)
            return image
        }
        fun assertBrandOverDifferentThemes(before: BufferedImage, after: BufferedImage) {
            assertEquals(before.width, after.width)
            assertEquals(before.height, after.height)
            assertNotEquals("The logo backdrop responds to theme changes", before.getRGB(0, 0), after.getRGB(0, 0))
            var compared = 0
            for (y in 1 until before.height - 1) for (x in 1 until before.width - 1) {
                // Only sample inside the mark. The transparent edges blend with the backdrop.
                val insideMark = (y - 1..y + 1).all { row ->
                    (x - 1..x + 1).all { column ->
                        val pixel = java.awt.Color(before.getRGB(column, row))
                        pixel.blue >= 160 && pixel.blue > pixel.red + 60
                    }
                }
                if (insideMark) {
                    val first = java.awt.Color(before.getRGB(x, y))
                    val second = java.awt.Color(after.getRGB(x, y))
                    assertEquals(first.red.toDouble(), second.red.toDouble(), 2.0)
                    assertEquals(first.green.toDouble(), second.green.toDouble(), 2.0)
                    assertEquals(first.blue.toDouble(), second.blue.toDouble(), 2.0)
                    compared++
                }
            }
            assertTrue("Compare the supplied artwork across both themes", compared > 30)
        }
        val welcome = logoPixels("onboarding_brand_logo", "purple-theme")
        val welcomeWordmark = logoPixels("onboarding_brand_wordmark", "purple-theme")
        compose.runOnIdle { colors = green }
        assertBrandOverDifferentThemes(welcome, logoPixels("onboarding_brand_logo", "green-theme"))
        assertBrandOverDifferentThemes(welcomeWordmark, logoPixels("onboarding_brand_wordmark", "green-theme"))
        compose.onNodeWithTag("start_listening_button").performScrollTo().performClick()
        val details = logoPixels("onboarding_details_logo", "green-theme")
        val detailsWordmark = logoPixels("onboarding_details_wordmark", "green-theme")
        compose.runOnIdle { colors = purple }
        assertBrandOverDifferentThemes(details, logoPixels("onboarding_details_logo", "purple-theme"))
        assertBrandOverDifferentThemes(detailsWordmark, logoPixels("onboarding_details_wordmark", "purple-theme"))
    }

    private fun verify(light: Boolean) {
        val inherited = if (light) LightAppColors else DarkAppColors
        var submitted: List<Any>? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides inherited) {
                OnboardingScreen(onComplete = { name, languages, country, code, flag ->
                    submitted = listOf(name, languages, country, code, flag)
                })
            }
        } }
        fun capture(tag: String, label: String): java.awt.image.BufferedImage {
            val file = File("/tmp/onboarding-${if (light) "light" else "dark"}-$label.png")
            compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath, RoborazziOptions(taskType = RoborazziTaskType.Record))
            return ImageIO.read(file)
        }
        fun assertCanvas(label: String) {
            val image = capture("onboarding_screen", label)
            val upperGlow = image.getRGB(3, image.height / 10)
            val quietEdge = image.getRGB(3, image.height / 2)
            val lowerGlow = image.getRGB(image.width - 4, image.height * 9 / 10)
            assertNotEquals("The upper accent should come through in $label", upperGlow, quietEdge)
            assertNotEquals("The lower accent should come through in $label", lowerGlow, quietEdge)
            assertNotEquals("The base should have a subtle tint in $label", 0, quietEdge and 0x00ffffff)
        }
        fun textColor(label: String): Color {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.color
        }
        compose.onNodeWithText("Redefining High Fidelity").assertIsDisplayed()
        compose.onNodeWithText("WELCOME TO YOUR MUSIC").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_brand_logo").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_brand_wordmark").assertIsDisplayed()
            .assertContentDescriptionEquals("WavTape").assertHeightIsEqualTo(40.dp)
        compose.onNodeWithText("WavTape").assertDoesNotExist()
        assertEquals("The tagline is white in every theme", Color.White,
            textColor("Redefining High Fidelity"))
        assertCanvas("welcome")
        compose.onNodeWithTag("start_listening_button").performScrollTo().performClick()
        compose.onNodeWithText("Make it yours.").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_details_logo").assertWidthIsEqualTo(70.4.dp).assertHeightIsEqualTo(70.4.dp)
        compose.onNodeWithTag("onboarding_details_wordmark").assertIsDisplayed()
            .assertContentDescriptionEquals("WavTape").assertHeightIsEqualTo(32.dp)
        compose.onNodeWithText("WavTape").assertDoesNotExist()
        compose.onNodeWithText("A LITTLE ABOUT YOU").assertDoesNotExist()
        for (name in listOf("English", "Hindi", "Punjabi", "Spanish", "Korean"))
            compose.onNodeWithTag("language_chip_$name").assertExists()
        compose.onNodeWithTag("language_chip_Tamil").assertDoesNotExist()
        compose.onNodeWithTag("language_chip_Telugu").assertDoesNotExist()
        assertCanvas("details")
        compose.onNodeWithTag("onboarding_name_input").performScrollTo().performTextInput("  Alex  ")
        compose.onNodeWithTag("language_chip_English").performScrollTo().performClick()
        compose.onNodeWithText("2 chosen").assertExists()
        val surface = capture("onboarding_languages_card", "card")
        assertEquals("The form keeps its existing card surface", 0x15171c,
            surface.getRGB(8, surface.height / 2) and 0x00ffffff)
        compose.onNodeWithTag("onboarding_submit_button").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("Alex", listOf("Punjabi", "Hindi"), "India", "IN", "🇮🇳"), submitted)
    }
}
