plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.damum.daycaretemi"
    compileSdk {
        version = release(33)
    }

    defaultConfig {
        applicationId = "com.damum.daycaretemi"
        minSdk = 23
        targetSdk = 33
        versionCode = 2
        versionName = "1.1.0-demo"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // temi(RK3288)는 32비트 ARM만 지원
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    // temi: 실기기용(기본). emulator: temi SDK 없이 FakeRobotController + 개발 패널
    flavorDimensions += "device"
    productFlavors {
        create("temi") {
            dimension = "device"
            isDefault = true
        }
        create("emulator") {
            dimension = "device"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // String.join() 등 Java 8 라이브러리 호환성: API 23 (temi)
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // temi SDK는 temi 빌드에만. main 코드가 Robot을 직접 부르면 emulator 빌드에서 컴파일 에러 → RobotController만 사용
    "temiImplementation"(libs.temi.sdk)

    // 이번 버전은 카메라/영상 분석 없이 버튼과 temi SDK만 사용.
    // 이전 MediaPipe/CameraX/OkHttp AAR은 미사용이라 제외하여 로봇 APK를 단순화.
    // Java 8 라이브러리 호환성은 유지.
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
