@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
    "ParameterName",
    "RedundantCurlyBracesInStringTemplate"
)

import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep generated files out of the source tree: everything Gradle writes for
// this module goes to <repo>/build/app instead of app/build.
layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/app"))


// ============================================================================
// VERSION CONFIGURATION
// ============================================================================

fun parse_and_validate_version(version_string: String): Triple<Int, Int, Int> {
    println("[VERSION] parse_and_validate_version version_string:${version_string}")

    val parts = version_string.split(".").map(String::toInt)

    require(parts.size == 3) {
        "appVersionName must have three parts (major.minor.patch), got: ${version_string}"
    }
    println("[VERSION] Parts: major=${parts[0]}, minor=${parts[1]}, patch=${parts[2]}")

    require(parts.all { it >= 0 }) {
        "All version parts must be non-negative"
    }
    println("[VERSION] All parts are non-negative [OK]")

    require(parts[1] in 0..99 && parts[2] in 0..99) {
        "Minor and patch must be 0–99, got: ${parts[1]}.${parts[2]}"
    }
    println("[VERSION] Minor and patch in valid range (0–99) [OK]")

    return Triple(parts[0], parts[1], parts[2])
}

fun calculate_version_code(major: Int, minor: Int, patch: Int): Int {
    println("[VERSION] calculate_version_code major:${major}, minor:${minor}, patch:${patch}")

    val base_version_code = major * 10_000 + minor * 100 + patch
    println("[VERSION] Base version code: ${base_version_code}")

    val app_version_code = base_version_code * 10
    println("[VERSION] App version code (with APK type suffix): ${app_version_code}")

    require(app_version_code in 1..2_100_000_000) {
        "Generated versionCode is outside Android's supported range: ${app_version_code}"
    }
    println("[VERSION] Version code is within valid range [OK]")

    return base_version_code
}


val versionFile = rootProject.file("version.txt")
require(versionFile.exists()) {
    "version.txt not found at ${versionFile.absolutePath}"
}
val app_version_name = versionFile.readText().trim()
println("[VERSION] got version name string from /version.txt, content: ${app_version_name}")


println("[VERSION] ============================================")
val (version_major, version_minor, version_patch) = parse_and_validate_version(app_version_name)
val base_version_code = calculate_version_code(version_major, version_minor, version_patch)
val app_version_code = base_version_code * 10
println("[VERSION] ============================================\n")


// ============================================================================
// KEYSTORE CONFIGURATION
// ============================================================================

data class KeystoreConfig(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String
)

fun load_keystore_config(): KeystoreConfig? {
    println("[KEYSTORE] ============================================")
    println("[KEYSTORE] Loading keystore configuration...")

    val properties = Properties()
    val properties_file = rootProject.file("keystore.properties")

    if (properties_file.exists()) {
        println("[KEYSTORE] Found keystore.properties file: ${properties_file.absolutePath}")
        FileInputStream(properties_file).use(properties::load)
        println("[KEYSTORE] Loaded properties from file")
    } else {
        println("[KEYSTORE] keystore.properties file not found, trying environment variables")
    }

    fun get_value(property_key: String, env_key: String): String? {
        val from_file = properties.getProperty(property_key)

        if (from_file != null) {
            println("[KEYSTORE] Found '${property_key}' in './keystore.properties' file")
            return from_file
        }

        val from_env = System.getenv(env_key)
        if (from_env != null) {
            println("[KEYSTORE] Found '${env_key}' in environment variables")
            return from_env
        }

        println("[KEYSTORE] '${property_key}' / '${env_key}' not found anywhere")
        return null
    }

    val storeFile = get_value("storeFile", "PDFR_STORE_FILE")

    if (storeFile == null) {
        println("[KEYSTORE] Keystore file not configured. Using debug signing for release builds.")
        println("[KEYSTORE] ============================================\n")
        return null
    }

    println("[KEYSTORE] Keystore file configured: ${storeFile}")

    val storePassword = get_value("storePassword", "PDFR_STORE_PASSWORD")
    require(storePassword != null) {
        "storePassword not found in keystore.properties or PDFR_STORE_PASSWORD env var"
    }
    println("[KEYSTORE] Keystore password configured [OK]")

    val keyAlias = get_value("keyAlias", "PDFR_KEY_ALIAS")
    require(keyAlias != null) {
        "keyAlias not found in keystore.properties or PDFR_KEY_ALIAS env var"
    }
    println("[KEYSTORE] Key alias configured: ${keyAlias} [OK]")

    val keyPassword = get_value("keyPassword", "PDFR_KEY_PASSWORD")
    require(keyPassword != null) {
        "keyPassword not found in keystore.properties or PDFR_KEY_PASSWORD env var"
    }
    println("[KEYSTORE] Key password configured [OK]")

    println("[KEYSTORE] All keystore credentials loaded successfully [OK]")
    println("[KEYSTORE] ============================================\n")

    return KeystoreConfig(
        storeFile = storeFile,
        storePassword = storePassword,
        keyAlias = keyAlias,
        keyPassword = keyPassword
    )
}

