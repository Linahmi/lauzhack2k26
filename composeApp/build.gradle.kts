plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinCompose)
}

kotlin {
    jvmToolchain(17)
    jvm()
    android {
        namespace = "ch.ripple.app"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.kotlinx.serialization.json) // JSON tree API for the phone session file, no plugin
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.uiTestJUnit4)
        }
    }
}

compose.desktop {
    application {
        mainClass = "ch.ripple.app.MainKt"
    }
}

// UI tests must not depend on the local AI being installed: point them at a closed port.
tasks.withType<Test>().configureEach {
    environment("RIPPLE_OLLAMA_URL", System.getenv("ROUTES_TEST_OLLAMA_URL") ?: "http://127.0.0.1:9")
}
