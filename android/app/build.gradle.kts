import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release signing: load android/key.properties (gitignored) if present — same
// loader pattern as the host app. When absent (CI / fresh clones), release
// falls back to the debug signing config so non-release builds still pass.
// NOTE: the plugin app is a SEPARATE Play Console app; when it ships publicly
// it should get its OWN upload keystore (a separate key.properties), not the
// host's. Until then (debug-only feature surface) the debug fallback covers
// local builds, and release AAB builds reuse whatever key.properties is placed
// in this directory by the release tooling.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "hr.exel.kenosis_plugin_internet"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    // AGP 9 disables the AIDL build feature by default — the plugin contract
    // (android/app/src/main/aidl/hr/exel/kenosis/plugin/IKenosisPlugin.aidl)
    // is binder IPC, so opt in or the Stub/Proxy classes are never generated
    // and Kotlin compilation of InternetPluginService fails with
    // "Unresolved reference 'kenosis'".
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // "Kenosis AI - Internet Search plugin" — a separate Play Store app.
        // The host (hr.exel.kenosis_ai) discovers this app's service by the
        // hr.exel.kenosis_ai.PLUGIN_SERVICE intent action (manifest <queries>).
        applicationId = "hr.exel.kenosis.plugin_internet"
        minSdk = maxOf(flutter.minSdkVersion, 29)
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        // Debug builds get a ".debug" applicationId suffix, mirroring the host
        // app: hr.exel.kenosis.plugin_internet.debug installs alongside a
        // future Play-installed release. The host's discovery binds whatever
        // plugin package is installed — the manifest <queries> filters on the
        // PLUGIN_SERVICE action, not the package name.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // Signed with the upload keystore (android/key.properties,
            // gitignored) when present; otherwise the debug config so
            // `flutter run --release` / CI builds without the key still work.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Phase-1.5 browser_fetch engine: fast path = plain HTTP fetch + HTML→text,
    // fallback = hidden OS-WebView render (android.webkit — platform, no dep).
    // OkHttp (Apache-2.0) + jsoup (MIT) — both license-clean for the APK
    // (see the license review; no GPL in the APK).
    // Phase 2 replaces this with a future headless browser (JNI .so).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // Local unit tests (e.g. InternetPluginService.shouldFallback boundary).
    // Run on the dev machine: cd plugins/internet/android && ./gradlew :app:testDebugUnitTest
    // (NOT on the build server — too slow; same policy as the host app's tests).
    testImplementation("junit:junit:4.13.2")
    // The real org.json (android.jar's copy is a "not mocked" stub in local
    // unit tests) — needed by the Qwant SERP-parser tests (+73). Test-only:
    // the APK keeps the platform's android-runtime org.json.
    testImplementation("org.json:json:20240303")
}

flutter {
    source = "../.."
}