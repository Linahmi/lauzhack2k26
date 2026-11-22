import java.util.Properties
import java.net.URI

// AGP 9 no longer allows com.android.application inside a KMP module, so the APK lives here
// and only hosts the shared UI from :composeApp.
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
}

val routesLocalProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val liveDebugTokenUrl = routesLocalProperties.getProperty("routes.liveTokenUrl", "http://10.0.2.2:8787/token")
val liveDebugUri = URI(liveDebugTokenUrl).also {
    require(it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null &&
        it.query == null && it.fragment == null && it.path == "/token") { "routes.liveTokenUrl must be an http(s) URL ending in /token, without credentials or query parameters" }
}
val liveDebugResources = layout.buildDirectory.dir("generated/routesLiveDebug/res")
val generateLiveDebugNetworkConfig = tasks.register("generateLiveDebugNetworkConfig") {
    inputs.property("host", liveDebugUri.host)
    outputs.dir(liveDebugResources)
    doLast {
        val xml = liveDebugResources.get().file("xml/routes_live_network.xml").asFile
        xml.parentFile.mkdirs()
        xml.writeText("""<?xml version="1.0" encoding="utf-8"?>
            <network-security-config>
                <base-config cleartextTrafficPermitted="false" />
                <domain-config cleartextTrafficPermitted="true">
                    <domain includeSubdomains="false">${liveDebugUri.host}</domain>
                </domain-config>
            </network-security-config>
        """.trimIndent())
    }
}

android {
    namespace = "ch.ripple.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "ch.ripple.android"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "LIVE_TOKEN_URL", "\"\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes.getByName("debug") { buildConfigField("String", "LIVE_TOKEN_URL", "\"$liveDebugTokenUrl\"") }
    sourceSets.getByName("debug").res.srcDir(liveDebugResources.get().asFile)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(generateLiveDebugNetworkConfig) }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
}
