// Embedded Windows runtime for PocketOS.
// Derived from Winlator (https://github.com/brunodev85/winlator-app, commit 3981d86, LGPL-2.1). See LICENSE and MODIFICATIONS.md.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.winlator"
    compileSdk = 35
    ndkVersion = "24.0.8215888"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
    }
    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
    // Runtime archives are already zstd-compressed.
    androidResources { noCompress += listOf("tzst", "sf2") }
}

dependencies {
    api("androidx.appcompat:appcompat:1.4.0")
    implementation("androidx.preference:preference:1.2.1")
    api("com.google.android.material:material:1.4.0")
    implementation("com.github.luben:zstd-jni:1.5.2-3@aar")
    implementation("org.tukaani:xz:1.7")
    implementation("org.apache.commons:commons-compress:1.20")
    implementation("androidx.lifecycle:lifecycle-process:2.5.1")
    implementation("androidx.startup:startup-runtime:1.1.1")
}
