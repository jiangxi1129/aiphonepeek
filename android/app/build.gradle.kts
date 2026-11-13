import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布签名：钥匙绝不进仓库。两种来源，任选其一：
//  1) 环境变量 KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD（CI 用）
//  2) android/keystore.properties（本机用，已在 .gitignore）：storeFile= storePassword= keyAlias= keyPassword=
// 都没有就用 debug 签名（能装能用，但每次换签名都得卸载重装，不适合长期自用）。
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun ks(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() } ?: ksProps.getProperty(propName)
val ksFile = ks("KEYSTORE_FILE", "storeFile")
val hasReleaseKey = ksFile != null && file(ksFile).exists()

android {
    namespace = "io.github.aiphonepeek"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.aiphonepeek"
        minSdk = 26
        targetSdk = 34
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0.0"
        resValue("string", "app_name", (findProperty("appName") as String?) ?: "AI Phone Peek")
        buildConfigField("String", "VERSION", "\"$versionName\"")
    }
    buildFeatures { buildConfig = true }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = ks("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = ks("KEY_ALIAS", "keyAlias")
                keyPassword = ks("KEY_PASSWORD", "keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
}
