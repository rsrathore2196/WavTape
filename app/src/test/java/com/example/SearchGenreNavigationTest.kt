package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.MusicTrack
import com.example.data.repository.*
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LightAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.viewmodel.SearchUiState
import com.example.ui.viewmodel.CatalogSearchState
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchGenreNavigationTest {
    @get:Rule val compose = createComposeRule()

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
            LocalDynamicGlassEnabled provides false, content = block) }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port")
    fun searchControlsAlignAndKeepTheirActions() = checkSearchControls()
    @Test @Config(qualifiers = "w320dp-h740dp-port")
    fun narrowSearchControlsFitWithoutOverlapping() = checkSearchControls()
    @Test @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeSearchControlsMatchTheSearchField() = checkSearchControls()

    private fun checkSearchControls() {
        var colors by mutableStateOf(DarkAppColors.copy(dynamicGlassEnabled = false))
        var catalog by mutableStateOf(CatalogSearchState())
        var searched: String? = null
        var remembered: String? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors, LocalDynamicGlassEnabled provides false) {
                SearchScreen(SearchUiState(query = "track title"), onQueryChange = { searched = it },
                    onSelectGenre = {}, onTrackClick = { _, _ -> }, onToggleFavorite = {},
                    catalogState = catalog, onSelectType = { catalog = catalog.copy(type = it) },
                    onRememberSearch = { remembered = it })
            }
        } }
        val header = compose.onNodeWithTag("search_fixed_header").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        for (theme in listOf(DarkAppColors, LightAppColors)) {
            compose.runOnIdle { colors = theme.copy(dynamicGlassEnabled = false) }
            val field = compose.onNodeWithTag("search_query_input").fetchSemanticsNode().boundsInRoot
            val filters = listOf("all", "tracks", "artists", "albums").map { tag ->
                compose.onNodeWithTag("filter_pill_$tag").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            }
            assertEquals(field.left, filters.first().left, 1f)
            assertEquals(field.right, filters.last().right, 1f)
            assertTrue(filters.all { kotlin.math.abs(it.width - filters.first().width) <= 1f && it.top >= field.bottom })
            filters.zipWithNext().forEach { (left, right) -> assertTrue(left.right < right.left) }
            val action = compose.onNodeWithTag("finish_search_button").assertIsDisplayed()
                .assertWidthIsAtLeast(60.dp).assertHeightIsAtLeast(40.dp)
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width > bounds.height && bounds.left >= field.left && bounds.right <= field.right)
            compose.onNodeWithContentDescription("Search").assertIsDisplayed()
            action.performClick()
            compose.runOnIdle { assertEquals("track title", searched); assertEquals("track title", remembered) }
            listOf("Tracks", "Artists", "Albums", "All").forEach { type ->
                compose.onNodeWithTag("filter_pill_${type.lowercase()}").performClick()
                compose.runOnIdle { assertEquals(type, catalog.type) }
            }
        }
        compose.onNodeWithTag("search_screen").performTouchInput { swipeUp() }
        assertEquals(header, compose.onNodeWithTag("search_fixed_header").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("top_settings_button").assertDoesNotExist()
        compose.onNodeWithText("XTREME").assertDoesNotExist()
        compose.onNodeWithText("Search Online Music").assertIsDisplayed()
        val preview = java.io.File("build/reports/accent-adaptation/search-header.png").apply { parentFile.mkdirs() }
        compose.onRoot().captureRoboImage(preview.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))

    }

    @Test fun focusedHistoryUsesTenListEntriesAndKeepsSearchRemovalAndClearWorking() {
        var searched: String? = null
        var remembered: String? = null
        var removed: String? = null
        var state by mutableStateOf(SearchUiState(recentSearches = (1..25).map { "Query $it" }))
        content {
            SearchScreen(state, onQueryChange = { searched = it; state = state.copy(query = it) },
                onSelectGenre = {}, onTrackClick = { _, _ -> }, onToggleFavorite = {},
                onRememberSearch = { remembered = it },
                onRemoveRecentSearch = { removed = it; state = state.copy(recentSearches = state.recentSearches.filterNot { q -> q == it }) },
                onClearRecentSearches = { state = state.copy(recentSearches = emptyList()) })
        }
        compose.onNodeWithTag("recent_searches_list").assertDoesNotExist()
        compose.onNodeWithTag("search_query_input").performClick().assertIsFocused()
        val list = compose.onNodeWithTag("recent_searches_list").assertIsDisplayed()
        val field = compose.onNodeWithTag("search_query_input").fetchSemanticsNode().boundsInRoot
        val history = list.fetchSemanticsNode().boundsInRoot
        assertTrue(history.top >= field.bottom)
        assertEquals(field.left, history.left, 1f)
        assertEquals(field.right, history.right, 1f)
        list.performScrollToNode(hasTestTag("recent_search_item_Query 10"))
        compose.onNodeWithTag("recent_search_item_Query 10").assertIsDisplayed()
        compose.onNodeWithTag("recent_search_item_Query 11").assertDoesNotExist()
        compose.onNodeWithTag("recent_search_chip_Query 10").assertDoesNotExist()
        compose.onNodeWithTag("remove_recent_search_Query 10").performClick()
        compose.runOnIdle { assertEquals("Query 10", removed); assertNull(searched); assertNull(remembered) }
        compose.onNodeWithTag("search_query_input").assertIsFocused()
        compose.onNodeWithTag("recent_search_item_Query 10").assertDoesNotExist()
        list.performScrollToNode(hasTestTag("recent_search_item_Query 9"))
        compose.onNodeWithTag("recent_search_item_Query 9").performClick()
        compose.runOnIdle { assertEquals("Query 9", searched); assertEquals("Query 9", remembered) }
        list.assertDoesNotExist()
        compose.onNodeWithTag("search_query_input").performClick()
        // Existing text does not hide history while the field is active.
        list.assertIsDisplayed().performScrollToNode(hasTestTag("clear_recent_searches_button"))
        compose.onNodeWithTag("clear_recent_searches_button").performClick()
        list.assertDoesNotExist()
    }

    @Test fun historyIsShownWhileTypingAndHiddenWhenSearchTabIsInactive() = checkHistoryVisibility()

    @Test @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeHistoryFitsBelowTheFocusedField() = checkHistoryVisibility()

    private fun checkHistoryVisibility() {
        var state by mutableStateOf(SearchUiState(recentSearches = listOf("history_heading", "Second")))
        var active by mutableStateOf(true)
        content {
            SearchScreen(state, onQueryChange = { state = state.copy(query = it) },
                onSelectGenre = {}, onTrackClick = { _, _ -> }, onToggleFavorite = {}, isScreenActive = active)
        }
        compose.onNodeWithTag("search_query_input").performClick().performTextInput("mix")
        val field = compose.onNodeWithTag("search_query_input").fetchSemanticsNode().boundsInRoot
        val history = compose.onNodeWithTag("recent_searches_list").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("History stays below the focused field", history.top >= field.bottom)
        compose.onNodeWithTag("recent_search_item_Second").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("recent_searches_list").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Dismissing history preserves the typed query", "mix", state.query) }
        compose.onNodeWithTag("search_query_input").performClick()
        compose.runOnIdle { active = false }
        compose.onNodeWithTag("recent_searches_list").assertDoesNotExist()
        compose.runOnIdle { active = true }
        compose.onNodeWithTag("recent_searches_list").assertDoesNotExist()
        compose.onNodeWithTag("search_query_input").performClick()
        compose.onNodeWithTag("recent_searches_list").assertIsDisplayed()
        compose.onNodeWithTag("finish_search_button").performClick()
        compose.onNodeWithTag("recent_searches_list").assertDoesNotExist()
    }

    @Test fun genreOpensItsOwnPageWithPlayableSongsAlbumsAndBackNavigation() {
        val track = MusicTrack("yt_abcdefghijk", "Pop Song", "Artist", "Pop Album", 180000, "", "")
        val album = CatalogAlbum("pop_album", "Pop Album", listOf(CatalogArtist("artist", "Artist")), "", "2026")
        val results = SearchResultCategory(songs = listOf(track), catalogAlbums = listOf(album))
        val category = ExploreCategory("Pop", "FEmusic_pop", kind = ExploreCategoryKind.GENRE)
        var state by mutableStateOf(SearchUiState(exploreIndexReady = true, exploreCategories = listOf(category)))
        var played: MusicTrack? = null
        var queue: List<MusicTrack> = emptyList()
        var opened: CatalogDestination? = null
        content {
            SearchScreen(state, onQueryChange = {},
                onSelectGenre = { state = state.copy(selectedGenre = it, result = if (it == "All") SearchResultCategory() else results) },
                onTrackClick = { song, songs -> played = song; queue = songs }, onToggleFavorite = {},
                onOpenCatalog = { opened = it })
        }
        compose.onNodeWithTag("search_screen").performScrollToNode(hasTestTag("explore_chip_${category.key}"))
        compose.onNodeWithTag("explore_chip_${category.key}").performClick()
        compose.onNodeWithTag("genre_results_page").assertExists()
        compose.onNodeWithTag("search_screen").assertDoesNotExist()
        compose.onNodeWithText("Explore Genres").assertDoesNotExist()
        compose.onNodeWithText("Pop Song").performClick()
        compose.runOnIdle { assertEquals(track, played); assertEquals(listOf(track), queue) }
        compose.onNodeWithTag("genre_results_page").performScrollToNode(hasText("Pop Album"))
        compose.onNodeWithText("Pop Album").performClick()
        compose.runOnIdle { assertEquals(CatalogDestination.Album(album), opened) }
        compose.onNodeWithTag("back_to_all_genres_button").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("search_screen").assertExists()
        compose.onNodeWithTag("genre_results_page").assertDoesNotExist()
        compose.onNodeWithTag("explore_chip_${category.key}").assertIsDisplayed()
    }
}
