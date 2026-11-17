package com.example

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.UserProfile
import com.example.ui.components.DefaultProfileAvatars
import com.example.ui.components.ProfileAvatar
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LightAppColors
import com.example.ui.theme.LocalAppColors
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
class HumanAvatarIllustrationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allNinePortraitsAreDistinctAndThemeChangesColorAccessoriesWithoutRecoloringSkin() {
        var colors by mutableStateOf(DarkAppColors)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors) {
                Column(Modifier.background(colors.scaffoldBackground).padding(12.dp).testTag("human_avatar_grid")) {
                    for (row in DefaultProfileAvatars.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (avatar in row) Column(Modifier.width(120.dp)) {
                            ProfileAvatar(UserProfile(avatarId = avatar.id), Modifier.size(120.dp).testTag("portrait_${avatar.id}"))
                            Text(avatar.label, color = colors.textPrimary, fontSize = 11.sp,
                                modifier = Modifier.height(36.dp).padding(top = 4.dp))
                        }
                    }
                }
            }
        } }
        val skin = mutableMapOf<String, Int>()
        val fingerprints = mutableSetOf<Int>()
        fun capturePortraits(name: String, first: Boolean) {
            for (avatar in DefaultProfileAvatars) {
                val file = File("build/reports/human-avatar-review/$name-${avatar.id}.png").apply { parentFile.mkdirs() }
                compose.onNodeWithTag("portrait_${avatar.id}").captureRoboImage(file.absolutePath,
                    roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                val pixels = ImageIO.read(file)
                val face = pixels.getRGB(pixels.width / 2, (pixels.height * .42f).toInt())
                val headphones = pixels.getRGB((pixels.width * .29f).toInt(), pixels.height / 2)
                assertEquals("Headphones should use the active accent", colors.primaryAccent.toArgb(), headphones)
                if (first) {
                    skin[avatar.id] = face
                    fingerprints += pixels.getRGB(0, 0, pixels.width, pixels.height, null, 0, pixels.width).contentHashCode()
                } else assertEquals("A theme change must preserve natural skin colors", skin[avatar.id], face)
            }
            val grid = File("build/reports/human-avatar-review/$name-grid.png")
            compose.onNodeWithTag("human_avatar_grid").captureRoboImage(grid.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        }
        capturePortraits("dark", first = true)
        assertEquals(9, fingerprints.size)
        compose.runOnIdle { colors = LightAppColors.copy(primaryAccent = Color(0xFFCA3B65), secondaryAccent = Color(0xFF723AE4)) }
        capturePortraits("light", first = false)
    }
}
