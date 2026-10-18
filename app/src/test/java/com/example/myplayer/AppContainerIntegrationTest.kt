package com.example.myplayer

import com.example.myplayer.di.AppContainer
import com.example.myplayer.dualbud.ui.DualBudViewModel
import com.example.myplayer.sync.ui.SyncPlayViewModel
import com.example.myplayer.ui.screens.downloads.DownloadsViewModel
import com.example.myplayer.ui.screens.home.HomeViewModel
import com.example.myplayer.ui.screens.library.LibraryViewModel
import com.example.myplayer.ui.screens.library.PlaylistDetailViewModel
import com.example.myplayer.ui.screens.main.MainViewModel
import com.example.myplayer.ui.screens.nowplaying.LyricsViewModel
import com.example.myplayer.ui.screens.recommendation.RecommendationViewModel
import com.example.myplayer.ui.screens.search.OnlineSearchViewModel
import com.example.myplayer.ui.screens.search.SearchViewModel
import com.example.myplayer.ui.screens.settings.SettingsViewModel
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test

class AppContainerIntegrationTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            AppContainer.initialize()
        }
    }

    @Test
    fun testAllViewModelsInstantiateWithoutError() {
        val mainVm = AppContainer.getViewModel(MainViewModel::class.java)
        assertNotNull("MainViewModel should not be null", mainVm)

        val homeVm = AppContainer.getViewModel(HomeViewModel::class.java)
        assertNotNull("HomeViewModel should not be null", homeVm)

        val libraryVm = AppContainer.getViewModel(LibraryViewModel::class.java)
        assertNotNull("LibraryViewModel should not be null", libraryVm)

        val playlistDetailVm = AppContainer.getViewModel(PlaylistDetailViewModel::class.java)
        assertNotNull("PlaylistDetailViewModel should not be null", playlistDetailVm)

        val searchVm = AppContainer.getViewModel(SearchViewModel::class.java)
        assertNotNull("SearchViewModel should not be null", searchVm)

        val onlineSearchVm = AppContainer.getViewModel(OnlineSearchViewModel::class.java)
        assertNotNull("OnlineSearchViewModel should not be null", onlineSearchVm)

        val lyricsVm = AppContainer.getViewModel(LyricsViewModel::class.java)
        assertNotNull("LyricsViewModel should not be null", lyricsVm)

        val recommendationVm = AppContainer.getViewModel(RecommendationViewModel::class.java)
        assertNotNull("RecommendationViewModel should not be null", recommendationVm)

        val settingsVm = AppContainer.getViewModel(SettingsViewModel::class.java)
        assertNotNull("SettingsViewModel should not be null", settingsVm)

        val downloadsVm = AppContainer.getViewModel(DownloadsViewModel::class.java)
        assertNotNull("DownloadsViewModel should not be null", downloadsVm)

        val syncPlayVm = AppContainer.getViewModel(SyncPlayViewModel::class.java)
        assertNotNull("SyncPlayViewModel should not be null", syncPlayVm)

        val dualBudVm = AppContainer.getViewModel(DualBudViewModel::class.java)
        assertNotNull("DualBudViewModel should not be null", dualBudVm)

        println("[AppContainerIntegrationTest] All 12 ViewModels successfully instantiated!")
    }
}
