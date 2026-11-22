import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProps = Properties().apply {
    val propsFile = rootProject.file("keystore/signing.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

val appVersionCode = (project.findProperty("versionCode")?.toString()?.toIntOrNull()) ?: 1
val appVersionName = (project.findProperty("versionName")?.toString()?.takeIf { it.isNotBlank() }) ?: "1.0.0"

android {
    namespace = "com.helpdesk.jiraagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.helpdesk.jiraagent"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/helpdesk-release.p12")
            storeType = signingProps.getProperty("storeType", "PKCS12")
            storePassword = signingProps.getProperty("storePassword", "HelpdeskSign2026!")
            keyAlias = signingProps.getProperty("keyAlias", "helpdesk-release")
            keyPassword = signingProps.getProperty("keyPassword", "HelpdeskSign2026!")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

configurations.all {
    resolutionStrategy {
        // Prevent biometric:1.1.0 / zxing-android-embedded from pulling legacy fragment:1.2.5
        // whose 16-bit checkForValidRequestCode crashes modern ActivityResultRegistry (>0xFFFF codes).
        force(
            "androidx.fragment:fragment:1.8.5",
            "androidx.fragment:fragment-ktx:1.8.5"
        )
    }
}
