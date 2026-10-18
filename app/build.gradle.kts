plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "ir.diban.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.diban.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.5.0"

        ndk {
            // Chaquopy + hev-socks5-tunnel are built for these ABIs.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                // JNI class that hev-socks5-tunnel registers its natives on:
                // ir.diban.android.vpn.TProxyService
                cFlags("-O3", "-DPKGNAME=ir/diban/android/vpn", "-DCLSNAME=TProxyService")
                arguments("APP_PLATFORM=android-24", "APP_OPTIM=release", "REV_ID=2.19.0")
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign with your own key for distribution; debug key keeps `assembleRelease` installable.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // Required so the proxy's native deps are extracted normally (Chaquopy needs this).
            useLegacyPackaging = true
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("cryptography")   // MITM certificate generation
            install("h2")             // HTTP/2 multiplexing to the relay
            install("certifi")        // CA bundle for verifying the relay's TLS
            // Optional extras (only if Chaquopy ships wheels for your Python version):
            // install("brotli")
            // install("zstandard")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
