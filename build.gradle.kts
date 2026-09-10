// This block is deliberately empty, and both halves of that matter.
//
// Gradle gives each project's build script its own plugin classloader, with the parent
// project's as its parent. A child can see the parent's classes; a parent cannot see the
// child's. So *where* a plugin is declared decides what it can link against.
//
// - AGP must not be declared here, because resolving it needs Google's Maven, and the
//   engine modules are meant to build on a machine or CI container that has neither that
//   nor an Android SDK. :app declares it, and :app is only included when an SDK is present.
//
// - The Kotlin plugins must not be declared here either, which is less obvious. They all
//   ship inside kotlin-gradle-plugin, so declaring any one of them - kotlin.jvm, for the
//   engine modules - would put org.jetbrains.kotlin.android on *this* classloader too. It
//   would then be the copy :app links against, and being a parent it cannot see :app's AGP,
//   so applying it fails with `Unable to load class
//   com.android.build.gradle.api.BaseVariant`. The Kotlin Android plugin still uses AGP's
//   legacy variant API and needs AGP visible from its own classloader or above.
//
// With nothing here, each module resolves its own plugins into a sibling classloader, and
// :app's holds AGP and the Kotlin plugins together, which is what the Android integration
// needs. Google's own multi-module template declares both at the root instead - that also
// works, and is the arrangement to switch to if the Google-Maven-free build ever stops
// being worth keeping. What does not work is declaring one of the pair and not the other.
plugins {
}
