package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AvatarCropTransform
import com.example.ui.components.AvatarCropPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarCropPageUiTest {
    @get:Rule val compose = createComposeRule()

    private fun image(): Uri {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val encoded = try { ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray() } finally { bitmap.recycle() }
        return Uri.parse("content://avatar-crop/preview").also {
            shadowOf(context.contentResolver).registerInputStream(it, encoded.inputStream())
        }
    }

    @Test fun adjustingAndRotatingOnlySubmitsWhenConfirmed() {
        val uri = image()
        var submitted: AvatarCropTransform? = null
        var shown by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (shown) AvatarCropPage(uri, { shown = false }) { source, crop ->
                assertFalse(source.isRecycled)
                assertEquals(120, source.width)
                submitted = crop; shown = false
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("avatar_crop_viewport").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("avatar_crop_viewport").performTouchInput { swipeLeft() }
        assertNull(submitted)
        compose.onNodeWithTag("avatar_crop_rotate").assertIsDisplayed().performClick()
        compose.onNodeWithTag("avatar_crop_submit").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { submitted != null }
        assertEquals(1, submitted!!.quarterTurns)
        compose.onNodeWithTag("avatar_crop_page").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun landscapeKeepsTheCropAndBottomActionsVisibleAndCancelDoesNotSubmit() {
        val uri = image()
        var shown by mutableStateOf(true)
        var submitted = 0
        compose.setContent { MaterialTheme {
            if (shown) AvatarCropPage(uri, { shown = false }) { _, _ -> submitted++ }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("avatar_crop_viewport").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("avatar_crop_viewport").assertIsDisplayed()
        compose.onNodeWithTag("avatar_crop_rotate").assertIsDisplayed()
        compose.onNodeWithTag("avatar_crop_submit").assertIsDisplayed()
        compose.onNodeWithTag("avatar_crop_cancel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("avatar_crop_page").assertDoesNotExist()
        assertEquals(0, submitted)
    }
}
