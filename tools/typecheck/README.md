# Off-device type check for `:app`

Compiles the whole `:app` source tree without an Android SDK and without Google's Maven.

```bash
./gradlew :core:jar :vision:jar          # from the repository root
./gradlew -p tools/typecheck compileKotlin
```

This is a **standalone build**. It is not in `settings.gradle.kts`, nothing in the app depends on
it, and deleting the directory changes nothing about how the app builds.

## Why it exists

`:app` needs AGP, Compose, CameraX and Room, all published only to Google's Maven. Some CI
containers and dev environments cannot reach it, and there `:app` cannot be built or even
configured — so type errors in ~7,500 lines of Kotlin go unseen until someone opens the project on
a machine with a full SDK.

Two things make this work around that:

- **Compose Multiplatform** publishes the same `androidx.compose.*` packages to Maven Central. The
  desktop artifacts satisfy nearly every Compose import in the app unchanged.
- **`stubs/`** supplies the rest — the Android platform classes, CameraX, Room's annotations,
  DataStore, Navigation, `ViewModel`, and the handful of Android-only corners of Compose such as
  `LocalContext`. They are declared in their real packages with the real signatures, so the app's
  imports and call sites resolve without modification.

## What it proves, and what it does not

It **does** catch: type mismatches, unresolved references, wrong argument counts, missing
`@OptIn`, deprecated APIs, and signature drift against `:core` and `:vision`. That is the class of
error that otherwise costs a round trip to a machine with an SDK.

It **does not** prove the app builds or runs. Not covered:

- AGP itself — manifest merging, resources, R8, packaging.
- KSP and Room's generated code. The annotations here are inert, so a malformed `@Query` or a DAO
  Room cannot implement passes silently.
- Anything at runtime: camera behaviour, permissions, TTS, database migrations, UI.
- Version skew. Compose Multiplatform 1.7.0 is close to, but not identical with, the Android
  artifacts the app actually compiles against (Compose BOM 2024.10.01, Material3 1.3.1,
  CameraX 1.4.0). An API that differs between them can produce a false error here, or hide a real
  one.

Treat a failure as a lead, not a verdict: check it against the real API before changing app code.
A clean run means the obvious errors are gone, not that `./gradlew :app:assembleDebug` will pass.

## Keeping the stubs honest

Every stub is a liability — one whose signature is wrong will either invent an error or conceal
one. Two rules:

- Only stub what the app actually imports. Do not fill out an API "for completeness".
- Mirror the real signature exactly, including return types. Getting this wrong matters in ways
  that look like nothing: `TextToSpeech.setLanguage` returns `int`, not `void`, which is precisely
  why Kotlin does not expose a `language` setter for it — a stub returning `Unit` would have hidden
  a real compile error in the app.
