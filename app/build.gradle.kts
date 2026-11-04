import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ============================================================================
// VERSION CONFIGURATION
// ============================================================================

fun parse_and_validate_version(version_string: String): Triple<Int, Int, Int> {
    println("[VERSION] parse_and_validate_version version_string:$version_string")

    val parts = version_string.split(".").map(String::toInt)
    
    require(parts.size == 3) {
        "appVersionName must have three parts (major.minor.patch), got: $version_string"
    }
    println("[VERSION] Parts: major=${parts[0]}, minor=${parts[1]}, patch=${parts[2]}")
    
    require(parts.all { it >= 0 }) {
        "All version parts must be non-negative"
    }
    println("[VERSION] All parts are non-negative ✓")
    
    require(parts[1] in 0..99 && parts[2] in 0..99) {
        "Minor and patch must be 0–99, got: ${parts[1]}.${parts[2]}"
    }
    println("[VERSION] Minor and patch in valid range (0–99) ✓")
    
    return Triple(parts[0], parts[1], parts[2])
}

fun calculate_version_code(major: Int, minor: Int, patch: Int): Int {
    println("[VERSION] calculate_version_code major:$major, minor:$minor, patch:$patch")
    
    val base_version_code = major * 10_000 + minor * 100 + patch
    println("[VERSION] Base version code: $base_version_code")
    
    val app_version_code = base_version_code * 10
    println("[VERSION] App version code (with APK type suffix): $app_version_code")
    
    require(app_version_code in 1..2_100_000_000) {
        "Generated versionCode is outside Android's supported range: $app_version_code"
    }
    println("[VERSION] Version code is within valid range ✓")
    
    return base_version_code
}

val app_version_name = "26.11.4"
println("[VERSION] ============================================")
val (version_major, version_minor, version_patch) = parse_and_validate_version(app_version_name)
val base_version_code = calculate_version_code(version_major, version_minor, version_patch)
val app_version_code = base_version_code * 10
println("[VERSION] ============================================\n")

// ============================================================================
// DEPENDENCY EXCLUSIONS
// ============================================================================

println("[DEPENDENCIES] Excluding BouncyCastle (not needed for password flow)")

configurations.configureEach {
    exclude(group = "org.bouncycastle")
}

// ============================================================================
// KEYSTORE CONFIGURATION
// ============================================================================

data class KeystoreConfig(
    val store_file: String,
    val store_password: String,
    val key_alias: String,
    val key_password: String
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
            println("[KEYSTORE] Found '$property_key' in keystore.properties file")
            return from_file
        }
        
        val from_env = System.getenv(env_key)
        if (from_env != null) {
            println("[KEYSTORE] Found '$env_key' in environment variables")
            return from_env
        }
        
        println("[KEYSTORE] '$property_key' / '$env_key' not found anywhere")
        return null
    }
    
    val store_file = get_value("keystore_file", "PDFR_KEYSTORE_FILE")
    
    if (store_file == null) {
        println("[KEYSTORE] Keystore file not configured. Using debug signing for release builds.")
        println("[KEYSTORE] ============================================\n")
        return null
    }
    
    println("[KEYSTORE] Keystore file configured: $store_file")
    
    val store_password = get_value("keystore_file_password", "PDFR_KEYSTORE_FILE_PASSWORD")
    require(store_password != null) {
        "keystore_file_password not found in keystore.properties or PDFR_KEYSTORE_FILE_PASSWORD env var"
    }
    println("[KEYSTORE] Keystore password configured ✓")
    
    val key_alias = get_value("key_alias", "PDFR_KEY_ALIAS")
    require(key_alias != null) {
        "key_alias not found in keystore.properties or PDFR_KEY_ALIAS env var"
    }
    println("[KEYSTORE] Key alias configured: $key_alias ✓")
    
    val key_password = get_value("key_password", "PDFR_KEY_PASSWORD")
    require(key_password != null) {
        "key_password not found in keystore.properties or PDFR_KEY_PASSWORD env var"
    }
    println("[KEYSTORE] Key password configured ✓")
    
    println("[KEYSTORE] All keystore credentials loaded successfully ✓")
    println("[KEYSTORE] ============================================\n")
    
    return KeystoreConfig(
        store_file = store_file,
        store_password = store_password,
        key_alias = key_alias,
        key_password = key_password
    )
}

