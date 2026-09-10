pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Scoped so plugin resolution never reaches out to Google for Kotlin/JVM plugins.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "shootingrangeanalyser"

// :core and :vision are plain Kotlin/JVM modules and always build.
include(":core")
include(":vision")

// :app needs the Android SDK. Including it unconditionally would make `./gradlew :core:test`
// fail on any machine (or CI container) without one, because resolving the Android Gradle
// Plugin happens at configuration time for the whole build. So we include it only when an
// SDK is actually available.
val androidSdkDir: String? =
    file("local.properties")
        .takeIf { it.exists() }
        ?.let { propsFile ->
            java.util.Properties()
                .apply { propsFile.inputStream().use { load(it) } }
                .getProperty("sdk.dir")
        }
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")

if (!androidSdkDir.isNullOrBlank() && file(androidSdkDir).isDirectory) {
    include(":app")
} else {
    logger.lifecycle(
        "Android SDK not found - skipping :app. " +
            "Set ANDROID_HOME or add sdk.dir to local.properties to build the app.",
    )
}
