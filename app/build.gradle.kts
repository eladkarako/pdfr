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
// Set the app version here. Format: major.minor.patch.
// Example: 26.11.3 becomes base version code 261103.
val appVersionName = "26.11.3"
val versionParts = appVersionName.split(".").map(String::toInt)

require(versionParts.size == 3) {
    "appVersionName must have three parts, like 26.11.3"
}
require(versionParts.all { it >= 0 }) {
    "Version parts must be non-negative"
}
require(versionParts[1] in 0..99 && versionParts[2] in 0..99) {
    "Minor and patch version parts must each be between 0 and 99"
}

// Pack major.minor.patch into an integer:
// 26 * 10,000 + 11 * 100 + 3 = 261103
val baseVersionCode =
    versionParts[0] * 10_000 +
        versionParts[1] * 100 +
        versionParts[2]

// Reserve the final digit for the APK type:
// 0 = universal APK; 1–4 = ABI-specific APKs.
val appVersionCode = baseVersionCode * 10

require(appVersionCode in 1..2_100_000_000) {
    "Generated versionCode is outside Android's supported range"
}

// ============================================================================
// DEPENDENCY EXCLUSIONS
// ============================================================================
// PDFBox only uses BouncyCastle for certificate/public-key security handlers.
// PDFr's password flow uses the standard handler (platform JCE), so 
// BouncyCastle is dead weight. Excluding it saves several MB of APK size.
configurations.configureEach {
    exclude(group = "org.bouncycastle")
}

// ============================================================================
// KEYSTORE CONFIGURATION
// ============================================================================
// Load signing credentials from keystore.properties file (git-ignored)
// or from environment variables (for CI/CD pipelines)
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { input ->
        keystoreProperties.load(input)
    }
}

