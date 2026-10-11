import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.feldkit"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.feldkit"
        minSdk = 29
        targetSdk = 37
        // CI passes the tag's number (e.g. v1.2.3 -> -PversionCode=10203 -PversionName=1.2.3); local builds stay at 1
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Debug builds carry arm64 only (libVLC ships every ABI, 80 MB); a release build asks for -PallAbis and gets one APK per ABI
        if (!project.hasProperty("allAbis")) ndk { abiFilters += "arm64-v8a" }
    }
    splits {
        abi {
            isEnable = project.hasProperty("allAbis")
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    // The release key comes from the environment (GitHub Actions secrets) or keystore.properties; without either the build is unsigned.
    signingConfigs {
        create("release") {
            val props = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
            fun v(env: String, key: String) = System.getenv(env) ?: props.getProperty(key)
            val file = v("FELDKIT_KEYSTORE_FILE", "storeFile")
            if (file != null) {
                storeFile = rootProject.file(file); storePassword = v("FELDKIT_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = v("FELDKIT_KEY_ALIAS", "keyAlias"); keyPassword = v("FELDKIT_KEY_PASSWORD", "keyPassword")
            }
        }
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            optimization { enable = true }
            // -PdebuggableRelease: an optimised build that tools/phone-image.sh can still feed disk images (never published)
            isDebuggable = project.hasProperty("debuggableRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                it.systemProperty("fixtures.dir", layout.buildDirectory.dir("fixtures").get().asFile.path)
                it.maxHeapSize = "2g"
                if (project.hasProperty("vxlog")) it.systemProperty("vx.log", "1")
                it.testLogging.showStandardStreams = project.hasProperty("vxlog")
                it.testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("org.videolan.android:libvlc-all:3.7.7")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("dev.chrisbanes.haze:haze:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
    implementation("dev.chrisbanes.haze:haze-glass:2.0.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}