// Type-checks :app off-device. See README.md in this directory for what that does and does not
// prove. Paths are relative to this directory; it is a standalone build, not part of the main one.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}
repositories { mavenCentral() }

kotlin {
    sourceSets["main"].kotlin.setSrcDirs(listOf("../../app/src/main/java", "stubs"))
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Built by `./gradlew :core:jar :vision:jar` in the main build first.
    implementation(files("../../core/build/libs/core.jar"))
    implementation(files("../../vision/build/libs/vision.jar"))
    implementation("org.openpnp:opencv:4.9.0-0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Compose Multiplatform publishes the same androidx.compose.* packages to Maven Central,
    // which is what makes this possible without Google's Maven.
    val compose = "1.7.0"
    implementation("org.jetbrains.compose.runtime:runtime-desktop:$compose")
    implementation("org.jetbrains.compose.ui:ui-desktop:$compose")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:$compose")
    implementation("org.jetbrains.compose.material3:material3-desktop:$compose")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:$compose")
}
