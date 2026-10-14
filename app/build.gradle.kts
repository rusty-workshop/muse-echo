import java.net.URI
import java.security.MessageDigest

// Over-the-air updates need a rising versionCode: use the commit count.
val gitCommitCount: Int = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toIntOrNull() ?: 1

// The newest changes, shown once on the Echo after an update.
val whatsNew: String = providers.exec { commandLine("git", "log", "-6", "--format=%s") }
    .standardOutput.asText.get().trim().lines().filter { it.isNotBlank() }
    .joinToString("\n") { "• " + it.trim() }

val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rusty.museecho"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.rusty.museecho"
        // The Echo Show 5 runs LineageOS 18 (Android 11 / API 30).
        minSdk = 26
        targetSdk = 34
        versionCode = gitCommitCount
        versionName = "0.3.$gitCommitCount"
        buildConfigField("String", "WHATS_NEW", "\"" + whatsNew.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"")
        // The Echo Show 5 is 32-bit ARM; keeps Vosk's native libs for other ABIs out of the APK.
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildTypes {
        release {
            // The Echo Show's CPU is slow: shrink and optimize with R8 (debug
            // builds are several times slower for Compose).
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same key as the debug builds so it installs over them in place and
            // keeps the pairing.
            signingConfig = signingConfigs.getByName("debug")
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
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // WebSocket to the Muse VM, the device API, and image downloads.
    implementation(libs.okhttp)
    // QR codes (the guest Wi-Fi code).
    implementation(libs.zxing.core)
    // X25519 for the Noise handshake: Android 11's provider has no XDH, so the
    // whole protocol uses BouncyCastle's lightweight API (no JCA provider
    // registration, so it never shadows Android's own "BC").
    implementation(libs.bouncycastle)
    // Offline wake-word spotting (Vosk, Apache-2.0); JNA is its native bridge.
    implementation(libs.vosk.android) {
        exclude(group = "net.java.dev.jna")
    }
    implementation("${libs.jna.get()}@aar")
    // In-app neural voices (Piper via sherpa-onnx, Apache-2.0). The AAR isn't on
    // Maven; fetchSherpaOnnx downloads the pinned release into libs/.
    implementation(files("libs/sherpa-onnx-$sherpaVersion.aar"))
    // Unpacks the downloaded voice models (.tar.bz2).
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// The sherpa-onnx AAR (47 MB) lives outside git: fetch the pinned release once
// and check its checksum, so a fresh clone builds without manual steps.
val fetchSherpaOnnx by tasks.registering {
    val target = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile
    outputs.file(target)
    doLast {
        if (target.isFile) return@doLast
        target.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
        val tmp = File(target.path + ".part")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(tmp.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == sherpaSha256) { "sherpa-onnx AAR checksum mismatch: $digest" }
        tmp.renameTo(target)
    }
}
tasks.named("preBuild") { dependsOn(fetchSherpaOnnx) }
