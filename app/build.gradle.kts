import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---- Signing --------------------------------------------------------------
// CI passes the keystore through environment variables (GitHub secrets).
// Locally, keystore/keystore.properties can point at a keystore file.
// With neither, release builds fall back to the debug key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

// CI restores keystore/release.jks from the MEMC_KEYSTORE_BASE64 secret; it is never committed.
val releaseStorePassword = signingValue("MEMC_KEYSTORE_PASSWORD", "storePassword")
val releaseStoreFile = (signingValue("MEMC_KEYSTORE_FILE", "storeFile") ?: "keystore/release.jks")
    .let { rootProject.file(it) }
    .takeIf { it.exists() && !releaseStorePassword.isNullOrBlank() }

android {
    namespace = "com.jainz.memc"
    compileSdk = 36

    // AI models stay uncompressed in the APK (they barely compress), so the app can read their size
    // and copy them out quickly.
    androidResources {
        noCompress += listOf("onnx")
    }

    defaultConfig {
        applicationId = "com.jainz.memc"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("VERSION_CODE") ?: "0")
    }

    // Two variants of the same app:
    //  phone - everything, for Android 10+ phones (64-bit ARM). The AI (NPU) mode runs on
    //          Snapdragon chips; every other phone uses the GPU engine.
    //  tv    - library and player for any Android TV / Google TV box from Android 7: no 60 fps
    //          engine, no export, no Explore; remote-control navigation; ARM and x86
    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
            buildConfigField("boolean", "TV", "false")
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        create("tv") {
            dimension = "device"
            versionNameSuffix = "-tv"
            buildConfigField("boolean", "TV", "true")
            // Many TV boxes in use still run Android 7-9 (Fire TV, Mi Box, cheap Android boxes).
            minSdk = 24
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = signingValue("MEMC_KEY_ALIAS", "keyAlias") ?: "memc"
                keyPassword = signingValue("MEMC_KEY_PASSWORD", "keyPassword") ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            // R8: drops unused code and optimises the rest (Compose runs much smoother and the
            // APK is smaller). Names are kept for readable crash reports; see proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Newer Java library parts (java.time etc.) on Android 7.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/BCKEY.*",
                "META-INF/BC*.SF",
                "META-INF/BC*.DSA",
            )
        }
        // Extracted to disk at install: the NPU loads its part of the QNN runtime from a real folder.
        jniLibs {
            useLegacyPackaging = true
            // The NPU runtime for every Snapdragon generation (V68 = Snapdragon 888 onwards)
            // is kept so AI mode works on any Snapdragon phone. Only the parts this app never
            // uses (the older DSP and GPU back ends) are dropped.
            excludes += listOf("**/libQnnDsp*.so", "**/libQnnGpu.so")
        }
    }

    lint {
        // Media3 marks many APIs with @UnstableApi; that is expected here.
        disable += listOf("UnsafeOptInUsageError")
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource)
    implementation(libs.media3.extractor)
    implementation(libs.media3.effect)
    implementation(libs.media3.container)
    implementation(libs.media3.transformer)
    implementation(libs.ffmpeg.decoder)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.okhttp)
    implementation(libs.smbj)
    // ONNX Runtime with Qualcomm's QNN backend: runs the RIFE model on the Snapdragon NPU.
    // Only shipped in the phone variant; the TV variant compiles against it but never loads it.
    "phoneImplementation"(libs.onnxruntime.qnn)
    "tvCompileOnly"(libs.onnxruntime.qnn)
    implementation(libs.coroutines.android)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Phone folders picked with Android's folder picker (automatic backups).
    implementation("androidx.documentfile:documentfile:1.0.1")
}
