import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "br.com.metalav.nexuspos"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "br.com.nexuspayments.pos"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "2.0-telas"

        // Defaults (ainda vamos mover isso depois para DataStore/Config screen)
        buildConfigField("String", "BASE_URL", "\"https://ci.metalav.com.br\"")
        buildConfigField("String", "POS_SERIAL", "\"POS-TESTE-001\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // --- Signing (Release) via keystore.properties ---
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (!propsFile.exists()) {
                throw GradleException("keystore.properties não encontrado na raiz do projeto: ${propsFile.absolutePath}")
            }

            val props = Properties().apply {
                propsFile.inputStream().use { load(it) }
            }

            val storeFilePath = props.getProperty("storeFile") ?: ""
            val storePass = props.getProperty("storePassword") ?: ""
            val keyAliasProp = props.getProperty("keyAlias") ?: ""
            val keyPass = props.getProperty("keyPassword") ?: ""

            if (storeFilePath.isBlank() || storePass.isBlank() || keyAliasProp.isBlank() || keyPass.isBlank()) {
                throw GradleException("keystore.properties incompleto. Precisa de storeFile, storePassword, keyAlias, keyPassword.")
            }

            storeFile = rootProject.file(storeFilePath)
            storePassword = storePass
            keyAlias = keyAliasProp
            keyPassword = keyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            // debug usa debug keystore automaticamente
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
}

