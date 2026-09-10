// Android plugins are deliberately NOT declared here, not even with `apply false`:
// that still resolves the Android Gradle Plugin onto the build classpath, which would
// break every build on a machine without an Android SDK. :app declares them itself.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
