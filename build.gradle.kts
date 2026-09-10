// The Android Gradle Plugin is deliberately NOT declared here, not even with `apply false`:
// that still resolves AGP onto the shared build classpath, which would break every build on a
// machine without an Android SDK. :app declares it itself, and :app is only included when an
// SDK is present.
//
// The JetBrains Kotlin plugins are a different matter and must be here. They all ship inside
// kotlin-gradle-plugin, so declaring any one of them puts the rest on the classpath too - with
// an unknown version, which Gradle then refuses to reconcile against a versioned request from a
// subproject. Declaring the whole family here with `apply false` gives every ID a known version;
// subprojects request them by bare ID. None of them need an Android SDK merely to be resolved.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
