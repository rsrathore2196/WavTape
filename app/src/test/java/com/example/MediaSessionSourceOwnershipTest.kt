package com.example

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.SessionCommand
import com.example.data.model.MusicTrack
import com.example.data.remote.StreamingAddons
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourceKind
import com.example.playback.PlaybackSourcePolicy
import com.example.service.MusicService
import com.example.service.PlaybackSessionStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
class MediaSessionSourceOwnershipTest {
    private fun item(uri: String) = MediaItem.Builder().setMediaId("library-track").setUri(uri).build()

    @Test fun browsersCanBrowseAndSearchWhileOnlyTheAppCanChangeAudioSettings() {
        val externalCommands = MusicService.librarySessionCommands(fromApp = false, trustedController = false)
        val browserCommands = MusicService.librarySessionCommands(fromApp = false, trustedController = true)
        val appCommands = MusicService.librarySessionCommands(fromApp = true, trustedController = false)
        for (code in listOf(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT,
            SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN, SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)) {
            assertFalse(externalCommands.contains(code))
            assertTrue(browserCommands.contains(code))
            assertTrue(appCommands.contains(code))
        }
        for (action in listOf(MusicService.COMMAND_SET_EQUALIZER, MusicService.COMMAND_SET_OUTPUT_DEVICE)) {
            val command = SessionCommand(action, android.os.Bundle.EMPTY)
            assertFalse(externalCommands.contains(command))
            assertFalse(browserCommands.contains(command))
            assertTrue(appCommands.contains(command))
        }
    }

    @Test fun deviceAndInternalAudioRequireAnAppOwnedItem() {
        for (uri in listOf("file:///music/song.flac", "content://media/song/1", "xtreme-offline://audio/song")) {
            assertFalse(MusicService.permitsControllerSource(item(uri), fromApp = false, trustedController = false))
            assertFalse(MusicService.permitsControllerSource(item(uri), fromApp = false, trustedController = true))
            assertTrue(MusicService.permitsControllerSource(item(uri), fromApp = true, trustedController = false))
        }
    }

    @Test fun externalClientsNeedMediaTrustForDirectNetworkStreams() {
        val remote = item("https://audio.example/song.m4a")
        assertFalse(MusicService.permitsControllerSource(remote, fromApp = false, trustedController = false))
        assertTrue(MusicService.permitsControllerSource(remote, fromApp = false, trustedController = true))
        assertTrue(MusicService.permitsControllerSource(remote, fromApp = true, trustedController = false))
    }

    @Test fun verifiedInlineAddonCanEnterTheAppSessionWithoutBeingPersistedOrOpenedExternally() {
        val base = "https://addon.example"
        val addon = PlaybackSource(PlaybackSource.addonId(base), PlaybackSourceKind.ADDON, "Addon", baseUrl = base)
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = listOf(addon))
        val track = MusicTrack("library-track", "Song", "Artist", "Album", 100_000, "", "")
        val result = checkNotNull(StreamingAddons.parseStream(
            """{"manifestXml":"<MPD><BaseURL>https://audio.example/</BaseURL></MPD>","codec":"flac"}""",
            base, track, track, policy, addon.id))
        val prepared = item(result.audioUrl).buildUpon().setMediaMetadata(MediaMetadata.Builder()
            .setExtras(android.os.Bundle().apply { putString("source_policy_key", policy.key) }).build()).build()
        assertTrue(MusicService.acceptsPreparedAppItem(prepared, policy))
        assertFalse(MusicService.acceptsPreparedAppItem(prepared, policy.copy(ceiling = AudioQualityCeiling.HIGH)))
        assertFalse(MusicService.permitsControllerSource(prepared, fromApp = false, trustedController = true))
        assertFalse(PlaybackSessionStore.isReal(prepared))
    }
}
