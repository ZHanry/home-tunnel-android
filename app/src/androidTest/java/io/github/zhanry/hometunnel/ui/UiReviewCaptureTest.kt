package io.github.zhanry.hometunnel.ui

import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.zhanry.hometunnel.R
import io.github.zhanry.hometunnel.model.ManagedDevice
import io.github.zhanry.hometunnel.model.PersistedState
import io.github.zhanry.hometunnel.model.ServerProfile
import io.github.zhanry.hometunnel.model.TunnelConnection
import io.github.zhanry.hometunnel.model.UserInfo
import io.github.zhanry.hometunnel.repository.AppScreen
import io.github.zhanry.hometunnel.repository.AppUiState
import io.github.zhanry.hometunnel.repository.HomeTunnelRepository
import io.github.zhanry.hometunnel.storage.SecureStateStore
import io.github.zhanry.hometunnel.ui.theme.HomeTunnelTheme
import io.github.zhanry.hometunnel.ui.theme.ThemeChoice
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in screenshots of production Composables with declared development data.
 * This never establishes backend, native media, installed-release or locale-persistence acceptance.
 * The host runner binds the resulting device PNGs to clean source and both installed APK hashes.
 */
class UiReviewCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun captureDeclaredReviewCase() {
        val args = InstrumentationRegistry.getArguments()
        val caseId = args.getString("reviewCase")
        assumeTrue("UI review is an explicit separate run", caseId != null)
        require(caseId!!.matches(Regex("[a-zA-Z0-9.-]{1,160}")))
        val run = requireNotNull(args.getString("reviewRun"))
        require(run.matches(Regex("[a-z0-9-]{1,40}")))
        val screen = requireNotNull(args.getString("reviewScreen"))
        val locale = requireNotNull(args.getString("reviewLocale"))
        val theme = requireNotNull(args.getString("reviewTheme"))
        val stateName = args.getString("reviewState") ?: "ready"
        val role = args.getString("reviewRole") ?: "user"
        val orientation = requireNotNull(args.getString("reviewOrientation"))
        require(orientation in setOf("portrait", "landscape"))
        require(locale in setOf("en", "zh-CN"))
        require(theme in setOf("light", "dark", "system-light", "system-dark"))
        require(role in setOf("user", "admin"))
        require(stateName in setOf("ready", "empty", "loading", "offline"))
        require(screen in setOf("loading", "login", "login-mfa", "password-change", "overview", "devices", "connections", "account"))
        val expectedOrientation = if (orientation == "landscape") Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = if (orientation == "landscape") ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == expectedOrientation }
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val actual = Configuration(compose.activity.resources.configuration)
        val config = Configuration(actual).apply { setLocale(Locale.forLanguageTag(locale)) }
        val localized = context.createConfigurationContext(config)
        val choice = when (theme) { "light" -> ThemeChoice.LIGHT; "dark" -> ThemeChoice.DARK; else -> ThemeChoice.SYSTEM }
        val systemDark = actual.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (theme.startsWith("system-")) require(systemDark == theme.endsWith("dark")) { "Host must select the declared system theme" }
        val directory = File(context.getExternalFilesDir(null), "ht10-ui/$run/$caseId")
        check(!directory.exists()) { "Previous screenshot evidence must be preserved" }
        check(directory.mkdirs())
        val repository = HomeTunnelRepository(context, SecureStateStore(context))
        val empty = stateName == "empty"
        val profile = ServerProfile("https://console.example.test", "https://console.example.test/api/v1", "example.test", 7000, "example.test")
        val state = AppUiState(
            screen = AppScreen.HOME,
            persisted = PersistedState(username = "review-user", userDisplayName = "Review User", profile = profile),
            currentUser = UserInfo("review-user", "review-user", "Review User", role, "normal"),
            busy = stateName == "loading", stale = stateName == "offline", loginMfaRequired = screen == "login-mfa",
            devices = if (empty) emptyList() else listOf(ManagedDevice("study", "Study PC", online = true), ManagedDevice("nas", "Family NAS", online = false)),
            connections = if (empty) emptyList() else listOf(
                TunnelConnection("photos", "nas", "Family photos", "photos", "http", publicUrl = "https://photos.example.test", localPort = 8080, version = 1, state = "Online"),
                TunnelConnection("automation", "study", "Home Assistant", "home", "http", publicUrl = "https://home.example.test", localPort = 8123, version = 1, state = "Pending"),
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                HomeTunnelTheme(choice) {
                    Surface(Modifier.fillMaxSize()) {
                        when (screen) {
                            "loading" -> LoadingScreen()
                            "login", "login-mfa" -> LoginScreen(state, repository)
                            "password-change" -> PasswordChangeScreen(state, repository)
                            else -> HomeScreen(state, repository, remember { SnackbarHostState() })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val tab = when (screen) { "devices" -> R.string.nav_devices; "connections" -> R.string.nav_connections; "account" -> R.string.nav_account; else -> null }
        tab?.let { compose.onAllNodesWithText(localized.getString(it)).onLast().performClick() }
        val interaction = args.getString("reviewInteraction") ?: "view"
        require(interaction in setOf("view", "keyboard"))
        if (interaction == "keyboard") {
            require(screen in setOf("login", "login-mfa", "password-change"))
            if (screen == "login-mfa") compose.onAllNodes(hasSetTextAction()).onLast().performClick()
            else compose.onAllNodes(hasSetTextAction()).onFirst().performClick()
            compose.runOnUiThread {
                compose.activity.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(compose.activity.currentFocus, InputMethodManager.SHOW_IMPLICIT)
            }
            compose.waitUntil(10_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        }
        val frames = JSONArray()
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        var previousOffset = -1f
        var complete = false
        for (index in 0 until 24) {
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            // PixelCopy waits for the Compose frame to reach the display before
            // the full-device capture, which also includes system bars/IME.
            compose.onRoot().captureToImage()
            instrumentation.uiAutomation.waitForIdle(100, 5_000)
            val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
            val scroll = nodes.firstOrNull()
            val range = scroll?.config?.get(SemanticsProperties.VerticalScrollAxisRange)
            val offset = range?.value() ?: 0f
            val maximum = range?.maxValue() ?: 0f
            if (index > 0 && offset <= previousOffset) { complete = true; break }
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Device screenshot unavailable" }
            val file = File(directory, "frame-${index.toString().padStart(2, '0')}.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            frames.put(JSONObject().put("file", file.name).put("width", bitmap.width).put("height", bitmap.height)
                .put("bytes", file.length()).put("sha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
                .put("scroll_value", offset.toDouble()).put("scroll_maximum", maximum.toDouble()))
            bitmap.recycle()
            if (scroll == null || offset >= maximum - 0.5f || interaction == "keyboard") { complete = true; break }
            previousOffset = offset
            compose.onAllNodes(matcher).onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scroll.boundsInRoot.height * 0.6f) }
        }
        check(complete) { "Scrollable page exceeded the bounded capture; inspect before continuing" }
        val record = JSONObject().put("case_id", caseId).put("screen", screen).put("locale", locale).put("theme", theme)
            .put("role", role).put("state", stateName).put("interaction", interaction).put("frames", frames)
            .put("synthetic_data", true).put("formal_acceptance", false).put("system_dark", systemDark)
            .put("density_dpi", actual.densityDpi).put("font_scale", actual.fontScale.toDouble()).put("orientation", actual.orientation)
            .put("ime_visible", ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            .put("capture_method", "actual-device-scrolled-viewport")
            .put("locale_method", "Production Composables with explicit localized resource context; persistence not tested")
        File(directory, "capture.json").writeText(record.toString(2) + "\n")
    }
}
