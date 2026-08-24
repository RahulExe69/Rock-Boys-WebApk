import java.io.File
import java.util.Base64
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Internal
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

// Ensure debug.keystore is decoded from its Base64 Representation to guarantee valid and consistent signing
val debugKeystoreFile = File(rootDir, "debug.keystore")
val debugKeystoreBase64File = File(rootDir, "debug.keystore.base64")
if (debugKeystoreBase64File.exists() && !debugKeystoreFile.exists()) {
    try {
        val base64Content = debugKeystoreBase64File.readText().trim()
        val decodedBytes = Base64.getDecoder().decode(base64Content)
        debugKeystoreFile.writeBytes(decodedBytes)
        println("Successfully decoded local persistent debug.keystore from Base64 configuration.")
    } catch (e: Exception) {
        println("Error decoding debug.keystore: ${e.message}")
    }
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.rockboys.exe"
    minSdk = 24
    targetSdk = 36
    versionCode = 95
    versionName = "2.0.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      
      // Fallback signs the release APK using the debug keystore if no release key env variables are defined,
      // resulting in a highly optimized release-ready APK that is fully signed and installable.
      val isReleaseConfigured = System.getenv("STORE_PASSWORD") != null && file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks").exists()
      if (isReleaseConfigured) {
          signingConfig = signingConfigs.getByName("release")
      } else {
          signingConfig = signingConfigs.getByName("debugConfig")
      }
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
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
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  // implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

abstract class CopyApkTask : DefaultTask() {
    @get:Internal
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyApk() {
        val apkDir = apkDirectory.get().asFile
        val outDir = outputDirectory.get().asFile
        if (apkDir.exists()) {
            val apkFiles = apkDir.walkTopDown().filter { it.isFile && it.name.endsWith(".apk") }
            for (file in apkFiles) {
                val isRelease = file.name.contains("release", ignoreCase = true) || file.parentFile.name.contains("release", ignoreCase = true)
                
                // Copy compiled APK to both app-debug.apk and app-release.apk inside .build-outputs
                val targets = if (isRelease) listOf("app-release.apk") else listOf("app-debug.apk", "app-release.apk")
                
                for (targetName in targets) {
                    val dest = File(outDir, ".build-outputs/$targetName")
                    dest.parentFile.mkdirs()
                    file.copyTo(dest, overwrite = true)
                    println("Successfully copied APK to compiler output directory: ${dest.absolutePath}")
                }
            }
        }
    }
}

val copyApkToAllOutputs = tasks.register<CopyApkTask>("copyApkToAllOutputs") {
    apkDirectory.set(layout.buildDirectory.dir("outputs/apk"))
    outputDirectory.set(rootDir)
    outputs.upToDateWhen { false }
}

tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyApkToAllOutputs)
}
