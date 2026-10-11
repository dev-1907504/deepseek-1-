import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.dreamgirl.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dreamgirl.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "0.23.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val f = keystoreProps.getProperty("storeFile")
            if (f != null) {
                storeFile = file(f)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

/**
 * 人格原文防线：任何构建之前，先逐字比对《设计方案》2.1 节。
 * 改了人格原文 → 构建失败。见 tools/verify_persona.py 与 docs/设计方案.md。
 */
val verifyPersona by tasks.registering(Exec::class) {
    group = "verification"
    description = "校验人格设定与方案 2.1 节逐字一致"
    workingDir = rootProject.projectDir
    commandLine("python3", "tools/verify_persona.py")
}

/**
 * 性格层与知识卡防线（和人格原文同一套做法）：
 *  性格层：源在 /opt/dream/persona_src/性格层.md，Kotlin 常量由 tools/extract_character.py 生成
 *  知识卡：源在 /opt/dream/persona_src/知识卡.md，Kotlin 常量由 tools/cards.py 生成
 * 手抄、手改、源被改动 → 构建失败。
 */
val verifyCharacter by tasks.registering(Exec::class) {
    group = "verification"
    description = "校验性格层与知识卡：源 sha256 + 生成物逐字节一致"
    workingDir = rootProject.projectDir
    commandLine("python3", "tools/verify_character.py")
}

val verifyCards by tasks.registering(Exec::class) {
    group = "verification"
    description = "校验知识卡：源 sha256 + 生成物逐字节一致 + 字数上限"
    workingDir = rootProject.projectDir
    commandLine("python3", "tools/verify_cards.py")
}

tasks.named("preBuild") {
    dependsOn(verifyPersona)
    dependsOn(verifyCharacter)
    dependsOn(verifyCards)
}
