plugins { 
    id("com.android.application") 
    id("org.jetbrains.kotlin.android") 
}
android {
    namespace = "com.sandbox.app"
    compileSdk = 34
    defaultConfig { 
        applicationId = "com.sandbox.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0" 
    }
    compileOptions { 
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17 
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { 
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
