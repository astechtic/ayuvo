import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

// Firebase core only (no Analytics/Crashlytics/Messaging), so nothing is sent anywhere.
// google-services.json must contain a client for every applicationId it processes; debug
// (.debug) and debug2 (.debug2) are skipped until those apps are added to the Firebase
// project and the file is re-downloaded, at which point they are picked up automatically.
val googleServicesPackages: Set<String> = file("google-services.json").let { json ->
    if (!json.exists()) emptySet()
    else Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\"").findAll(json.readText())
        .map { it.groupValues[1] }.toSet()
}

// Release signing config is read from android/keystore.properties (gitignored).
// When the file is absent (fresh checkout, CI without secrets), assembleRelease
// still works but emits an unsigned APK. Generate one with:
//   keytool -genkey -v -keystore ayuvo-release.jks -keyalg RSA -keysize 2048 \
//           -validity 10000 -alias ayuvo
// then create keystore.properties with storeFile / storePassword / keyAlias / keyPassword.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.ayuvo.health"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ayuvo.health"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Release uses localized @string/app_name; debug overrides to "Ayuvo Debug".
        manifestPlaceholders["launcherAppName"] = "@string/app_name"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val oauthProps = Properties().apply {
            val file = rootProject.file("oauth.properties")
            if (file.exists()) load(file.inputStream())
        }
        val webClientId = oauthProps.getProperty("cloud.backup.web.client.id")
            ?: localProperties.getProperty("cloud.backup.web.client.id")
            ?: ""
        buildConfigField(
            "String",
            "CLOUD_BACKUP_WEB_CLIENT_ID",
            "\"${webClientId.replace("\"", "\\\"")}\""
        )
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the signing config if keystore.properties exists. Without
            // it, gradle emits app-release-unsigned.apk and you sign manually with
            // apksigner before uploading to the Play Console.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            // Suffix the package + version so the debug build installs side-by-side
            // with the production app. Launcher label matches iOS Debug: "Ayuvo Debug".
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Literal placeholder so locale app_name strings can't override the label.
            manifestPlaceholders["launcherAppName"] = "Ayuvo Debug"
        }
        create("debug2") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".debug2"
            versionNameSuffix = "-debug2"
            manifestPlaceholders["launcherAppName"] = "Ayuvo Debug 2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // AdsConfig gates real vs test ad units on BuildConfig.DEBUG.
        buildConfig = true
    }

    lint {
        // The default resources intentionally provide English fallback copy while
        // translated locales are updated incrementally. Keep all other release
        // checks enabled; only the fallback-policy warning is excluded.
        disable += "MissingTranslation"
    }

    testOptions {
        // Health Connect record constructors (BloodPressureRecord and friends) consult
        // Build.VERSION; returning defaults lets the pure record mapper be exercised in
        // JVM unit tests instead of only on a device.
        unitTests.isReturnDefaultValues = true
    }

    // Workouts: the exercise catalogue shared with iOS (scripts/import_exercises_dataset.py).
    // Exercise media is streamed from the dataset's pinned GitHub URLs, never bundled.
    sourceSets {
        getByName("main") {
            assets.srcDirs(
                "src/main/assets",
                "../../shared/exercises",
                "../../local-models/legal"
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

androidComponents {
    onVariants { variant ->
        if (variant.applicationId.get() !in googleServicesPackages) {
            val taskName = "process${variant.name.replaceFirstChar { it.uppercase() }}GoogleServices"
            tasks.matching { it.name == taskName }.configureEach { enabled = false }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.common)
    implementation(libs.play.review.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
    implementation(libs.gson)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    // Health Records: GMS document scanner (Phase 1) and bundled Latin text recognition (used from Phase 2).
    implementation(libs.play.services.mlkit.document.scanner)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.app.update)
    implementation(libs.play.services.auth)
    implementation(libs.vico.compose.m3)
    implementation(libs.litert.lm.android)
    implementation(libs.whisper.android)

    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
    testImplementation("com.squareup.okhttp3:okhttp-tls:${libs.versions.okhttp.get()}")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
