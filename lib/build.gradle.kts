plugins {
    id("com.android.library")
    id("kotlin-android")
    id("maven-publish")
}

android {
    namespace = "dora.widget.browser"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.github.dora4:dora:1.2.27")
    implementation("com.github.dora4:dview-alert-dialog:1.18")
    implementation("com.github.dora4:dview-toast:1.1")
    implementation("com.github.getActivity:XXPermissions:18.2")
}

afterEvaluate {
    publishing {
        publications {
            register("release", MavenPublication::class) {
                from(components["release"])
                groupId = "com.github.dora4"
                artifactId = rootProject.project.name
                version = "1.1"
            }
        }
    }
}