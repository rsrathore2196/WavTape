package com.example

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.service.SessionArtworkLoader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionArtworkLoaderTest {
    @Test fun sessionArtworkUsesASharperRenditionOfTheSameCoverWithTheOriginalAsFallback() {
        val small = Uri.parse("https://lh3.googleusercontent.com/album-id=w60-h60-l90-rj?token=original")
        val large = Uri.parse("https://lh3.googleusercontent.com/album-id=w1024-h1024-l90-rj?token=original")
        assertEquals(listOf(large, small), SessionArtworkLoader.artworkCandidates(small))
        assertEquals(listOf(large), SessionArtworkLoader.artworkCandidates(large))
        val videoCover = Uri.parse("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg?sqp=small&rs=resize")
        val highResolution = Uri.parse("https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg")
        assertEquals(listOf(highResolution, videoCover), SessionArtworkLoader.artworkCandidates(videoCover))
    }

    @Test fun localAndUnrecognizedCoversKeepTheirOriginalUris() {
        for (uri in listOf("content://media/external/audio/albumart/1", "file:///cover.jpg",
            "https://music.example/120x120.jpg?signature=keep")) {
            assertEquals(listOf(Uri.parse(uri)), SessionArtworkLoader.artworkCandidates(Uri.parse(uri)))
        }
    }
}
