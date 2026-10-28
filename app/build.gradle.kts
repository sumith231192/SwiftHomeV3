plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.swifthome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.swifthome"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
// Deliberately NO dependencies: no third-party libraries, nothing to audit but MainActivity.java
