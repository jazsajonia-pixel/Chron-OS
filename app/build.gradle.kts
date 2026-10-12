plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Share the real PE fixtures and captured runtime logs with app tests.
    sourceSets.getByName("test").resources.srcDir("../core/src/test/resources")
    namespace = "com.chronos.pocketos"
    compileSdk = 35
    defaultConfig {
        // "com.chron.os" has the same length as "com.winlator", so the embedded runtime's prebuilt Linux files
        // (which contain the absolute path /data/data/<package>/files) are retargeted byte-for-byte. Do not change.
        applicationId = "com.chron.os"
        minSdk = 26
        // The embedded Windows runtime starts Box64/Wine binaries from the app's private storage. Android only
        // allows that for apps targeting API 28 or lower (same as Winlator). PocketOS is distributed as an APK.
        targetSdk = 28
        versionCode = 3
        versionName = "0.3.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key only so the artifact is installable for testing.
            // Replace with a real release keystore before any public distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // ./gradlew :app:testDebugUnitTest -Ppocketos.runtimeIntegration=true unpacks the real runtime (slow, ~1 GB).
            all {
                it.systemProperty("pocketos.runtimeIntegration", project.findProperty("pocketos.runtimeIntegration") ?: "false")
                // ./gradlew :app:testDebugUnitTest -Ppocketos.displayPipeline=true runs a real Windows program under
                // qemu against PocketOS's display server (needs tools/runtime/host-display-test/build.sh first).
                val pipeline = project.findProperty("pocketos.displayPipeline")?.toString() ?: "false"
                it.systemProperty("pocketos.displayPipeline", pipeline)
                if (pipeline == "true") {
                    it.systemProperty("java.library.path", rootProject.file("tools/runtime/host-display-test/out").absolutePath)
                    System.getenv("POCKETOS_TEST_LD_PRELOAD")?.let { lib -> it.environment("LD_PRELOAD", lib); it.environment("ASAN_OPTIONS", "detect_leaks=0:handle_segv=0:allow_user_segv_handler=1") }
                    it.systemProperty("pocketos.clickTestExe", rootProject.file("tools/runtime/host-display-test/out/click_test.exe").absolutePath)
                }
            }
        }
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // The runtime executes some bundled native programs (e.g. PulseAudio) from the extracted library folder.
        jniLibs { useLegacyPackaging = true }
    }
    lint { disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi") }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":winruntime"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // JVM build of zstd for the runtime integration test (the runtime module ships the Android build).
    testImplementation("com.github.luben:zstd-jni:1.5.2-3")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
