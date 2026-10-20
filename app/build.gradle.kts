plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Optional: a real keystore for stable updates. CI falls back to the debug key, which still makes
// an installable APK. Never put the keystore or its passwords in the repository.
val keystorePath = System.getenv("DECOMP_KEYSTORE")

android {
    namespace = "com.deivid22srk.decompdecomp"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.deivid22srk.decompdecomp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // Chaquopy ships arm64-v8a Python; that is the target for a real phone.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("DECOMP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DECOMP_KEY_ALIAS")
                keyPassword = System.getenv("DECOMP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
            // libcapstone.so and Chaquopy's libs are extracted and loadable from nativeLibraryDir.
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("python3.13")

        pip {
            // The app itself, its splitter and the HTTP layer. `mcp`, `pydantic`, `ninja`,
            // `zstandard`, `pygfxd` and `crunch64` are intentionally absent: Android uses a tiny
            // `mcp` shim, and the two N64 codecs are replaced by import-only stubs.
            // rabbitizer is vendored because upstream builds with -Werror, which NDK clang trips.
            install(rootProject.file("third_party/rabbitizer").path)
            install("splat64==0.50.1")
            install("spimdisasm==1.42.4")
            install("n64img==0.3.3")
            install("pyelftools==0.33")
            install("httpx==0.28.1")
            install("uvicorn==0.54.0")
            install("starlette==1.9.0")
            install("anyio==4.15.1")
        }

        // `decompnet` reads its static files and toolchain table with importlib.resources and
        // __file__; extracting the package makes both work. `capstone` is a pure-Python wrapper
        // whose libcapstone.so lives in jniLibs (LIBCAPSTONE_PATH).
        extractPackages("decompnet", "capstone")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
