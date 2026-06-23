import java.io.BufferedReader
import java.util.Properties
import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.kotlin.kapt")
}

val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { keystoreProperties.getProperty(it).isNullOrBlank().not() }

android {
    namespace = "com.youtv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.youtv.app"
        minSdk = 29
        targetSdk = 35
        versionCode = getVersionCode()
        versionName = getVersionName()
    }

    buildFeatures {
        compose = true
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

android.applicationVariants.all {
    outputs.all {
        (this as BaseVariantOutputImpl).outputFileName = "you-tv.apk"
    }
}

fun getTag(): String {
    return try {
        val process = Runtime.getRuntime().exec("git describe --tags --always")
        process.waitFor()
        process.inputStream.bufferedReader().use(BufferedReader::readText).trim().removePrefix("v")
            .takeIf { it.matches(Regex("\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?(?:-.*)?")) }.orEmpty()
    } catch (_: Exception) {
        ""
    }
}

fun getVersionCode(): Int {
    val metadata = rootProject.file("version.json").takeIf { it.exists() }?.readText().orEmpty()
    Regex("\"version_code\"\\s*:\\s*(\\d+)").find(metadata)?.groupValues?.get(1)?.toIntOrNull()
        ?.let { return it }
    return try {
        val arr = (getTag().replace(".", " ").replace("-", " ") + " 0").split(" ")
        arr[0].toInt() * 16777216 + arr[1].toInt() * 65536 + arr[2].toInt() * 256 + arr[3].toInt()
    } catch (_: Exception) {
        1
    }
}

fun getVersionName(): String {
    return getTag().ifEmpty {
        val metadata = rootProject.file("version.json").takeIf { it.exists() }?.readText().orEmpty()
        Regex("\"version_name\"\\s*:\\s*\"v?([^\"]+)\"").find(metadata)?.groupValues?.get(1)
            ?: "1.0.0"
    }
}

dependencies {
    implementation(libs.media3.ui)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.datasource.rtmp)

    implementation(libs.nanohttpd)
    implementation(libs.gson)
    implementation(libs.okhttp)

    implementation(libs.core.ktx)
    implementation(libs.coroutines)

    implementation(libs.lifecycle.viewmodel)

    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)

    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
}
