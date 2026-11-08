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
        buildConfigField("String", "CHAZ_SERVER_URL", "\"${localProp("CHAZ_SERVER_URL", "http://10.0.2.2:8787")}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${localProp("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProp("SUPABASE_ANON_KEY", "")}\"")
    }

    // CarUxRestrictionsManager (DrivingGate 1순위)
    useLibrary("android.car")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            // TODO(T18): 자체 키스토어 서명. 지금은 디버그 키로 서명해 에뮬레이터 설치 가능하게 둔다.
            signingConfig = signingConfigs.getByName("debug")
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

    // Pleos SDK — 어댑터(platform/, voice/) 뒤에서만 참조한다
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
