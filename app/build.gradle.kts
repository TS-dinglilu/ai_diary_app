plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.ailogapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ailogapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 只保留 arm64 架构（真机主流），减小 APK 体积
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // 模型文件不能被压缩：
    // 1. sherpa-onnx 的 .onnx 文件需 mmap 读取，压缩后无法运行
    // 2. gemma-2b-it.bin 高达 2.52GB，压缩时 AGP 会将其读入单个 byte 数组，
    //    超过 Java 数组上限 (~2.1GB) 导致 "Required array size too large"
    // 注意：不能对 "txt" 设置 noCompress，否则 AGP 8.2+ 的 stableIds.txt 中间文件会被
    // AAPT2 错误处理，导致 processDebugResources 失败（error code 13）。
    // 大模型（onnx/bin）不再打包进 APK，由用户下载到 filesDir；此处保留扩展名仅为兼容。
    androidResources {
        noCompress += listOf("onnx", "bin", "model", "bpe.model")
    }

    // 确保 native 库(.so)被解压到本地，避免 sherpa-onnx 加载失败
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // sherpa-onnx 离线语音识别引擎（本地 AAR）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // WorkManager（充电时触发转写/分析）
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Room（存储录音记录、转写、AI 分析结果）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 网络请求（调用 AI 大模型 / 云端转写）
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // JSON
    implementation("androidx.preference:preference-ktx:1.2.1")

    // MediaPipe LLM Inference（端侧大模型离线推理）
    implementation("com.google.mediapipe:tasks-genai:0.10.14")
}
