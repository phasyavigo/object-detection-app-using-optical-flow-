plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.optflow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.optflow"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Jika ingin batasi ABI yang termasuk (sesuaikan dengan folder jniLibs)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86")
        }
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

    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        compose = false // tidak pakai Compose di UI ini
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // --- jika kamu mau pakai CameraX (tidak digunakan di template ini) ---
    // val cameraxVersion = "1.3.1"
    // implementation("androidx.camera:camera-core:$cameraxVersion")
    // implementation("androidx.camera:camera-camera2:$cameraxVersion")
    // implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    // implementation("androidx.camera:camera-view:$cameraxVersion")

    // Jika kamu punya module opencv (project :opencv) gunakan ini,
    // tapi jangan pakai dua sumber native (module + jniLibs) sekaligus bila konflik:
    implementation(project(":opencv"))

    // Jika kamu tidak punya module opencv, pastikan:
    // 1) letakkan libopencv_java4.so di app/src/main/jniLibs/<abi>/
    // 2) tambahkan jar java wrapper OpenCV (jika ada) di app/libs/opencv-android.jar dan:
    // implementation(files("libs/opencv-android.jar"))
    //
    // Contoh (uncomment bila kamu menaruh jar):
    // implementation(files("libs/opencv-android.jar"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
