plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.elintpos.wrapper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.elintpos.wrapper"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                // Prevent bundling Windows SDK files
                "Windows SDK 2.04/**",
                // Exclude vendor docs/demo; keep only referenced AAR
                "Android SDK 3.2.0/**"
            )
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    
    // For barcode/QR code generation (optional)
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Excel (.xlsx) reading (Apache POI port for Android)
    implementation("com.github.SUPERCILEX.poi-android:poi:3.17")

    // Printer SDKs - Epson ePOS2 SDK
    // Note: Epson SDK requires manual download due to repository access issues
    // Download from: https://download.epson-biz.com/modules/pos/index.php?page=single_soft&cid=4571&scat=58&pcat=3
    // Place the AAR file in app/libs/ directory
    // implementation("com.epson.epos2:epos2:2.8.0")
    
    // XPrinter SDK (if available on Maven)
    // Note: XPrinter SDK might not be available on public Maven repositories
    // You may need to download it manually and place in libs folder
    // implementation("com.xprinter:xprinter-sdk:1.0.0") // Uncomment if available

    // Network and HTTP for SDK downloads
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Local SDKs: auto-include any AAR/JAR in app/libs
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}