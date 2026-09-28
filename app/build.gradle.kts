plugins {
    id 'com.android.application'
    id 'kotlin-android'
}

android {
    namespace 'com.example.assetcapturerig'
    compileSdk 34

    defaultConfig {
        applicationId "com.example.assetcapturerig.v2"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"

        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"

        // Inject DASHSCOPE_API_KEY into BuildConfig
        def dashscopeKey = System.getenv("DASHSCOPE_API_KEY") ?: ""
        buildConfigField "String", "DASHSCOPE_API_KEY", "\"${dashscopeKey}\""
    }

    buildFeatures {
        buildConfig true
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
        debug {
            signingConfig signingConfigs.debug
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'com.google.ar:core:1.41.0'
    implementation 'io.github.sceneview:arsceneview:0.10.2'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
