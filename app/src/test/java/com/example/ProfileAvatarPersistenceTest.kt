package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileAvatarPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun selectedIllustrationAndPersonalImageSurvivePreferenceReadsAndPublishWithTheProfile() {
        val before = UserProfilePreferences.getUserProfile(context)
        try {
            val selected = UserProfile(name = "Listener", avatarId = "radio", avatarUri = "content://photos/personal")
            UserProfilePreferences.saveUserProfile(context, selected)
            assertEquals(selected, UserProfilePreferences.getUserProfile(context))
            assertEquals(selected, UserProfilePreferences.getUserProfileFlow(context).value)
            val illustration = selected.copy(avatarId = "piano", avatarUri = "")
            UserProfilePreferences.saveUserProfile(context, illustration)
            assertEquals(illustration, UserProfilePreferences.getUserProfile(context))
        } finally { UserProfilePreferences.saveUserProfile(context, before) }
    }

    @Test fun restoredAvatarsPersistAndOlderBackupsPreserveTheExistingAvatar() = runBlocking {
        val before = UserProfilePreferences.getUserProfile(context)
        val database = MusicDatabase.createInMemoryDatabase(context)
        try {
            val selected = UserProfile(name = "Listener", avatarId = "vinyl", avatarUri = "content://photos/existing")
            UserProfilePreferences.saveUserProfile(context, selected)
            val profile = JSONObject().put("name", "Restored").put("country", "India").put("countryCode", "IN")
                .put("languages", org.json.JSONArray().put("English"))
            fun backup() = JSONObject().put("app", "Xtreme Player").put("userProfile", profile).toString()
            assertTrue(BackupRestoreManager.restoreBackup(context, database, rawJson = backup()).success)
            assertEquals("vinyl", UserProfilePreferences.getUserProfile(context).avatarId)
            assertEquals(selected.avatarUri, UserProfilePreferences.getUserProfile(context).avatarUri)
            profile.put("avatarId", "speaker").put("avatarUri", "")
            assertTrue(BackupRestoreManager.restoreBackup(context, database, rawJson = backup()).success)
            assertEquals("speaker", UserProfilePreferences.getUserProfile(context).avatarId)
            assertEquals("", UserProfilePreferences.getUserProfile(context).avatarUri)
        } finally { database.close(); UserProfilePreferences.saveUserProfile(context, before) }
    }
}