// Helper function to get signing values from properties file or env vars
fun signingValue(property: String, env: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(env)

// Get the keystore file path WITHOUT checking if it exists yet.
// This allows the task to create it if missing.
val releaseStoreFilePath = signingValue("storeFile", "PDFR_KEYSTORE_FILE")
    ?.let { rootProject.file(it) }

// Validate signing credentials early if keystore is configured
if (releaseStoreFilePath != null) {
    require(signingValue("keyAlias", "PDFR_KEY_ALIAS") != null) {
        "keyAlias must be set in keystore.properties or PDFR_KEY_ALIAS env var"
    }
    require(signingValue("storePassword", "PDFR_KEYSTORE_PASSWORD") != null) {
        "storePassword must be set in keystore.properties or PDFR_KEYSTORE_PASSWORD env var"
    }
    require(signingValue("keyPassword", "PDFR_KEY_PASSWORD") != null) {
        "keyPassword must be set in keystore.properties or PDFR_KEY_PASSWORD env var"
    }
}

// For use in signingConfigs, check if the file exists NOW
// (it will be re-evaluated after the keystore task runs)
val releaseStoreFile = releaseStoreFilePath?.takeIf { it.exists() }

// ABI-specific version-code suffixes.
// Keep these stable across releases so the same ABI always has the same suffix.
val abiCodes = mapOf(
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

        // Base code is used by the universal APK.
        versionCode = appVersionCode
        versionName = appVersionName

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Create one APK per ABI, plus a universal APK containing all ABIs.
    // This reduces file size for users by letting them download only their architecture.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // Signing configuration for release builds
    signingConfigs {
        // Only create the release config if we have a valid keystore file
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("storePassword", "PDFR_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "PDFR_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "PDFR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Minify and shrink resources to keep APK size small
            // PDFBox and the viewer library provide their own ProGuard rules
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    // ============================================================================
    // RESOURCE OPTIMIZATION
    // ============================================================================
    androidResources {
        // PDFBox ships ~4 MB of CMap/font assets for text extraction/rendering.
        // PDFr only uses PDFBox for decryption and metadata, not rendering,
        // so these resources are unused. Excluding them keeps APK smaller.
        ignoreAssetsPatterns += "!com"

        // Store binary files uncompressed to avoid unnecessary APK changes
        // and keep builds reproducible. These formats are already compressed,
        // so storing them uncompressed prevents re-compression on each build.
        noCompress += listOf(
            "png",      // PNG images
            "jpg",      // JPEG images
            "jpeg",     // JPEG images (alternate extension)
            "webp",     // WebP images
            "m4a",      // Audio files (MP4 audio)
            "mp4",      // Video files
            "flac",     // FLAC audio
            "ogg"       // Ogg Vorbis audio
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ============================================================================
// KEYSTORE AUTO-CREATION TASK
// ============================================================================
// This task creates the keystore file if it's missing.
// It runs before every build (via preBuild dependency).
// If the keystore already exists, it does nothing.
tasks.register("createKeystoreIfMissing") {
    doLast {
        val keystorePath = releaseStoreFilePath
        
        // Check if we need to create the keystore
        if (keystorePath != null && !keystorePath.exists()) {
            // Ensure the directory exists
            keystorePath.parentFile?.mkdirs()
            
            // Locate keytool in the JDK (not relying on system PATH)
            val javaHome = System.getProperty("java.home")
            val keytoolPath = if (System.getProperty("os.name").contains("Windows")) {
                "$javaHome\\bin\\keytool.exe"
            } else {
                "$javaHome/bin/keytool"
            }
            
            println("🔧 Creating keystore at: ${keystorePath.absolutePath}")
            println("📍 Using keytool from: $keytoolPath")
            
            // Run keytool to generate the keystore
            val command = listOf(
                keytoolPath       ,
                "-genkey"         ,  "-v",
                "-keystore"       ,  keystorePath.absolutePath,
                "-keyalg"         ,  "RSA",
                "-keysize"        ,  "2048",
                "-sigalg"         ,  "SHA256withRSA",
                "-deststoretype"  ,  "PKCS12",
                "-validity"       ,  "10000",
                "-alias"          ,  signingValue("keyAlias", "PDFR_KEY_ALIAS") ?: "pdfr",
                "-storepass"      ,  signingValue("storePassword", "PDFR_KEYSTORE_PASSWORD") ?: "password",
                "-keypass"        ,  signingValue("keyPassword", "PDFR_KEY_PASSWORD") ?: "password",
                "-dname"          ,  "CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, S=Unknown, C=US",
            )

            val process = ProcessBuilder(command)
                .inheritIO()
                .start()

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("keytool failed with exit code $exitCode")
            }

            // Actually verify the keystore file exists
            if (keystorePath.exists()) {
                println("✅ Keystore created successfully!")
            } else {
                throw GradleException("❌ Keystore creation failed—file not found at ${keystorePath.absolutePath}")
            }

        } else if (keystorePath != null && keystorePath.exists()) {
            println("✅ Keystore already exists at: ${keystorePath.absolutePath}")
        } else {
            println("⚠️ No keystore configured (PDFR_KEYSTORE_FILE not set)")
        }
    }
}

// Hook the keystore creation task into the build process
// It runs before preBuild, which runs before any compilation
tasks.named("preBuild").configure {
    dependsOn("createKeystoreIfMissing")
}

// ============================================================================
// ABI-SPECIFIC VERSION CODES
// ============================================================================
// Give each architecture APK a unique versionCode so the Play Store can
// distinguish between them. The universal APK gets the base code (ends in 0).
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == ABI }
                ?.identifier

            val abiCode = abiCodes[abi]
            if (abiCode != null) {
                // ABI APKs get: baseVersionCode * 10 + abiCode (1, 2, 3, or 4)
                output.versionCode.set(baseVersionCode * 10 + abiCode)
            }
        }
    }
}

// ============================================================================
// DEPENDENCIES
// ============================================================================
dependencies {
    // AndroidX core libraries
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // Jetpack Compose UI framework
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // Kotlin coroutines for async work
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // PDF viewer library (uses Android's PdfRenderer for rendering)
    implementation("io.github.afreakyelf:Pdf-Viewer:2.4.0")

    // PDFBox for decryption and metadata extraction
    // (NOT used for rendering—that's handled by Android's PdfRenderer)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}

