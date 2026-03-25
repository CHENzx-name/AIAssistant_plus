plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

android {
    repositories {
        flatDir { dirs("libs") }
    }

    namespace = "com.example.aiassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.aiassistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.preference:preference-ktx:1.2.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Retrofit - 用于执行HTTP网络请求
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    // Kotlinx Serialization - 用于解析服务器返回的JSON数据
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0") // Retrofit的转换器

    // OkHttp 日志拦截器 - 强烈建议添加，便于调试网络请求
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    // 讯飞 SparkChain ASR SDK
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("com.google.code.gson:gson:2.8.8")

    // AndroidX Activity KTX - 用于轻松地创建ViewModel实例
    implementation("androidx.activity:activity-ktx:1.9.0")
    // AndroidX ViewModel KTX - 提供viewModelScope协程作用域
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
}