package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.UserProfile
import com.example.ui.components.EditProfileSheet
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

@OptIn(ExperimentalRoborazziApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditProfileSheetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closeDiscardsUnsavedEditsEvenWhenTheFormIsInvalid() {
        var shown by mutableStateOf(true)
        var dismissed = 0
        var saved: UserProfile? = null
        compose.setContent { MaterialTheme {
            if (shown) EditProfileSheet(UserProfile(name = "Listener", languages = listOf("English")),
                onDismiss = { dismissed++; shown = false }, onSave = { saved = it })
        } }
        compose.onNodeWithTag("edit_profile_name_input").performTextReplacement("")
        compose.onNodeWithTag("save_profile_button").assertIsNotEnabled()
        compose.onNodeWithTag("edit_profile_close").assertHasClickAction().performClick()
        compose.onNodeWithTag("edit_profile_sheet").assertDoesNotExist()
        compose.runOnIdle { assertNull(saved); assertEquals(1, dismissed) }
    }

    @Test fun avatarPickerShowsNineChoicesAndSavesOnlyTheSelectedDraft() {
        var saved: UserProfile? = null
        compose.setContent { MaterialTheme {
            EditProfileSheet(UserProfile(name = "Listener", avatarUri = "content://previous/photo"),
                onDismiss = {}, onSave = { saved = it })
        } }
        compose.onNodeWithTag("edit_profile_avatar").performScrollTo().performClick()
        for (avatar in com.example.ui.components.DefaultProfileAvatars) {
            compose.onNodeWithTag("avatar_option_${avatar.id}").assertIsDisplayed().assertHasClickAction()
        }
        compose.onNodeWithTag("avatar_choose_image").assertIsDisplayed().assertHasClickAction()
        capturePicker()
        compose.onNodeWithTag("avatar_option_vinyl").performClick()
        compose.onNodeWithTag("avatar_picker").assertDoesNotExist()
        assertNull(saved)
        compose.onNodeWithTag("save_profile_button").performClick()
        assertEquals("vinyl", saved?.avatarId)
        assertEquals("", saved?.avatarUri)
    }

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun compactAvatarPickerKeepsTheLastChoicesAndGalleryReachableInLandscape() {
        var gallery = 0
        compose.setContent { MaterialTheme {
            com.example.ui.components.AvatarPickerDialog("headphones", false, {}, { gallery++ }, {})
        } }
        compose.onNodeWithTag("avatar_option_dj").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("avatar_choose_image").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, gallery)
    }

    @Test fun galleryPickerStoresAPrivateCopyOfTheSelectedImageInTheSavedProfile() {
        var saved: UserProfile? = null
        val image = android.net.Uri.parse("content://avatar-test/personal-image")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888)
        val encoded = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(image, encoded.inputStream())
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                input: I, options: androidx.core.app.ActivityOptionsCompat?) {
                assertTrue(contract is androidx.activity.result.contract.ActivityResultContracts.OpenDocument)
                assertEquals(listOf("image/*"), (input as Array<*>).toList())
                dispatchResult(requestCode, android.app.Activity.RESULT_OK, android.content.Intent().setData(image))
            }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                EditProfileSheet(UserProfile(name = "Listener"), onDismiss = {}, onSave = { saved = it })
            }
        } }
        compose.onNodeWithTag("edit_profile_avatar").performScrollTo().performClick()
        compose.onNodeWithTag("avatar_choose_image").performClick()
        compose.onNodeWithTag("avatar_crop_page").assertIsDisplayed()
        compose.onNodeWithTag("save_profile_button").assertIsNotEnabled()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("avatar_crop_viewport").fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(saved)
        compose.onNodeWithTag("avatar_crop_submit").assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            !compose.onNodeWithTag("save_profile_button").fetchSemanticsNode().config
                .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag("save_profile_button").performClick()
        assertTrue(saved?.avatarUri?.startsWith("app-avatar://") == true)
        val copy = com.example.data.local.AvatarImageStore.imageModel(context, saved!!.avatarUri) as java.io.File
        assertTrue(copy.isFile)
        copy.delete()
    }

    @Test fun dismissingAvatarPickerKeepsTheExistingAvatarAndUnsavedProfile() {
        var saved: UserProfile? = null
        compose.setContent { MaterialTheme {
            EditProfileSheet(UserProfile(name = "Listener", avatarId = "radio"),
                onDismiss = {}, onSave = { saved = it })
        } }
        compose.onNodeWithTag("edit_profile_avatar").performScrollTo().performClick()
        val chooser = compose.onNodeWithTag("avatar_picker").fetchSemanticsNode()
        compose.runOnIdle {
            // Back belongs to the focused dialog, not the Activity behind the sheet.
            val view = (chooser.root as androidx.compose.ui.platform.ViewRootForTest).view
            val window = generateSequence(view as android.view.View?) { it.parent as? android.view.View }
                .filterIsInstance<androidx.compose.ui.window.DialogWindowProvider>().first().window
            (window.callback as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("avatar_picker").assertDoesNotExist()
        compose.onNodeWithTag("edit_profile_sheet").assertIsDisplayed()
        assertNull(saved)
        compose.onNodeWithTag("save_profile_button").performClick()
        assertEquals("radio", saved?.avatarId)
    }

    @Test fun backKeepsUnsavedEditsAndOnlySaveSubmits() {
        var dismissed = 0
        var saved: UserProfile? = null
        compose.setContent { MaterialTheme {
            EditProfileSheet(UserProfile(name = "Listener", languages = listOf("English")),
                onDismiss = { dismissed++ }, onSave = { saved = it })
        } }
        compose.onNodeWithTag("edit_profile_name_input").performTextReplacement("Draft")
        androidx.test.espresso.Espresso.pressBack()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("edit_profile_sheet").assertIsDisplayed()
        compose.onNodeWithTag("edit_profile_name_input").assertTextContains("Draft")
        compose.runOnIdle { assertNull(saved); assertEquals(0, dismissed) }
        compose.onNodeWithTag("save_profile_button").performClick()
        compose.runOnIdle { assertEquals("Draft", saved?.name); assertEquals(1, dismissed) }
    }

    @Test fun saveStaysVisibleAndInPlaceWhileTheFormScrollsAndSavesCurrentValues() {
        var saved: UserProfile? = null
        val profile = UserProfile(name = "Listener", languages = listOf("English"), countryCode = "IN", country = "India")
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors) {
                EditProfileSheet(profile, onDismiss = {}, onSave = { saved = it })
            }
        } }
        val button = compose.onNodeWithTag("save_profile_button").assertIsDisplayed().assertIsEnabled()
        val initialBounds = button.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("edit_profile_name_input").performTextReplacement("Updated Listener")
        compose.onNodeWithTag("edit_profile_fields").performScrollToNode(hasText("Country (Regional Charts)"))
        button.assertIsDisplayed()
        assertEquals(initialBounds, button.fetchSemanticsNode().boundsInRoot)
        button.performClick()
        compose.runOnIdle {
            assertEquals("Updated Listener", saved?.name)
            assertEquals(profile.languages, saved?.languages)
            assertEquals("IN", saved?.countryCode)
        }
    }
    private fun capturePicker() {
        val node = compose.onNodeWithTag("avatar_picker").fetchSemanticsNode()
        lateinit var bitmap: android.graphics.Bitmap
        compose.runOnIdle {
            val view = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
            val root = view.rootView
            val full = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(full))
            val bounds = node.boundsInRoot
            val location = IntArray(2).also(view::getLocationInWindow)
            val origin = IntArray(2).also(root::getLocationInWindow)
            bitmap = android.graphics.Bitmap.createBitmap(full,
                bounds.left.toInt() + location[0] - origin[0], bounds.top.toInt() + location[1] - origin[1],
                bounds.width.toInt(), bounds.height.toInt())
        }
        val preview = java.io.File("build/reports/profile-avatar-review/picker.png").apply { parentFile.mkdirs() }
        bitmap.captureRoboImage(preview.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

}