val keystore_config = load_keystore_config()


// ============================================================================
// ANDROID CONFIGURATION
// ============================================================================

android {
    namespace = "com.karako.pdfr"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.karako.pdfr"
        minSdk = 24
        targetSdk = 37
        versionCode = app_version_code
        versionName = app_version_name
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        println("[ANDROID] App configured: version=${app_version_name} (code=${app_version_code})")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    splits {
        abi {
            println("[ANDROID] ABI splits enabled: armeabi-v7a, arm64-v8a, x86, x86_64")
            println("[ANDROID] Universal APK enabled for all architectures")

            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keystore_config != null) {
            println("[SIGNING] Creating release signing config with keystore credentials")

            create("release") {
                storeFile = rootProject.file(keystore_config.storeFile)
                storePassword = keystore_config.storePassword
                keyAlias = keystore_config.keyAlias
                keyPassword = keystore_config.keyPassword

                enableV1Signing = true
                enableV2Signing = true
            }

            // ===== VALIDATION =====
            val releaseConfig = signingConfigs.getByName("release")
            println("[SIGNING] Validating release signing config...")

            if (releaseConfig.storeFile == null) {
                error("[SIGNING] ERROR: storeFile is NULL - keystore file not set!")
            } else {
                println("[SIGNING] [OK] storeFile: ${releaseConfig.storeFile}")
            }

            if (releaseConfig.storePassword == null) {
                error("[SIGNING] ERROR: storePassword is NULL - keystore password not set!")
            } else {
                println("[SIGNING] [OK] storePassword: [REDACTED]")
            }

            if (releaseConfig.keyAlias == null) {
                error("[SIGNING] ERROR: keyAlias is NULL - key alias not set!")
            } else {
                println("[SIGNING] [OK] keyAlias: ${releaseConfig.keyAlias}")
            }

            if (releaseConfig.keyPassword == null) {
                error("[SIGNING] ERROR: keyPassword is NULL - key password not set!")
            } else {
                println("[SIGNING] [OK] keyPassword: [REDACTED]")
            }

            println("[SIGNING] Release signing config created [OK]")
        } else {
            println("[SIGNING] No keystore configured. Release builds will use debug signing.")
        }
    }

    buildTypes {
        release {
            println("[BUILD] Configuring release build type...")

            isMinifyEnabled = true
            println("[BUILD] Minification enabled")

            //noinspection NotShrinkingResources
            isShrinkResources = false
            println("[BUILD] Resource shrinking disabled (ProGuard handles minification)")

            if (keystore_config != null) {
                println("[BUILD] Using release signing config")
                signingConfig = signingConfigs.getByName("release")
            } else {
                println("[BUILD] Keystore not configured, falling back to debug signing")
                signingConfig = signingConfigs.getByName("debug")
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            ndk {
                println("[BUILD] explicitly include debug symbols to make crash more clear (removing uses Android NDK llvm-strip).")
                debugSymbolLevel = "FULL" // or "SYMBOL_TABLE" or "NONE"
            }
            println("[BUILD] ProGuard rules configured")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        println("[COMPILE] Java compatibility set to VERSION_17")
    }

    buildFeatures {
        compose = true
        println("[BUILD] Jetpack Compose enabled")
    }

    androidResources {
        println("[RESOURCES] Optimizing resources...")
        println("[RESOURCES] Storing binary files uncompressed (already compressed)")
        noCompress += listOf("png", "jpg", "jpeg", "webp", "m4a", "mp4", "flac", "ogg")
    }

    packaging {
        // Prebuilt, already stripped by tools/build-qpdf-android.ps1.
        jniLibs {
            keepDebugSymbols += "**/libqpdfjni.so"
        }

        resources {
            println("[PACKAGING] Excluding license metadata files")
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/version-control-info.textproto"
            excludes += "/META-INF/com/android/build/gradle/app-metadata.properties"
        }
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    println("[COMPILE] Kotlin JVM target set to JVM_17")
}


// ============================================================================
// ABI CODES
// ============================================================================
val universal_code = 0

val abi_codes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4,
)


// ============================================================================
// ABI-SPECIFIC VERSION CODES
// ============================================================================

println("[ABI] ============================================")
println("[ABI] Setting up ABI-specific version codes...")

val seen_release_codes = mutableMapOf<Int, String>()

androidComponents {
    onVariants { variant ->
        println("[VERSION] variant=${variant.name}, buildType=${variant.buildType}, outputs=${variant.outputs.size}, base=${base_version_code}")

        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == ABI }?.identifier
            val label: String
            val suffix: Int

            if (abi == null) {
                label = "universal"
                suffix = universal_code
            } else {
                label = abi
                suffix = abi_codes[abi]
                    ?: throw GradleException("[VERSION] ABI '${abi}' has no entry in abi_codes")
            }

            val code = base_version_code * 10 + suffix
            output.versionCode.set(code)

            // Duplicate check only for release builds, since debug and release
            // are never uploaded together and may share codes safely.
            if (variant.buildType == "release") {
                val previous = seen_release_codes.put(code, "${variant.name}/${label}")
                if (previous != null) {
                    throw GradleException("[VERSION] Duplicate versionCode ${code}: ${previous} and ${variant.name}/${label}")
                }
            }

            println("[VERSION] ${variant.name}/${label}: versionCode=${code} (base=${base_version_code}, suffix=${suffix})")
        }
    }
}

