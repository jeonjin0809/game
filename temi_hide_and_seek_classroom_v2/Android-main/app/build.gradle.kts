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
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // temi(RK3288)는 32비트 ARM만 지원
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    androidResources {
        // MediaPipe .task 모델은 압축하지 않아야 메모리 매핑으로 읽을 수 있음
        noCompress += "task"
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
        // MediaPipe가 java.util.Optional 등 API 24 클래스를 사용 → API 23(temi)용 desugaring
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // temi SDK는 temi 빌드에만. main 코드가 Robot을 직접 부르면 emulator 빌드에서 컴파일 에러 → RobotController만 사용
    "temiImplementation"(libs.temi.sdk)

    // 지금은 미사용. 온디바이스 Pose lite·카메라 옵션용으로 유지 (Manifest overrideLibrary와 짝)
    implementation(libs.mediapipe.tasks.vision)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.bundles.camerax)

    // 노트북 서버와 WebSocket 통신
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
