package com.example

import android.app.Application
import com.example.connections.SpotifyConnection
import com.example.data.model.SongIdentity
import com.example.data.remote.LyricsDocuments
import com.example.data.remote.LyricsSources
import com.example.data.remote.MusixmatchWebLyrics
import com.example.data.remote.SpotifyLyricsSource
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
class LyricsProviderWireTest {
    private val song = SongIdentity("yt_abcdefghijk", "Song & Light", "Singer", durationMs = 180_000)
    private val spotifyId = "0VjIjW4GlUZAMYd2vXMi3b"
    private fun trackRow(id: String, name: String, artist: String) = JSONObject().put("item",
        JSONObject().put("data", JSONObject().put("id", id).put("uri", "spotify:track:$id").put("name", name)
            .put("artists", JSONObject().put("items", JSONArray().put(JSONObject()
                .put("profile", JSONObject().put("name", artist)))))))

    private val spotifyLyrics = """
        {"syncType":"LINE_SYNCED","lines":[
          {"startTimeMs":"480","words":"Light in the distance"},
          {"startTimeMs":"6510","words":"A new day begins"}
        ]}
    """.trimIndent()

    @Test fun paxSenixSpotifyResolvesAnExactRecordingWithoutAConfiguredApiKey() = runBlocking<Unit> {
        val requests = mutableListOf<Request>()
        val source = LyricsSources(song, spotifySession = { null }, spotify = SpotifyLyricsSource(), directRequest = { req ->
            requests += req
            when (req.url.host) {
                "open.spotify.com" -> """
                    <script type="application/json" id="__NEXT_DATA__">
                      {"props":{"pageProps":{"state":{"settings":{"session":{
                        "accessToken":"anonymous-fixture","accessTokenExpirationTimestampMs":${System.currentTimeMillis() + 300_000},
                        "isAnonymous":true
                      }}}}}}
                    </script>
                """.trimIndent()
                "api-partner.spotify.com" -> {
                    val query = Buffer().also { requireNotNull(req.body).writeTo(it) }.readUtf8()
                    assertEquals("findTracks", JSONObject(query).getString("operationName"))
                    assertEquals("Bearer anonymous-fixture", req.header("Authorization"))
                    val rows = JSONArray().put(trackRow("62PaSfnHKt4V3QKsrcIHOR", "Song & Light (Live)", "Singer"))
                        .put(trackRow("4Q0qVhFQa7j6jRKzo3HDmP", song.title, "Different Artist"))
                        .put(trackRow(spotifyId, song.title, requireNotNull(song.artist)))
                    """{"data":{"searchV2":{"tracksV2":{"items":$rows}}}}"""
                }
                "spotify-lyrics-api-yeah.vercel.app" -> {
                    assertEquals(spotifyId, req.url.queryParameter("trackId"))
                    assertNull(req.header("Authorization"))
                    assertNull(req.header("Cookie"))
                    spotifyLyrics
                }
                else -> error("Unexpected request to ${req.url.host}")
            }
        })
        val result = source.lookup(null, "PaxSenix Spotify") {}
        assertEquals("PaxSenix Spotify", result?.provider)
        assertEquals(listOf(480L, 6510L), result?.lines?.map { it.timestampMs })
        assertEquals(3, requests.size)
    }

    @Test fun anExistingSpotifySessionReadsFirstPartyLyricsWithoutSendingTheCookie() = runBlocking<Unit> {
        val spotifyTrack = song.copy(trackId = "spotify:track:$spotifyId")
        val source = LyricsSources(spotifyTrack, spotify = SpotifyLyricsSource(),
            spotifySession = { refreshExpired ->
                assertTrue(refreshExpired)
                SpotifyConnection("cookie-fixture", "session-fixture", Long.MAX_VALUE, clientToken = "client-fixture")
            },
            directRequest = { req ->
                assertEquals("spclient.wg.spotify.com", req.url.host)
                assertEquals(spotifyId, req.url.pathSegments.last())
                assertEquals("Bearer session-fixture", req.header("Authorization"))
                assertEquals("client-fixture", req.header("Client-Token"))
                assertNull(req.header("Cookie"))
                """{"lyrics":{"syncType":"UNSYNCED","lines":[{"startTimeMs":"0","words":"A new day begins"}]}}"""
            })
        val result = source.lookup(null, "PaxSenix Spotify") {}
        assertEquals(false, result?.isSynced)
        assertEquals("A new day begins", result?.lines?.single()?.text)
    }

