plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ceyinfo.cerpcashbook"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ceyinfo.cerpcashbook"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"https://cerpapi.ceyinfo.com/api/v1/\"")

        // GitHub repo for in-app auto-update (AppUpdater hits the Releases API).
        // Change these if the project is moved to a different owner/repo.
        buildConfigField("String", "GITHUB_OWNER", "\"isurusajith68\"")
        buildConfigField("String", "GITHUB_REPO", "\"cerp-m-cash-book\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"https://cerpapi.ceyinfo.com/api/v1/\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.swiperefreshlayout)

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.gson)

    // Image loading
    implementation(libs.coil)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // ViewModel + LiveData
    implementation(libs.viewmodel.ktx)
    implementation(libs.livedata.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
