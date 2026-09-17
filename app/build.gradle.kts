plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lanu.anc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lanu.anc"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O2 -DNDEBUG"
            }
        }
    }

    buildTypes {
        release {
            // JNI entry points intentionally keep the class/method names expected by the native AAudio layer.
            // Release optimization can be enabled once a production signing + R8 keep-rule set is introduced.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.2.12479018"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
