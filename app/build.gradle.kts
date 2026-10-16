import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    signingConfigs {
        create("Release") {
            val propertiesFile = rootProject.file("signing.properties")
            if (propertiesFile.exists()) {
                val properties = Properties()
                properties.load(FileInputStream(propertiesFile))

                storeFile = file(properties["KEYSTORE_FILE"] as String)
                storePassword = properties["KEYSTORE_PASSWORD"] as String
                keyAlias = properties["KEY_ALIAS"] as String
                keyPassword = properties["KEY_PASSWORD"] as String
            } else {
                println("Warning: signing.properties file not found!")
            }
        }
    }
    namespace = "com.immichframe.immichframe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.immichframe.immichframe"
        minSdk = 23
        targetSdk = 36
        versionCode = 67
        versionName = "1.0.67.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "ImmichFrame"
        manifestPlaceholders["homeCategory"] = "android.intent.category.HOME"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "ImmichFrame Debug"
            manifestPlaceholders["homeCategory"] = "android.intent.category.HOME"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CI intentionally produces an unsigned release artifact; production credentials are
            // only used by explicitly configured local/release environments.
            signingConfig = if (rootProject.file("signing.properties").exists()) {
                signingConfigs.getByName("Release")
            } else {
                null
            }
        }
        // Optimized, non-debuggable build for the dedicated frames. Deployed frames run the
        // .debug package signed with the build machine's debug key; keeping both lets this build
        // upgrade them in place with their settings and HOME role intact, and keeps the existing
        // debug builds valid rollbacks. That debug keystore is therefore the frames' release key.
        create("frame") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-frame"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            manifestPlaceholders["appLabel"] = "ImmichFrame"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.retrofit)
    implementation(libs.retrofitgson)
    implementation(libs.nanohttpd)
    implementation(libs.paho.mqtt)
    implementation(libs.androidx.preference)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
