package com.example

import android.app.Application
import com.example.data.remote.YouTubeArtistChannelResolver
import com.example.data.repository.CatalogArtist
import com.example.data.repository.CatalogArtistProfile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class YouTubeArtistChannelResolverTest {

    @Before
    fun resetCache() {
        YouTubeArtistChannelResolver.clearCache()
    }

    private fun webChannelBrowse(
        externalId: String,
        title: String,
        description: String = "Official artist channel",
        vanityUrl: String = "",
        officialBadge: Boolean = false,
        topicHeader: Boolean = false
    ): String {
        val header = JSONObject().apply {
            if (topicHeader) {
                put("topicChannelDetailsRenderer", JSONObject().put("title", JSONObject().put("simpleText", title)))
            } else {
                val badges = JSONArray()
                if (officialBadge) {
                    badges.put(JSONObject().put("metadataBadgeRenderer", JSONObject()
                        .put("style", "BADGE_STYLE_TYPE_VERIFIED_ARTIST")
                        .put("tooltip", "Official Artist Channel")))
                }
                put("c4TabbedHeaderRenderer", JSONObject()
                    .put("title", title)
                    .put("badges", badges))
            }
        }
        val metadata = JSONObject().put("channelMetadataRenderer", JSONObject()
            .put("title", title)
            .put("description", description)
            .put("externalId", externalId)
            .put("channelUrl", "https://www.youtube.com/channel/$externalId")
            .put("vanityChannelUrl", vanityUrl))
        return JSONObject()
            .put("header", header)
            .put("metadata", metadata)
            .toString()
    }

    private fun channelSearchResult(vararg channels: JSONObject): String {
        val items = JSONArray()
        channels.forEach { items.put(JSONObject().put("channelRenderer", it)) }
        return JSONObject().put("contents", JSONObject()
            .put("twoColumnSearchResultsRenderer", JSONObject()
                .put("primaryContents", JSONObject()
                    .put("sectionListRenderer", JSONObject()
                        .put("contents", JSONArray().put(JSONObject()
                            .put("itemSectionRenderer", JSONObject().put("contents", items))))))))
            .toString()
    }

    private fun channelItem(
        channelId: String,
        title: String,
        subText: String = "",
        videoText: String = "",
        canonicalBaseUrl: String = "/channel/$channelId",
        description: String = "",
        badgeStyle: String? = null
    ): JSONObject = JSONObject()
        .put("channelId", channelId)
        .put("title", JSONObject().put("simpleText", title))
        .put("subscriberCountText", JSONObject().put("simpleText", subText))
        .put("videoCountText", JSONObject().put("simpleText", videoText))
        .put("descriptionSnippet", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", description))))
        .put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject()
            .put("browseId", channelId)
            .put("canonicalBaseUrl", canonicalBaseUrl)))
        .apply {
            if (badgeStyle != null) {
                put("ownerBadges", JSONArray().put(JSONObject().put("metadataBadgeRenderer", JSONObject()
                    .put("style", badgeStyle))))
            }
        }

    @Test
    fun subscribeButtonOfficialChannelIdBeatsTopicBrowseId() = runBlocking {
        val topicBrowseId = "UCPC0L1d253x-KuDuO055eYg"
        val officialChannelId = "UCqECaJ8Gagnn7YCbPEzWH6g"
        val artist = CatalogArtist(topicBrowseId, "Taylor Swift")
        val profile = CatalogArtistProfile(artist, youtubeChannelId = officialChannelId)

        val browsed = mutableListOf<String>()
        val url = YouTubeArtistChannelResolver.resolveArtistYouTubeUrl(
            artist = artist,
            profile = profile,
            browseWeb = { id ->
                browsed += id
                when (id) {
                    officialChannelId -> webChannelBrowse(
                        externalId = officialChannelId,
                        title = "Taylor Swift",
                        vanityUrl = "http://www.youtube.com/@TaylorSwift",
                        officialBadge = true
                    )
                    else -> webChannelBrowse(
                        externalId = id,
                        title = "Taylor Swift - Topic",
                        description = "Auto-generated by YouTube.",
                        topicHeader = true
                    )
                }
            },
            searchWeb = { error("Search should not run when subscribe channelId is already verified") }
        )

        assertEquals(listOf(officialChannelId), browsed)
        assertEquals("https://www.youtube.com/channel/$officialChannelId", url)
    }

    @Test
    fun topicBrowseChannelIsRejectedAndResolvedToOfficialArtistChannelViaSearch() = runBlocking {
        val topicId = "UCtopicArijit123456"
        val realChannelId = "UCtqm6G6tM9d6y9H1h8Xreal"
        val artist = CatalogArtist(topicId, "Arijit Singh")

        val url = YouTubeArtistChannelResolver.resolveArtistYouTubeUrl(
            artist = artist,
            profile = CatalogArtistProfile(artist, youtubeChannelId = topicId),
            browseWeb = { id ->
                webChannelBrowse(
                    externalId = id,
                    title = "Arijit Singh - Topic",
                    description = "Provided to YouTube by T-Series. Auto-generated by YouTube.",
                    topicHeader = true
                )
            },
            searchWeb = {
                channelSearchResult(
                    channelItem(
                        channelId = topicId,
                        title = "Arijit Singh - Topic",
                        description = "Auto-generated by YouTube."
                    ),
                    channelItem(
                        channelId = "UCfanStatusChannel111",
                        title = "Arijit Singh Status",
                        subText = "@arijitsinghstatus",
                        videoText = "2.5M subscribers",
                        badgeStyle = "BADGE_STYLE_TYPE_VERIFIED"
                    ),
                    channelItem(
                        channelId = "UCimpersonatorSmall222",
                        title = "Arijit Singh",
                        subText = "@arijitsingh999",
                        videoText = "120 subscribers"
                    ),
                    channelItem(
                        channelId = realChannelId,
                        title = "Arijit Singh",
                        subText = "@arijitsingh",
                        videoText = "15.2M subscribers",
                        canonicalBaseUrl = "/@arijitsingh",
                        badgeStyle = "BADGE_STYLE_TYPE_VERIFIED_ARTIST"
                    )
                )
            }
        )

        assertEquals("https://www.youtube.com/channel/$realChannelId", url)
    }

    @Test
    fun artistActuallyNamedTopicResolvesRealChannelAndRejectsTopicSuffix() = runBlocking {
        val topicGeneratedId = "UCtopicAutoGen12345"
        val realTopicDjId = "UCrealDjTopic123456"
        val artist = CatalogArtist(topicGeneratedId, "Topic")

        val url = YouTubeArtistChannelResolver.resolveArtistYouTubeUrl(
            artist = artist,
            browseWeb = { id ->
                webChannelBrowse(
                    externalId = id,
                    title = "Topic - Topic",
                    description = "Auto-generated by YouTube."
                )
            },
            searchWeb = {
                channelSearchResult(
                    channelItem(
                        channelId = topicGeneratedId,
                        title = "Topic - Topic",
                        description = "Auto-generated by YouTube."
                    ),
                    channelItem(
                        channelId = realTopicDjId,
                        title = "Topic",
                        subText = "@TopicMusic",
                        videoText = "920K subscribers",
                        canonicalBaseUrl = "/@TopicMusic",
                        badgeStyle = "BADGE_STYLE_TYPE_VERIFIED_ARTIST"
                    )
                )
            }
        )

        assertEquals("https://www.youtube.com/channel/$realTopicDjId", url)
    }

    @Test
    fun nonYouTubeArtistIdResolvesDirectlyViaChannelSearchAndFallsBackSafelyWhenNoneFound() = runBlocking {
        val realChannelId = "UCZRdNleCgW--BGUJf5UMPOQ"
        val diljit = CatalogArtist("jiosaavn_99", "Diljit Dosanjh")

        val resolved = YouTubeArtistChannelResolver.resolveArtistYouTubeUrl(
            artist = diljit,
            browseWeb = { error("Non-UC IDs must not be browsed on YouTube") },
            searchWeb = {
                channelSearchResult(
                    channelItem(
                        channelId = "UCdiljitLyrics9999",
                        title = "Diljit Dosanjh Lyrics",
                        videoText = "500K subscribers"
                    ),
                    channelItem(
                        channelId = realChannelId,
                        title = "Diljit Dosanjh",
                        subText = "@diljitdosanjh",
                        videoText = "8.1M subscribers",
                        canonicalBaseUrl = "/@diljitdosanjh",
                        badgeStyle = "BADGE_STYLE_TYPE_VERIFIED_ARTIST"
                    )
                )
            }
        )
        assertEquals("https://www.youtube.com/channel/$realChannelId", resolved)

        val obscureArtist = CatalogArtist("UCtopicOnlyObscure9", "Obscure Indie Artist")
        val fallback = YouTubeArtistChannelResolver.resolveArtistYouTubeUrl(
            artist = obscureArtist,
            browseWeb = { id ->
                webChannelBrowse(
                    externalId = id,
                    title = "Obscure Indie Artist - Topic",
                    description = "Auto-generated by YouTube.",
                    topicHeader = true
                )
            },
            searchWeb = {
                channelSearchResult(
                    channelItem(
                        channelId = "UCtopicOnlyObscure9",
                        title = "Obscure Indie Artist - Topic",
                        description = "Auto-generated by YouTube."
                    ),
                    channelItem(
                        channelId = "UCunrelatedRandom99",
                        title = "Random Channel",
                        videoText = "1M subscribers"
                    )
                )
            }
        )
        assertEquals(
            "https://www.youtube.com/results?search_query=Obscure%20Indie%20Artist",
            fallback
        )
    }
}
