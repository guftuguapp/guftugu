import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Optional release signing: android/keystore.properties (git-ignored), see docs/BUILD_ANDROID.md.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

// The builder's own server, baked into their build so people only ever type an invite code.
// Set in android/local.properties (git-ignored):  guftugu.serverUrl=https://…  [guftugu.downloadUrl=https://…]
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultServerUrl: String = (localProps.getProperty("guftugu.serverUrl") ?: "").trim().trimEnd('/')
val downloadUrl: String = (localProps.getProperty("guftugu.downloadUrl") ?: "").trim()

android {
    namespace = "com.guftugu.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.guftugu.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        // WebRTC natives dominate APK size; real phones are ARM. For an x86_64 emulator build:
        //   ./gradlew assembleDebug -Pguftugu.abis=x86_64,arm64-v8a
        val abis = (project.findProperty("guftugu.abis") as String?)?.split(",")?.map { it.trim() }
            ?: listOf("arm64-v8a", "armeabi-v7a")
        ndk { abiFilters += abis }
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")
        buildConfigField("String", "DOWNLOAD_URL", "\"$downloadUrl\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Same applicationId/versionName as release so BuildConfig.VERSION_NAME == "1.0.0" is
            // what the server sees in DeviceInfo.appVersion.
            // -Pguftugu.debuggable=false: a fast, non-debuggable test build (no CheckJNI/JDWP overhead).
            isDebuggable = (project.findProperty("guftugu.debuggable") as String?)?.toBoolean() ?: true
        }
        release {
            // R8 + resource shrinking: smaller APK and a much faster cold start on budget phones.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
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
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Network / serialization / coroutines
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Images / video
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)

    // Calls
    implementation(libs.stream.webrtc.android)

    // QR
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    // Tests (pure JVM)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
