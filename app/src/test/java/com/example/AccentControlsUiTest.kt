package com.example

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.AccentPageHeader
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
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentControlsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun secondaryAccentChangesRepaintControlsWhileHeadersStayNeutral() {
        var palette by mutableStateOf(DarkAppColors.copy(primaryAccent = Color(0xFF25C5FF), secondaryAccent = Color(0xFFBA75FF)))
        var checked by mutableStateOf(true)
        var clicks = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides palette) {
                Column {
                    AccentPageHeader("Your Library", "Saved music", "accent_header")
                    AccentButton({ clicks++ }, modifier = Modifier.padding(16.dp).width(240.dp).height(48.dp).testTag("accent_button")) {
                        Text("Play all")
                    }
                    AccentSwitch(checked, { checked = it }, Modifier.testTag("accent_switch"))
                    AccentButton({}, enabled = false, modifier = Modifier.testTag("disabled_accent_button")) { Text("Disabled") }
                    AccentButton({}, elevation = androidx.compose.material3.ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                        modifier = Modifier.padding(16.dp).width(240.dp).height(48.dp).testTag("elevated_accent_button")) {
                        Text("Elevated")
                    }
                }
            }
        } }
        fun image(tag: String, name: String): java.awt.image.BufferedImage {
            val file = File("build/reports/accent-adaptation/$name.png").apply { parentFile.mkdirs() }
            compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            return ImageIO.read(file)
        }
        fun samples(name: String): List<Int> {
            val header = image("accent_header", "$name-header")
            val button = image("accent_button", "$name-button")
            val toggle = image("accent_switch", "$name-switch")
            return listOf(header.getRGB(header.width * 3 / 4, header.height * 3 / 4),
                button.getRGB(button.width * 3 / 4, button.height / 2),
                toggle.getRGB(toggle.width / 5, toggle.height / 2))
        }
        val before = samples("first")
        compose.runOnIdle { palette = palette.copy(secondaryAccent = Color(0xFFFF6E95)) }
        val after = samples("second")
        assertEquals("Header glass must not be accent-painted", before.first(), after.first())
        before.drop(1).zip(after.drop(1)).forEach { (a, b) ->
            assertNotEquals("Accent-filled controls still observe both endpoints", a, b)
        }
        val elevated = image("elevated_accent_button", "elevated-button")
        val centerX = elevated.width / 2
        assertEquals("AccentButton must not cast an inner transparent-Surface shadow over its gradient fill",
            elevated.getRGB(centerX, 2), elevated.getRGB(centerX, elevated.height / 4))
        compose.onNodeWithTag("accent_button").performClick()
        assertEquals(1, clicks)
        compose.onNodeWithTag("disabled_accent_button").assertIsNotEnabled()
        compose.onNodeWithTag("accent_switch").assertIsOn().performClick().assertIsOff()
    }
}
