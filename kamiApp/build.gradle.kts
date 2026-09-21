plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.kami.app"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.kami.app"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        // Bump versionCode/versionName on every release.
        versionCode = 30
        versionName = "2.1"
    }

    // Debug builds are signed with the keystore committed at keystore/
    // (PKCS12, password "android", same key as Kami Manager) so each new
    // APK installs as a direct upgrade instead of failing on a signature
    // mismatch with the runner's ephemeral debug key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        // Local JVM unit tests call pure logic only; unmocked android.jar
        // methods just return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // JVM unit tests (run by 'Kami App' workflow): kotlin-test assertions +
    // a real org.json so JSONObject/JSONArray behave like on-device.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.20")
    testImplementation("org.json:json:20240305")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.compose.material3)
    implementation(libs.androidx.foundation.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.shizuku.aidl)
}
