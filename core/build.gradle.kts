plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

kotlin {
    jvmToolchain(17)
    jvm()
    android {
        namespace = "ch.ripple.core"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {} // run commonTest on the Android target too
    }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
