import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "fr.echecs.lunettes"
  compileSdk = 36

  defaultConfig {
    applicationId = "fr.echecs.lunettes"
    minSdk = 29
    targetSdk = 36
    val vNum = (project.findProperty("vNum") ?: "0").toString()
    versionCode = vNum.toIntOrNull() ?: 1
    versionName = vNum
    ndk { abiFilters += "arm64-v8a" }

    // Identifiants de l'appli enregistrée dans le Wearables Developer Center de Meta
    manifestPlaceholders["mwdat_application_id"] = "1082721304535859"
    manifestPlaceholders["mwdat_client_token"] = "AR|1082721304535859|778fc4b09a65289f881f65ce833bf3df"
  }

  signingConfigs {
    getByName("debug") {
      storeFile = file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    debug { signingConfig = signingConfigs.getByName("debug") }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  androidResources { noCompress += "onnx" }
  packaging {
    resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Stockfish est livré comme "libstockfish.so" : il doit être extrait sur le téléphone
    jniLibs { useLegacyPackaging = true }
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.exifinterface)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.onnxruntime.android)
  implementation("androidx.camera:camera-core:1.4.1")
  implementation("androidx.camera:camera-camera2:1.4.1")
  implementation("androidx.camera:camera-lifecycle:1.4.1")
  implementation("androidx.camera:camera-view:1.4.1")
}
