import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val appVersionName = rootProject.file("VERSION").also {
    require(it.exists()) { "VERSION file is missing at ${it.absolutePath}" }
}.readText().trim().also {
    require(it.isNotEmpty()) { "VERSION file is empty" }
}
val appVersionCode = appVersionName.substringBefore('+').substringBefore('-').split('.').let { p ->
    require(p.size == 3) { "Invalid semantic version in VERSION: '$appVersionName' (expected MAJOR.MINOR.PATCH)" }
    val maj = p[0].toInt()
    val min = p[1].toInt()
    val pat = p[2].toInt()
    require(maj >= 0 && min in 0..99 && pat in 0..99) {
        "Version numbers out of bounds in VERSION: '$appVersionName' (expected major>=0, minor/patch in 0..99)"
    }
    maj * 10000 + min * 100 + pat
}

android {
    namespace = "com.gravityssh.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gravityssh.app"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "APP_VERSION", "\"$appVersionName\"")
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
        if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASS") ?: "gravityssh"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "gravityssh"
                keyPassword = System.getenv("ANDROID_KEY_PASS") ?: "gravityssh"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val relSign = signingConfigs.findByName("release")
            if (relSign != null) {
                signingConfig = relSign
            }
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
        dex {
            // Re-enable APK-level DEX compression (reclaims ~3.7 MB of uncompressed DEX bloat on minSdk >= 29)
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/9/module-info.class",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/BCKEY.DSA",
                "META-INF/BCKEY.SF",
                "META-INF/BC1024KE.DSA",
                "META-INF/BC1024KE.SF",
                "META-INF/BC2048KE.DSA",
                "META-INF/BC2048KE.SF",
                // Exclude ~1.21 MB of unused BouncyCastle Picnic PQC lookup tables
                "org/bouncycastle/pqc/**"
            )
        }
    }
    lint {
        disable += setOf("InvalidPackage")
        checkReleaseBuilds = true
        abortOnError = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SSHJ + BouncyCastle + Ed25519
    implementation("com.hierynomus:sshj:0.39.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    runtimeOnly("net.i2p.crypto:eddsa:0.3.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    // QR Code generation & CameraX scanning
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
