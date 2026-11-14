import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 비밀값·환경값은 local.properties 에서만 읽는다 (커밋 금지). 예시는 local.properties.example
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String) = (localProps.getProperty(key) ?: default).trim()

android {
    namespace = "ai.chaz"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ai.chaz"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 로컬 개발 서버(Edge Function 대체). 에뮬레이터에서 호스트 PC = 10.0.2.2
        // 실차(arm64)와 에뮬레이터(x86_64)만 — MediaPipe 네이티브 라이브러리 용량을 줄인다
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        buildConfigField("String", "CHAZ_SERVER_URL", "\"${localProp("CHAZ_SERVER_URL", "http://10.0.2.2:8787")}\"")
        // 서버 외부 공개(도메인) 시 차량 전용·AI 경로용 앱 토큰. server/.env 의 CHAZ_APP_TOKEN 과 같아야 한다
        buildConfigField("String", "CHAZ_APP_TOKEN", "\"${localProp("CHAZ_APP_TOKEN", "")}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${localProp("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProp("SUPABASE_ANON_KEY", "")}\"")
    }

    // 저장소 루트 assets/ (bgm, tts, frames, exterior-mock) 를 앱 assets 로 포함
    sourceSets {
        getByName("main") {
            assets.srcDir("../../assets")
        }
    }

    // CarUxRestrictionsManager (DrivingGate 1순위)
    useLibrary("android.car")

    // MediaPipe 는 모델을 메모리 매핑하므로 압축하지 않는다
    androidResources {
        noCompress += "tflite"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 자체 키스토어 서명: 경로·비밀번호는 local.properties 에서만 (예시는 local.properties.example).
    // 값이 없으면(클론 직후 등) 디버그 키로 서명해 release 빌드 자체는 되게 둔다.
    val releaseStore = localProp("RELEASE_STORE_FILE", "").takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = localProp("RELEASE_STORE_PASSWORD", "")
            keyAlias = localProp("RELEASE_KEY_ALIAS", "chaz")
            keyPassword = localProp("RELEASE_KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            // R8: 안 쓰는 코드 제거만 (APK 105MB → GitHub 파일 한도 100MB 아래로). 이름 변경·최적화는
            // proguard-rules.pro 에서 끈다 — 11/10 packageScope 부분 최적화 때 IllegalAccessError 크래시 실측.
            // Pleos SDK·MediaPipe·JNA·ai.chaz 는 통째로 keep.
            optimization {
                enable = true
                keepRules {
                    @Suppress("DEPRECATION")
                    files.add(file("proguard-rules.pro"))
                }
            }
            signingConfig = if (releaseStore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.zxing.core) // 페어링 QR

    // Pleos SDK — 어댑터(platform/, voice/) 뒤에서만 참조한다
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
