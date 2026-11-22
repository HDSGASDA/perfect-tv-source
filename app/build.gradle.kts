import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.imdtv.iptvtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.imdtv.iptvtv"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.6.1"
        buildConfigField("boolean", "MANAGED", "false")
        buildConfigField("String", "STREAMING_BASE_URL", "\"https://protect.securemy.network:443\"")
        buildConfigField("String", "PANEL_BASE_URL", "\"\"")
    }

    // Keep the upstream app buildable. The managed app installs alongside it with its own signing identity.
    val panelUrl = providers.gradleProperty("panelBaseUrl").orElse("http://10.0.2.2:3000").get().trimEnd('/')
    require(panelUrl.matches(Regex("https?://[A-Za-z0-9.:-]+"))) {
        "panelBaseUrl must be an http(s) origin without a path, credentials, query or fragment"
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("standard") { dimension = "distribution" }
        create("managed") {
            dimension = "distribution"
            applicationIdSuffix = ".managed"
            versionCode = providers.gradleProperty("managedVersionCode").orElse("12").get().toInt().also { require(it > 0) }
            versionName = providers.gradleProperty("managedVersionName").orElse("0.6.1-managed.1").get()
            buildConfigField("boolean", "MANAGED", "true")
            buildConfigField("String", "PANEL_BASE_URL", "\"$panelUrl\"")
        }
    }

    // Release key, kept outside the repo: ~/.config/iptv-tv/keystore.properties (storeFile, storePassword, keyAlias,
    // keyPassword), or the file named by the IPTV_KEYSTORE_PROPERTIES environment variable. Without it, release builds
    // are signed with the debug key so anyone can build; those can't update an install signed with the release key.
    val keystoreProps = File(System.getenv("IPTV_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.config/iptv-tv/keystore.properties")
    val releaseKey = if (keystoreProps.isFile) Properties().apply { keystoreProps.inputStream().use(::load) } else null
    // Fail before producing a distributable managed APK with a local HTTP URL or debug signing key.
    val validateManagedRelease by tasks.registering {
        doLast {
            require(panelUrl.startsWith("https://")) { "Managed releases require -PpanelBaseUrl=https://your-panel-host" }
            require(releaseKey != null) { "Managed releases require your own release keystore; see MANAGED_SETUP.md" }
        }
    }
    tasks.matching { it.name == "preManagedReleaseBuild" }.configureEach { dependsOn(validateManagedRelease) }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        // Robolectric only sees merged app assets, not test assets; debug-only so release ships without them.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash) // DASH (.mpd) streams: iptv-org's BBC channels and some providers
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg)
    implementation(libs.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.smbj) // recordings on a network share
    implementation(libs.zxing.core) // QR code for the log page

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
}
