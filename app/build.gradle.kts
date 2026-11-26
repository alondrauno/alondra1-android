plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.alondra1.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alondra1.app"
        minSdk = 29 // Android 10
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.alondra1.app.InstrumentedTestRunner"
        // Fixed cloud API address. Empty (release) = signed discovery of the flavor's environment.
        // Debug builds use the local development server; override with -PapiBaseUrl=...
        buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("apiBaseUrl") ?: ""}\"")
    }

    signingConfigs {
        // Release signing only from CI secrets / local properties; keys are never committed.
        create("release") {
            val store = System.getenv("ALONDRA1_KEYSTORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("ALONDRA1_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ALONDRA1_KEY_ALIAS")
                keyPassword = System.getenv("ALONDRA1_KEY_PASSWORD")
            }
        }
        // The Dev app's own stable key (CI secret), so a newer Dev APK installs over the previous one and keeps its data.
        // Never the prod key; without it, local dev builds use the debug key as before.
        create("dev") {
            val store = System.getenv("ALONDRA1_DEV_KEYSTORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storeType = "pkcs12"
                storePassword = System.getenv("ALONDRA1_DEV_KEYSTORE_PASSWORD")
                keyAlias = "alondra1-dev"
                keyPassword = System.getenv("ALONDRA1_DEV_KEYSTORE_PASSWORD")
            }
        }
    }

    // One app, two environments (completion spec v1.1 §8.3): separate application ids, names, endpoints and discovery
    // keys. The prod flavor trusts only prod; a dev build cannot reach prod (BuildEnvironment.allowsAddress).
    flavorDimensions += "environment"
    productFlavors {
        create("prod") {
            dimension = "environment"
            resValue("string", "app_name", "Alondra1")
            buildConfigField("String", "ENVIRONMENT", "\"prod\"")
            buildConfigField("String", "API_ORIGIN", "\"https://api.alondra1.com\"")
            buildConfigField("String", "DISCOVERY_PRIMARY", "\"https://config.alondra1.com/manifest.json\"")
            buildConfigField("String", "DISCOVERY_MIRROR", "\"https://config2.alondra1.com/manifest.json\"")
            // Public keys of the prod discovery signer (alondra1-infra discovery/trust/prod.json); empty until created.
            buildConfigField("String", "DISCOVERY_TRUST", "\"{\\\"keys\\\": []}\"")
        }
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Alondra1 Dev")
            if (System.getenv("ALONDRA1_DEV_KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("dev")
            buildConfigField("String", "ENVIRONMENT", "\"dev\"")
            buildConfigField("String", "API_ORIGIN", "\"https://api.alondra1.dev\"")
            buildConfigField("String", "DISCOVERY_PRIMARY", "\"https://config.alondra1.dev/manifest.json\"")
            buildConfigField("String", "DISCOVERY_MIRROR", "\"https://config2.alondra1.dev/manifest.json\"")
            // Public keys of the dev discovery signer (alondra1-infra discovery/trust/dev.json).
            buildConfigField("String", "DISCOVERY_TRUST", "\"{\\\"keys\\\":[{\\\"key_id\\\":\\\"dev-2026-1\\\",\\\"environment\\\":\\\"dev\\\",\\\"public_key\\\":\\\"9866fABqc4F5q2wLZ2WgMZ0KF5AUZwXepQo5KYqGCSw=\\\"}]}\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("ALONDRA1_KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Local dev server (alondra1-backend: go run ./cmd/devserver) as seen from the emulator.
            buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("apiBaseUrl") ?: "http://10.0.2.2:8080"}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.logging.enabled", "false")
            it.maxHeapSize = "2g"
            it.forkEvery = 40
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi", "Typos")
    }
    sourceSets {
        // Room schema JSON files are committed; debug assets carry them for the migration tests (debug builds only).
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
