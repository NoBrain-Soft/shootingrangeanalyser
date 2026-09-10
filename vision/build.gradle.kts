plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :vision holds all image processing. It uses the OpenCV *Java* API only and never
// touches android.* (no org.opencv.android.Utils, no OpenCVLoader) - Bitmap<->Mat
// conversion and native loading belong to :app. That restriction is what allows the
// detection algorithms to be compiled and tested here against the desktop OpenCV build,
// then run unchanged on Android against the AAR.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))

    // Provided at runtime by the OpenCV Android AAR in :app; supplied by the desktop
    // build (which also carries the natives) for tests here.
    compileOnly(libs.opencv.jvm)
    testImplementation(libs.opencv.jvm)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
