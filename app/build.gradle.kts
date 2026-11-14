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

    defaultConfig {
        applicationId = "com.jainz.memc"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("VERSION_CODE") ?: "0")
    }

    // Two variants of the same app:
    //  phone - everything, for the Galaxy S24 Ultra (Snapdragon 8 Gen 3, 64-bit ARM)
    //  tv    - library and player for Android TV / Google TV boxes: no 60 fps engine, no export,
    //          no Explore; remote-control navigation; 32- and 64-bit ARM
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
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
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
            isMinifyEnabled = false
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
            // Qualcomm runtime parts for other chips. The S24 Ultra's Snapdragon 8 Gen 3 uses the
            // "V75" NPU; dropping the rest roughly halves the APK.
            excludes += listOf(
                "**/libQnnHtpV68*.so", "**/libQnnHtpV69*.so", "**/libQnnHtpV73*.so",
                "**/libQnnHtpV79*.so", "**/libQnnHtpV81*.so", "**/libQnnDsp*.so", "**/libQnnGpu.so",
            )
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
}
