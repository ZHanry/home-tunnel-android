plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

abstract class PackageNativeNoticesTask : Exec() {
    @get:OutputDirectory
    abstract val assetOutput: DirectoryProperty
}

val productVersionValue = providers.gradleProperty("HOME_TUNNEL_VERSION_NAME").get()
val versionNameValue = providers.gradleProperty("HOME_TUNNEL_RELEASE_VERSION").orElse(productVersionValue).get()
val versionCodeValue = providers.gradleProperty("HOME_TUNNEL_VERSION_CODE").get().toInt()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-rc\\.[1-9][0-9]*)?").matches(productVersionValue) && versionNameValue == productVersionValue) {
    "Release display version must exactly match the complete source version"
}
require(versionCodeValue in 1..2_100_000_000) { "Android versionCode is outside the supported range" }
val remoteNativeRoot = providers.gradleProperty("remoteNativeRoot").orNull
val remoteControllerArm64 = providers.gradleProperty("remoteControllerArm64").orNull == "true"

fun signingValue(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull
        ?: providers.gradleProperty(propertyName).orNull

val releaseStoreFile = signingValue("ANDROID_RELEASE_STORE_FILE", "android.release.storeFile")
val releaseStorePassword = signingValue("ANDROID_RELEASE_STORE_PASSWORD", "android.release.storePassword")
val releaseKeyAlias = signingValue("ANDROID_RELEASE_KEY_ALIAS", "android.release.keyAlias")
val releaseKeyPassword = signingValue("ANDROID_RELEASE_KEY_PASSWORD", "android.release.keyPassword")
val completeReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.zhanry.hometunnel"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.zhanry.hometunnel"
        buildConfigField("boolean", "REMOTE_CONTROLLER_BACKEND", remoteControllerArm64.toString())
        minSdk = 26
        ndk { abiFilters += setOf("arm64-v8a") }
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = versionNameValue
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        resourceConfigurations += listOf("en", "zh-rCN")
        if (remoteNativeRoot != null) {
            externalNativeBuild.cmake.arguments += "-DHOME_TUNNEL_REMOTE_ROOT=${file(remoteNativeRoot).absolutePath.replace('\\', '/')}"
            // The imported core uses shared libc++; package one matching NDK runtime with JNI.
            externalNativeBuild.cmake.arguments += "-DANDROID_STL=c++_shared"
            externalNativeBuild.cmake.arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
        }
    }

    if (completeReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Older x86_64 emulators cannot translate the arm64 UI tooling libraries.
            ndk { abiFilters += if (remoteControllerArm64) setOf("arm64-v8a") else setOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (completeReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (remoteNativeRoot != null) {
        ndkVersion = "27.2.12479018"
        externalNativeBuild.cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"

    packaging {
        jniLibs.keepDebugSymbols += "**/libhome_tunnel_remote.so"
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // The signed APK targets API 35 and
        // arm64. Android 16 target behavior and ChromeOS are not claimed
        // until their physical-device matrices are complete; all other lint
        // warnings remain release-blocking.
        disable += setOf("GradleDependency", "ChromeOsAbiSupport", "OldTargetApi", "PluralsCandidate", "UnusedResources")
    }
}

if (remoteNativeRoot != null) {
    val verifyRemoteNative = tasks.register<Exec>("verifyRemoteNative") {
        workingDir(rootProject.projectDir)
        commandLine(listOf(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
            "scripts/verify-remote-native.py", file(remoteNativeRoot).absolutePath) +
            if (remoteControllerArm64) listOf("--abis", "arm64-v8a") else emptyList())
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyRemoteNative) }
    if (remoteControllerArm64) {
        val noticeNdk = androidComponents.sdkComponents.sdkDirectory.map { it.dir("ndk/27.2.12479018") }
        androidComponents.onVariants { variant ->
            val taskName = "package${variant.name.replaceFirstChar { it.uppercaseChar() }}NativeNotices"
            val packageNativeNotices = tasks.register<PackageNativeNoticesTask>(taskName) {
                dependsOn(verifyRemoteNative)
                workingDir(rootProject.projectDir)
                inputs.files(rootProject.file("LICENSE"), rootProject.file("scripts/package-native-notices.py"),
                    file("$remoteNativeRoot/LICENSE.md"), file("$remoteNativeRoot/android-webrtc-build.json"))
                inputs.files(noticeNdk.map { it.file("NOTICE") }, noticeNdk.map { it.file("NOTICE.toolchain") },
                    noticeNdk.map { it.file("source.properties") })
                doFirst {
                    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
                        "scripts/package-native-notices.py", "--sdk", file(remoteNativeRoot).absolutePath,
                        "--ndk", noticeNdk.get().asFile.absolutePath,
                        "--output", assetOutput.get().dir("licenses").asFile.absolutePath)
                }
            }
            variant.sources.assets?.addGeneratedSourceDirectory(packageNativeNotices) { it.assetOutput }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
