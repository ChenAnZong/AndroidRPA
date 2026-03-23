plugins {
    id("com.android.library")
}

group="com.github.topjohnwu.libsu"

android {
    compileSdkVersion(31)

    defaultConfig {
        targetSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    namespace = "com.topjohnwu.superuser"
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    api("androidx.annotation:annotation:1.3.0")
}