val keystore_config = load_keystore_config()

// ============================================================================
// ABI CODES
// ============================================================================

val abi_codes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4,
)

// ============================================================================
// ANDROID CONFIGURATION
// ============================================================================

android {
    namespace = "com.karako.pdfr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.karako.pdfr"
        minSdk = 24
        targetSdk = 36
        versionCode = app_version_code
        versionName = app_version_name

        println("[ANDROID] App configured: version=$app_version_name (code=$app_version_code)")

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
                storeFile = rootProject.file(keystore_config.store_file)
                storePassword = keystore_config.store_password
                keyAlias = keystore_config.key_alias
                keyPassword = keystore_config.key_password
            }
            
            println("[SIGNING] Release signing config created ✓")
        } else {
            println("[SIGNING] No keystore configured. Release builds will use debug signing.")
        }
    }

    buildTypes {
        release {
            println("[BUILD] Configuring release build type...")
            
            isMinifyEnabled = true
            println("[BUILD] Minification enabled")
            
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
            println("[BUILD] ProGuard rules configured")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        println("[COMPILE] Java compatibility set to VERSION_17")
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        println("[COMPILE] Kotlin JVM target set to JVM_17")
    }

    buildFeatures {
        compose = true
        println("[BUILD] Jetpack Compose enabled")
    }

    androidResources {
        println("[RESOURCES] Optimizing resources...")
        println("[RESOURCES] Excluding PDFBox CMap/font assets (~4 MB)")
        
        ignoreAssetsPatterns += "!com"
        
        println("[RESOURCES] Storing binary files uncompressed (already compressed)")
        noCompress += listOf("png", "jpg", "jpeg", "webp", "m4a", "mp4", "flac", "ogg")
    }

    packaging {
        resources {
            println("[PACKAGING] Excluding license metadata files")
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ============================================================================
// ABI-SPECIFIC VERSION CODES
// ============================================================================

println("[ABI] ============================================")
println("[ABI] Setting up ABI-specific version codes...")

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == ABI }
                ?.identifier

            if (abi != null) {
                val abi_code = abi_codes[abi]
                if (abi_code != null) {
                    val final_version_code = base_version_code * 10 + abi_code
                    output.versionCode.set(final_version_code)
                    println("[ABI] $abi: version code = $final_version_code (base=$base_version_code + suffix=$abi_code)")
                } else {
                    println("[ABI] $abi: ABI code not found in mapping (skipped)")
                }
            } else {
                println("[ABI] Universal APK: version code = $app_version_code (suffix=0)")
            }
        }
    }
}

println("[ABI] ============================================\n")

// ============================================================================
// DEPENDENCIES
// ============================================================================

println("[DEPS] Adding dependencies...")

dependencies {
  implementation("androidx.activity:activity-compose:1.9.3")                  // Activity for Compose
  implementation("androidx.compose.material3:material3")                      // Material Design 3 UI
  implementation("androidx.compose.ui:ui")                                    // Jetpack Compose UI framework
  implementation("androidx.core:core-ktx:1.15.0")                             // AndroidX core libraries + Kotlin essentials
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")        // Lifecycle support
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")          // Lifecycle support
  implementation("com.tom-roush:pdfbox-android:2.0.27.0")                     // PDFBox for decryption and metadata extraction
  implementation("io.github.afreakyelf:Pdf-Viewer:2.4.0")                     // PDF viewer library (uses Android's PdfRenderer)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")   // Kotlin Coroutines for async work.
  implementation(platform("androidx.compose:compose-bom:2024.12.01"))         // Jetpack Compose framework
}


println("[DEPS] Dependencies configured ✓")
