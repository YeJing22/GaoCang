import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use(::load)
}
fun propOrEnv(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }

val targetCompileSdk = providers.gradleProperty("compileSdk").orNull?.toIntOrNull() ?: 37

android {
    namespace = "com.yejing.artvault"
    compileSdk = targetCompileSdk
    if (targetCompileSdk == 37) {
        // core-ktx 1.19.1 / Compose 1.12.1 最低要求 compileSdk 37，对应 platforms;android-37.2
        compileSdkMinor = 2
    }

    defaultConfig {
        applicationId = "com.yejing.artvault"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.0"
    }

    signingConfigs {
        val storePath = propOrEnv("RELEASE_STORE_FILE")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = propOrEnv("RELEASE_STORE_PASSWORD")
                keyAlias = propOrEnv("RELEASE_KEY_ALIAS")
                keyPassword = propOrEnv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    // GIF 解码是 Coil 的可选组件：不注册就只解第一帧（库里的 .gif 稿能动起来靠它）
    implementation(libs.coil.gif)
    // 头像裁剪：成熟的 CropImageView 负责跟手拖动、双指中心缩放、EXIF 与边界约束
    implementation("com.vanniktech:android-image-cropper:4.7.0")
    // 网格拖拽排序：社区事实标准。自动滚动、让位动画、边界与帧同步都是它内置的，
    // 自己手写这套踩过 ANR 和不滚动的坑（见 TaxonomyScreen 注释）。
    implementation("sh.calvin.reorderable:reorderable:2.5.1")
    // Google Photos 式滑动多选：长按起手、手指划过即选、贴边自动滚。
    // 只用 core（Modifier.gridDragSelect），不用它的 grid 包装（那套会替换我们的网格与卡片结构）。
    implementation("com.dragselectcompose:core:3.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
