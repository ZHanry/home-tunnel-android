package io.github.zhanry.hometunnel.ui

import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.zhanry.hometunnel.R
import io.github.zhanry.hometunnel.UiReviewActivity
import io.github.zhanry.hometunnel.model.ManagedDevice
import io.github.zhanry.hometunnel.model.PersistedState
import io.github.zhanry.hometunnel.model.ServerProfile
import io.github.zhanry.hometunnel.model.TunnelConnection
import io.github.zhanry.hometunnel.model.UserInfo
import io.github.zhanry.hometunnel.repository.AppScreen
import io.github.zhanry.hometunnel.repository.AppUiState
import io.github.zhanry.hometunnel.repository.HomeTunnelRepository
import io.github.zhanry.hometunnel.repository.AdminRepository
import io.github.zhanry.hometunnel.repository.AdminPage
import io.github.zhanry.hometunnel.storage.SecureStateStore
import io.github.zhanry.hometunnel.ui.theme.HomeTunnelTheme
import io.github.zhanry.hometunnel.ui.theme.ThemeChoice
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.After

/** Opt-in screenshots of production Composables with declared development data.
 * This never establishes backend, native media, installed-release or locale-persistence acceptance.
 * The host runner binds the resulting device PNGs to clean source and both installed APK hashes.
 */
class UiReviewCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<UiReviewActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var reviewAdmin: AdminRepository? = null

    @After fun stopReviewRequests() { compose.runOnUiThread { reviewAdmin?.reset() } }

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
        val adminPage = screen.removePrefix("admin-").uppercase().let { value -> AdminPage.entries.firstOrNull { it.name == value } }
            .takeIf { screen.startsWith("admin-") }
        val wizardStep = listOf("tunnel-device", "tunnel-target", "tunnel-access", "tunnel-review", "tunnel-result").indexOf(screen)
        require(stateName in setOf("ready", "empty", "loading", "offline", "error", "no-permission", "conflict", "waiting", "unconfirmed", "port-conflict", "sync-failed"))
        require(screen in setOf("loading", "login", "login-mfa", "password-change", "overview", "devices", "connections", "account") || adminPage != null || wizardStep >= 0)
        require(adminPage == null || role == "admin")
        val interaction = args.getString("reviewInteraction") ?: "view"
        require(interaction in setOf("view", "keyboard", "search-empty", "create-user", "edit-user", "delete-user", "disable-user", "reset-password", "discard", "logout"))
        val template = args.getString("reviewTemplate") ?: "http"
        compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale)) }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.locales[0].toLanguageTag() == locale }
        val expectedOrientation = if (orientation == "landscape") Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = if (orientation == "landscape") ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == expectedOrientation }
        compose.runOnUiThread {
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val actual = Configuration(compose.activity.resources.configuration)
        val localized = compose.activity
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
        val admin = if (adminPage != null) AdminRepository({ UiReviewAdminApi(stateName) }, { state.currentUser }).also { reviewAdmin = it } else null
        compose.setContent {
                HomeTunnelTheme(choice) {
                    Surface(Modifier.fillMaxSize()) {
                        when (screen) {
                            "loading" -> LoadingScreen()
                            "login", "login-mfa" -> LoginScreen(state, repository)
                            "password-change" -> PasswordChangeScreen(state, repository)
                            else -> when {
                                admin != null -> HomeScreen(state, repository, remember { SnackbarHostState() }, administration = admin)
                                wizardStep >= 0 -> UiReviewWizard(wizardStep.coerceAtMost(3), screen == "tunnel-result", template, stateName, state.devices)
                                else -> HomeScreen(state, repository, remember { SnackbarHostState() })
                            }
                        }
                    }
                }
        }
        compose.waitForIdle()
        if (admin != null && adminPage != null) {
            compose.onAllNodesWithText(localized.getString(R.string.nav_account)).onLast().performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(localized.getString(R.string.nav_management)))
            compose.onNodeWithText(localized.getString(R.string.nav_management)).performClick()
            compose.waitForIdle()
            compose.runOnUiThread { if (adminPage == AdminPage.USER) admin.openUser("member-1") else admin.open(adminPage) }
            compose.waitUntil(5_000) { admin.state.value.loading == (stateName == "loading") }
            if (stateName in setOf("error", "offline", "no-permission"))
                check(admin.state.value.errorCode != null) { "Declared administrative error was not reached" }
        }
        val tab = when (screen) { "devices" -> R.string.nav_devices; "connections" -> R.string.nav_connections; "account" -> R.string.nav_account; else -> null }
        tab?.let { compose.onAllNodesWithText(localized.getString(it)).onLast().performClick() }
        val actionLabel = when (interaction) {
            "create-user" -> R.string.admin_new_user
            "edit-user" -> R.string.admin_edit
            "delete-user" -> R.string.admin_delete
            "disable-user" -> R.string.admin_disable
            "reset-password" -> R.string.admin_reset
            "logout" -> R.string.sign_out
            else -> null
        }
        if (actionLabel != null) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(localized.getString(actionLabel)))
            compose.onAllNodesWithText(localized.getString(actionLabel)).onLast().performClick()
            compose.onAllNodes(isDialog()).onLast().assertIsDisplayed()
        }
        if (interaction == "discard") {
            require(wizardStep >= 0)
            compose.onNodeWithContentDescription(localized.getString(R.string.wizard_back)).performClick()
            compose.onNodeWithText(localized.getString(R.string.discard_title)).assertIsDisplayed()
        }
        if (interaction == "search-empty") {
            require(screen == "connections")
            val searchLabel = localized.getString(R.string.search_connections)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(searchLabel))
            compose.onNodeWithText(searchLabel).performClick().performTextInput("no-match")
            compose.onNodeWithText(localized.getString(R.string.no_search_results)).assertIsDisplayed()
        }
        val usesKeyboard = interaction in setOf("keyboard", "search-empty")
        if (usesKeyboard) {
            require(screen in setOf("login", "login-mfa", "password-change", "connections") || wizardStep in 0..2)
            if (screen == "login-mfa") compose.onAllNodes(hasSetTextAction()).onLast().performClick()
            else if (interaction == "keyboard") compose.onAllNodes(hasSetTextAction()).onFirst().performClick()
            compose.runOnUiThread {
                compose.activity.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(compose.activity.currentFocus, InputMethodManager.SHOW_IMPLICIT)
            }
            compose.waitUntil(10_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            try {
                compose.waitUntil(5_000) {
                    runCatching { compose.onAllNodes(hasSetTextAction() and isFocused()).onFirst().assertIsDisplayed() }.isSuccess
                }
            } catch (failure: Throwable) {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(directory, "focus-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                File(directory, "focus-failure-semantics.txt").writeText(compose.onAllNodes(isRoot()).onLast().printToString())
                throw failure
            }
            if (interaction == "search-empty")
                compose.onNodeWithText(localized.getString(R.string.no_search_results)).assertIsDisplayed()
        }
        val frames = JSONArray()
        val foregroundRoot = compose.onAllNodes(isRoot()).fetchSemanticsNodes().last()
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            SemanticsMatcher("belongs to the foreground window") { node ->
                generateSequence(node.parent) { it.parent }.any { it.id == foregroundRoot.id }
            }
        var previousOffset = -1f
        var complete = false
        for (index in 0 until 24) {
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            // PixelCopy waits for the Compose frame to reach the display before
            // the full-device capture, which also includes system bars/IME.
            compose.onAllNodes(isRoot()).onLast().captureToImage()
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
            val semantics = File(directory, "frame-${index.toString().padStart(2, '0')}-semantics.txt")
            semantics.writeText(compose.onAllNodes(isRoot()).onLast().printToString())
            frames.put(JSONObject().put("file", file.name).put("width", bitmap.width).put("height", bitmap.height)
                .put("bytes", file.length()).put("sha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
                .put("scroll_value", offset.toDouble()).put("scroll_maximum", maximum.toDouble())
                .put("semantics_file", semantics.name)
                .put("semantics_sha256", MessageDigest.getInstance("SHA-256").digest(semantics.readBytes()).joinToString("") { "%02x".format(it) }))
            bitmap.recycle()
            if (scroll == null || offset >= maximum - 0.5f || usesKeyboard) { complete = true; break }
            previousOffset = offset
            compose.onAllNodes(matcher).onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scroll.boundsInRoot.height * 0.6f) }
        }
        check(complete) { "Scrollable page exceeded the bounded capture; inspect before continuing" }
        val record = JSONObject().put("case_id", caseId).put("screen", screen).put("locale", locale).put("theme", theme)
            .put("template", template)
            .put("role", role).put("state", stateName).put("interaction", interaction).put("frames", frames)
            .put("synthetic_data", true).put("formal_acceptance", false).put("system_dark", systemDark)
            .put("density_dpi", actual.densityDpi).put("font_scale", actual.fontScale.toDouble()).put("orientation", actual.orientation)
            .put("ime_visible", ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            .put("capture_method", "actual-device-scrolled-viewport")
            .put("locale_method", "Production application theme and AppCompat application locale verified on Activity resources; cold-start locale persistence not tested")
        File(directory, "capture.json").writeText(record.toString(2) + "\n")
    }
}
