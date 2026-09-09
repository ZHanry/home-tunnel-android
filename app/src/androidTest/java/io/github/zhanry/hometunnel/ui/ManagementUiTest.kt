package io.github.zhanry.hometunnel.ui

import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhanry.hometunnel.R
import io.github.zhanry.hometunnel.model.ManagedDevice
import io.github.zhanry.hometunnel.model.PersistedState
import io.github.zhanry.hometunnel.model.ServerProfile
import io.github.zhanry.hometunnel.model.TunnelConnection
import io.github.zhanry.hometunnel.repository.AppUiState
import io.github.zhanry.hometunnel.repository.HomeTunnelRepository
import io.github.zhanry.hometunnel.storage.SecureStateStore
import io.github.zhanry.hometunnel.ui.theme.HomeTunnelTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

class ManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun openHome() {
        val repository = HomeTunnelRepository(context, SecureStateStore(context))
        val state = AppUiState(
            persisted = PersistedState(username = "lin", userDisplayName = "林先生",
                profile = ServerProfile("https://console.home.example", "https://console.home.example/api/v1", "home.example", 7000, "home.example")),
            devices = listOf(ManagedDevice("study", "书房电脑", online = true), ManagedDevice("nas", "家庭 NAS", online = true)),
            connections = listOf(
                TunnelConnection("photos", "nas", "家庭相册", "photos", "http", publicUrl = "https://photos.home.example", localPort = 8080, version = 1, state = "Online"),
                TunnelConnection("automation", "study", "Home Assistant", "home", "http", publicUrl = "https://home.home.example", localPort = 8123, version = 1, state = "Pending"),
            ),
        )
        compose.setContent { HomeTunnelTheme { HomeScreen(state, repository, remember { SnackbarHostState() }) } }
    }

    private fun navigate(resource: Int) {
        compose.onAllNodesWithText(context.getString(resource)).onLast().performClick()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val destination = File(context.getExternalFilesDir(null), "ui-6.0-$name.png")
        destination.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun navigationFiltersByDeviceAndSearchesServices() {
        openHome()
        capture("overview")
        navigate(R.string.nav_devices)
        compose.onNodeWithText("家庭 NAS").assertIsDisplayed()
        capture("devices")
        compose.onNodeWithText("家庭 NAS").performClick()
        compose.onNodeWithText("家庭相册").assertIsDisplayed()
        compose.onNodeWithText("Home Assistant").assertDoesNotExist()
        capture("connections")
        compose.onNodeWithText(context.getString(R.string.search_connections)).performTextInput("no-match")
        compose.onNodeWithText("家庭相册").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.no_search_results)).assertIsDisplayed()
        navigate(R.string.nav_account)
        compose.onNodeWithText("林先生").assertIsDisplayed()
        capture("account")
    }

    @Test fun multiDeviceCreationRequiresAnExplicitTargetAndHasAVisibleCancelAction() {
        openHome()
        compose.onNodeWithText(context.getString(R.string.add_connection)).performClick()
        compose.onNodeWithText(context.getString(R.string.choose_device)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.save)).assertIsNotEnabled()
        compose.onNodeWithText("家庭 NAS").performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.discard_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.keep_editing)).performClick()
        compose.onNodeWithText(context.getString(R.string.choose_device)).assertIsDisplayed()
    }
}
