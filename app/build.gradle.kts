import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// PDFBox only uses BouncyCastle for certificate/public-key security handlers.
// Leaf's password flow goes through the standard handler, which uses the
// platform's own JCE, so the three BouncyCastle jars would be dead weight
// (several megabytes of post-quantum tables alone).
configurations.configureEach {
    exclude(group = "org.bouncycastle")
}

// Release signing is read from keystore.properties (git-ignored) or from
// environment variables so CI can sign without a secrets file.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun signingValue(property: String, env: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "LEAF_KEYSTORE_FILE")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.leaf.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.leaf.reader"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("storePassword", "LEAF_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "LEAF_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "LEAF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The library ships its own consumer ProGuard rules. Minifying keeps
            // the APK close to the library's own footprint.
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // PDFBox ships roughly 4 MB of CMap/font assets for its own text
        // extraction and rendering helpers. Leaf only parses dictionaries and
        // saves decrypted copies with PDFBox, so those resources are never
        // opened; dropping them keeps the APK close to its previous size.
        ignoreAssetsPatterns += "!com"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // The whole PDF engine: no native payload, it draws with Android's PdfRenderer.
    implementation("io.github.afreakyelf:Pdf-Viewer:2.4.0")

    // Android's PdfRenderer has no password parameter. PDFBox decrypts protected
    // files into a cache copy (and reads their metadata) before the viewer sees
    // them. It is not used for rendering.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
