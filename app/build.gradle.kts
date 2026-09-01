import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

// Подпись релиза читается из keystore.properties (в .gitignore). Если файла нет —
// релиз локально подписывается debug-ключом, чтобы сборка не падала.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasReleaseKeystore) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.tennis.analyzer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tennis.analyzer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Только arm64: ONNX/QNN есть лишь под arm64, остальные ABI = мёртвый вес.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // Не паковать дублирующиеся .so из MediaPipe и TFLite
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += "**/libOpenCL.so"
        // Распаковывать .so на диск — обход проверки выравнивания ELF на Android 15
        jniLibs.useLegacyPackaging = true
        // Выкидываем старые/ненужные QNN-бэкенды (экономия ~30 МБ). Оставляем HTP
        // V73/V75/V79/V81 (Snapdragon 8 Gen1/Gen2/Gen3/8 Elite). Старые устройства → CPU.
        jniLibs.excludes += setOf(
            "**/libQnnHtpV68Skel.so", "**/libQnnHtpV68Stub.so",
            "**/libQnnHtpV69Skel.so", "**/libQnnHtpV69Stub.so",
            "**/libQnnDspV66Skel.so", "**/libQnnDspV66Stub.so",
            "**/libQnnDsp.so", "**/libQnnGpu.so"
        )
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // CameraX
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")

    // ExoPlayer (замедленное воспроизведение)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // MediaPipe Pose Landmarker
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // TensorFlow Lite (для нашей LSTM модели)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // ONNX Runtime с QNN EP для Snapdragon Hexagon NPU (включает базовый ORT)
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.26.0")

    // Room DB (история тренировок)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Канал связи с часами (Wear OS компаньон через Data Layer)
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // Реклама (Yandex Ads) для бесплатной версии.
    // AdMob не подходит: Google не даёт создавать новые AdMob-аккаунты из России
    // (санкции OFAC), а RuStore-аудитория — российская. Yandex Ads работает без VPN.
    implementation("com.yandex.android:mobileads:8.4.0")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // AppMetrica (Yandex) — сбор крашей и базовой аналитики использования.
    // Выбран вместо Firebase Crashlytics: работает без VPN у российской аудитории RuStore.
    implementation("io.appmetrica.analytics:analytics:8.5.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