    @Test fun musixmatchSignsCanonicalQueriesAndRetainsWordTimingsWithoutAnApiKey() = runBlocking<Unit> {
        val requests = mutableListOf<Request>()
        val web = MusixmatchWebLyrics(today = { LocalDate.of(2026, 11, 22) })
        val source = LyricsSources(song, musixmatchWeb = web, directRequest = { req ->
            requests += req
            when (req.url.pathSegments.last()) {
                "search" -> """<script src="https://s.mxmcdn.net/site/js/_next/static/chunks/pages/_app-example.js"></script>"""
                "_app-example.js" -> """Buffer.from("lJXd0hXam1iYldXLjlGbiVHc".split("").reverse().join(""),"base64")"""
                "track.search" -> {
                    assertEquals("Song & Light Singer", req.url.queryParameter("q"))
                    assertTrue(req.url.encodedQuery.orEmpty().contains("q=Song+%26+Light+Singer"))
                    assertEquals("4WjzntksLlNC5ThyIbri29t5y4XJECeGPPbdpcsGQNI=", req.url.queryParameter("signature"))
                    assertNull(req.url.queryParameter("apikey"))
                    """{"message":{"header":{"status_code":200},"body":{"track_list":[
                       {"track":{"track_id":1,"track_name":"Song & Light","artist_name":"Singer","track_length":243}},
                       {"track":{"track_id":23,"track_name":"Song & Light","artist_name":"Singer","track_length":180}}
                    ]}}}"""
                }
                "track.richsync.get" -> {
                    assertEquals("23", req.url.queryParameter("track_id"))
                    assertEquals("sha256", req.url.queryParameter("signature_protocol"))
                    val rich = JSONArray("""[{"ts":1.2,"te":2.6,"l":[{"c":"Twilight ","o":0.0},{"c":"glows","o":0.5}]}]""")
                    """{"message":{"header":{"status_code":200},"body":{"richsync":{"richsync_body":${JSONObject.quote(rich.toString())}}}}}"""
                }
                else -> error("Unexpected request to ${req.url}")
            }
        })
        val lyrics = source.lookup(null, "Musixmatch") {}
        assertEquals("Musixmatch", lyrics?.provider)
        assertEquals(listOf(1200L, 1700L), lyrics?.lines?.single()?.words?.map { it.timestampMs })
        assertEquals(true, lyrics?.hasWordTiming)
        assertEquals(4, requests.size)
    }

    @Test fun musixmatchSkipsUntrustedScriptsAndDoesNotRequestUnmatchedLyrics() = runBlocking<Unit> {
        val web = MusixmatchWebLyrics()
        val matches: (String, String, Double) -> Boolean = { title, artist, _ ->
            LyricsDocuments.identityMatches(song, title, artist)
        }
        assertNull(web.lookup(song, "Song & Light Singer", matches) { req ->
            assertEquals("www.musixmatch.com", req.url.host)
            """<script src="https://mxm.example/_next/static/chunks/pages/_app-example.js"></script>"""
        })
        assertNull(web.lookup(song, "Song & Light Singer", matches) { req ->
            when (req.url.pathSegments.last()) {
                "search" -> """<script src="/_next/static/chunks/pages/_app-example.js"></script>"""
                "_app-example.js" -> """Buffer.from("lJXd0hXam1iYldXLjlGbiVHc".split("").reverse().join(""), "base64")"""
                "track.search" -> """{"message":{"header":{"status_code":200},"body":{"track_list":[
                    {"track":{"track_id":2,"track_name":"Song & Light","artist_name":"Different Artist","track_length":180}}
                ]}}}"""
                else -> error("A failed match cannot request lyrics")
            }
        })
    }
}
