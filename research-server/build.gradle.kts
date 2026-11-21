// Research proxy: the ONLY component that holds an LLM API key or talks to the web.
// The apps and the financial engine work without it (cached research fallback).
plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(projects.core)          // shared types, wire format, validation
    implementation(libs.anthropic.java)    // official Anthropic Java SDK (Claude + server-side web tools)
    implementation(libs.pdfbox)            // text from fetched PDF sources
    testImplementation(kotlin("test"))
}

application { mainClass.set("ch.ripple.research.MainKt") }

tasks.test {
    useJUnitPlatform()
    // Live tests (real network / real API) run only when explicitly enabled.
    environment("RIPPLE_LIVE_TESTS", System.getenv("RIPPLE_LIVE_TESTS") ?: "")
}
