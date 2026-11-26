package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.remote.ArchiveFileEvidence
import com.example.data.remote.ArchiveFileKey
import com.example.data.remote.ArchiveFileTransport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchiveFileTransportTest {
    @Test fun encodedFileIdentitySurvivesTrustedRedirectButRejectsSubstitution() {
        val key = ArchiveFileKey.create("album", "disc 1/Porch Light (Live) %2F.flac")!!
        assertEquals(key, ArchiveFileKey.fromId(key.id))
        assertEquals(key, ArchiveFileKey.fromUrl(key.url))
        val mirror = "https://ia601406.us.archive.org/34/items/album/disc%201/Porch%20Light%20%28Live%29%20%252F.flac"
        assertEquals(key, ArchiveFileTransport.redirectTarget(key, key.url, mirror)?.let(ArchiveFileKey::fromUrl))
        assertNull(ArchiveFileTransport.redirectTarget(key, key.url, mirror.replace("https:", "http:")))
        assertNull(ArchiveFileTransport.redirectTarget(key, key.url, mirror.replace(".archive.org", ".archive.org.example.com")))
        assertNull(ArchiveFileTransport.redirectTarget(key, key.url, mirror.replace("/album/", "/other/")))
        assertNull(ArchiveFileTransport.redirectTarget(key, key.url, mirror.replace("Light", "Night")))
        assertNull(ArchiveFileKey.fromUrl("https://archive.org/download/album/dir%2Fsong.flac"))
        assertNull(ArchiveFileKey.create("album", "../song.flac"))
        assertNull(ArchiveFileKey.fromUrl("https://username@archive.org/download/album/song.flac"))
        assertEquals("album", ArchiveFileKey.itemLink("https://archive.org/details/album"))
    }

    @Test fun rangesAreBoundedAndSequentialResponsesStayAtZero() {
        val range = ArchiveFileTransport::responseLength
        assertEquals(4096L, range(206, "bytes 1048576-1052671/27796450", 4096, 1048576, 4096))
        assertEquals(100L, range(206, "bytes 900-999/1000", 100, 900, 4096))
        assertEquals(262144L, range(200, null, 27796450, 0, 262144))
        assertEquals(-1L, range(200, null, -1, 0, -1))
        val wrongPosition = assertThrows(ArchiveFileTransport.Failure::class.java) {
            range(206, "bytes 5-104/1000", 100, 10, 100)
        }
        assertEquals("range_unavailable", wrongPosition.outcome)
        assertThrows(ArchiveFileTransport.Failure::class.java) { range(200, null, 27796450, 1048576, 4096) }
        assertThrows(ArchiveFileTransport.Failure::class.java) { range(206, "bytes 0-10/500", 12, 0, 11) }
        assertEquals("license_denied", assertThrows(ArchiveFileTransport.Failure::class.java) {
            range(403, null, 0, 0, 8192)
        }.outcome)
        assertEquals("http_unavailable", assertThrows(ArchiveFileTransport.Failure::class.java) {
            range(404, null, 0, 0, 8192)
        }.outcome)
        assertEquals("rate_limited", assertThrows(ArchiveFileTransport.Failure::class.java) {
            range(429, null, 0, 0, 8192)
        }.outcome)
    }

    @Test fun archiveUpgradeIsrcComesFromTheFileAndOtherProvidersKeepTheOriginalRule() {
        val key = ArchiveFileKey.create("album_isrc", "song.flac")!!
        val catalog = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180_000, "", key.url.toString(),
            source = "INTERNET_ARCHIVE", isrc = "USABC2000001")
        try {
            assertNull(ArchiveFileEvidence.verifiedReplacementIsrc(catalog))
            ArchiveFileEvidence.remember(key, catalog.copy(id = key.id, isrc = "USABC2000002"))
            assertEquals("USABC2000002", ArchiveFileEvidence.verifiedReplacementIsrc(catalog))
            for ((provider, url) in listOf(
                "YouTube Music" to "https://www.youtube.com/watch?v=abcdefghijk",
                "JIOSAAVN" to "https://aac.saavncdn.com/song_320.mp4",
                "Streaming Addon: Qobuz" to "https://qobuz.example/audio.flac",
                "Streaming Addon: Tidal" to "https://tidal.example/audio.mpd",
                "Streaming Addon: Deezer" to "https://deezer.example/audio.flac",
                "Streaming Addon: Other" to key.url.toString()
            )) {
                assertEquals(provider, "USABC2000001",
                    ArchiveFileEvidence.verifiedReplacementIsrc(catalog.copy(source = provider, audioUrl = url)))
            }
        } finally { ArchiveFileEvidence.forget(key) }
    }
    @Test fun archiveDiscoveryIgnoresAtmosAndStillRequiresTheActiveArchivePolicy() {
        val policy = com.example.playback.PlaybackSourcePolicy(com.example.playback.AudioQualityCeiling.MAX)
        val active = policy.copy(dolbyAtmos = true)
        assertNotEquals(policy.key, active.key)
        assertTrue(com.example.data.remote.archiveCatalogPolicyAllows(policy, active))
        assertFalse(com.example.data.remote.archiveCatalogPolicyAllows(policy, active.copy(online = false)))
        assertFalse(com.example.data.remote.archiveCatalogPolicyAllows(policy,
            active.copy(ceiling = com.example.playback.AudioQualityCeiling.HIGH)))
        assertFalse(com.example.data.remote.archiveCatalogPolicyAllows(policy,
            active.copy(sources = active.sources.filter { it.kind != com.example.playback.PlaybackSourceKind.INTERNET_ARCHIVE })))
    }

}