println("[ABI] ============================================\n")

// ============================================================================
// DEPENDENCIES
// ============================================================================

println("[DEPS] Adding dependencies...")

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")                  // Activity for Compose
    implementation("androidx.compose.material3:material3")                      // Material Design 3 UI
    implementation("androidx.compose.ui:ui")                                    // Jetpack Compose UI framework
    implementation("androidx.core:core-ktx:1.19.1")                             // AndroidX core libraries + Kotlin essentials
    implementation("androidx.exifinterface:exifinterface:1.4.2")                // EXIF/XMP metadata for JPEG, PNG, WebP and HEIF
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")        // Lifecycle support
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")          // Lifecycle support
    implementation("com.artifex.mupdf:fitz:1.28.5")                             // MuPDF engine for PDF, EPUB, XPS, CBZ and text selection
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")   // Kotlin Coroutines for async work.
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))         // Jetpack Compose framework

    // Pure JVM tests for parsing and small data classes.
    testImplementation("junit:junit:4.13.2")

    // On-device tests use the real MuPDF native library and Android Bitmap.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

println("[DEPS] Dependencies configured [OK]")

// ============================================================================
// RELEASE ARTIFACTS
// ============================================================================

// Mirror release APKs and bundles into <repo>/build/release for easy access.
val collectReleaseArtifacts = tasks.register<Copy>("collectReleaseArtifacts") {
    group = "build"
    description = "Copies release APKs and AABs into <repo>/build/release."
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    from(layout.buildDirectory.dir("outputs/bundle/release")) { include("*.aab") }
    into(rootProject.layout.projectDirectory.dir("build/release"))
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach { finalizedBy(collectReleaseArtifacts) }
