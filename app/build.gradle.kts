import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "de.wanderlogplus.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.wanderlogplus.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 79
        versionName = "0.61.0"
        // MapLibre ships ~10 MB of native code per ABI; both target phones are arm64 (no emulator in use).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release key: CI passes it via environment (GitHub secrets), locally it comes from the gitignored keystore.properties.
    val releaseKey = run {
        val props = Properties()
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
        fun value(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: props.getProperty(prop)
        val store = value("WLP_KEYSTORE_FILE", "storeFile")
        // Only an existing keystore: a path from another machine (or no keystore at all) builds unsigned.
        if (store == null || !file(store).isFile) null else listOf(store, value("WLP_KEYSTORE_PASSWORD", "storePassword"), value("WLP_KEY_ALIAS", "keyAlias"), value("WLP_KEY_PASSWORD", "keyPassword"))
    }
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey[0]!!)
                storePassword = releaseKey[1]
                keyAlias = releaseKey[2]
                keyPassword = releaseKey[3]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("repoRoot", rootDir.path) }
    }
    sourceSets {
        // Robolectric unit tests read the debug variant's merged assets, so the Room schemas must live there (debug APK only).
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

kotlin { jvmToolchain(17) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Screenshot tests only record (never compare); PNGs land under build/ and are never committed.
roborazzi { outputDir.set(file("build/outputs/roborazzi")) }

dependencies {
    implementation(project(":client"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.zxing.embedded)
    // OpenStreetMap maps (OpenFreeMap style, no key); never initialized in Robolectric tests.
    implementation(libs.maplibre.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    // Fixtures.realOrNull() runs the importer on the private real export when present.
    testImplementation(project(":importer"))
}

// Compose UI tests need ui-test-manifest, which is debug-only; run unit tests for debug only.
tasks.configureEach {
    if (name == "testReleaseUnitTest") enabled = false
}
