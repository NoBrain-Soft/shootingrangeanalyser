# Shooting Range Analyser

An Android app that analyses shooting targets through the camera.

- **Live mode** — zoom in on the target and the phone watches it, calling each new shot as it
  appears so you don't have to walk downrange or glass the target between strings.
- **Photo mode** — photograph a target and get the holes found, scored, measured and explained.

There is no standardised QR-coded target to work from, so the app establishes scale from whatever is
actually there: the target's own printed rings, the paper size, a distance you measure yourself, the
size of the bullet holes, or the camera's optics and a known range.

## What makes it different

Most of the engineering here went into **not lying to the shooter**.

- **Group size is reported with the range it could plausibly have been.** A five-shot group is a
  sample, not a measurement. The app fits the group's spread, simulates thousands of fresh groups
  from it, and tells you the interval — because two identical rifles shooting five rounds each
  routinely differ by 40% on extreme spread alone.
- **Comparing two targets gives a verdict, not a percentage.** It will say a visibly better group
  is not evidence of anything, and either tell you how many shots would settle it or that no
  practical amount of shooting would — which is the answer that stops you buying ammunition to chase
  a difference that is not there.
- **Coaching shows its working.** Every tip carries the numbers that produced it. Where a tip comes
  from shooting folklore rather than measurement, it says so and suggests a way to test it.
- **Vertical spread gets attributed.** Given your load's velocity SD, the app works out how much of
  the spread the ammunition explains at that distance, and blames the ammunition or you accordingly.
  Bulk .308 throws about 130 mm of vertical at 600 yards on its own; telling that shooter to work on
  their breathing wastes their range time.
- **Sight advice is gated.** If the group's offset from the aim point is inside the group's own
  spread, the app says so rather than handing you a click count derived from noise.
- **The app declines when it cannot see.** Before a live session it works out how many pixels across
  a hole will be, and says plainly when the distance and zoom make tracking impossible.

## Layout

```
:core     Pure Kotlin/JVM. Domain model, target library, scoring, statistics,
          ballistics, coaching, comparison. No Android, no OpenCV.
:vision   Pure Kotlin/JVM. Calibration, hole detection, live tracking. Uses the
          OpenCV Java API only - never android.*
:app      Android. Compose UI, CameraX, Room, text-to-speech, export.
```

`:vision` deliberately avoids `android.*` so its algorithms compile and run against the desktop
OpenCV build. The Android AAR (`org.opencv:opencv`) and the desktop jar (`org.openpnp:opencv`)
expose the same `org.opencv.*` API at the same upstream version, so the detection code that is
tested here is the code that runs on the phone.

## Building

**Use JDK 17 or 21.** Gradle 8.9 and AGP 8.7.3 both predate JDK 25 and fail while parsing its
version string — `IllegalArgumentException: 25.0.4.1` before a single line of build script runs. If
your default `java` is newer, point the build at an older one:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or org.gradle.java.home in gradle.properties
./gradlew --stop                                      # the old daemon does not pick this up
```

```bash
./gradlew :core:test :vision:test    # the analysis engine - 217 tests
./gradlew :app:assembleDebug         # the app; needs an Android SDK
./gradlew :app:installDebug          # build and push to a device over adb

./gradlew :core:jar :vision:jar && ./gradlew -p tools/typecheck compileKotlin
                                     # type-check :app without an SDK - see tools/typecheck
```

`settings.gradle.kts` includes `:app` only when an Android SDK is present (`ANDROID_HOME`, or
`sdk.dir` in `local.properties`). Resolving the Android Gradle Plugin happens at configuration time
for the whole build, so without that guard `./gradlew :core:test` would fail on any machine or CI
container without an SDK.

**The root `plugins {}` block is empty on purpose, and adding anything to it will break the build.**
Each module declares its own plugins with versions. Gradle gives every project its own plugin
classloader with the parent project's as its parent, so a plugin declared at the root is the copy
the subprojects link against — and it cannot see anything the subprojects loaded. AGP at the root
would need Google's Maven for every build; the Kotlin plugins at the root would place
`kotlin.android` above AGP, where it cannot reach the variant API it needs. The root build script
spells this out at more length.

## Status and handover

The analysis engine is complete and tested: **217 tests**, covering scoring against every ring
boundary, group statistics against hand-computed fixtures, flyer detection held to its nominal
false-positive rate by Monte Carlo, ballistics validated against published velocity and
time-of-flight figures, and detection run against synthetically rendered targets with perspective,
blur, noise and uneven lighting.

`:app` builds and runs on a phone. It cannot be built *here*, though: this repository is developed
in an environment where Google's Maven is unreachable, so AGP, Compose, CameraX and Room do not
resolve and `./gradlew :app:assembleDebug` will not run at all.

What runs instead is `tools/typecheck`, which compiles the whole `:app` source tree against Compose
Multiplatform's Maven Central artifacts plus stubs for the Android surface. It passes with no errors
and no warnings, and it catches type errors, unresolved references and missing opt-ins across all
~8,000 lines. It does not cover AGP, Room's generated code, or anything at runtime — its README is
specific about the difference and worth reading before trusting a clean run.

Three manual checks are worth doing after any substantial change:

1. **Photo of a target with known hole positions.** Confirms calibration, rectification, detection
   and scoring end to end.
2. **A live string.** Confirms the camera pipeline, the watcher's confirmation logic, and that the
   spoken and on-screen calls agree.
3. **Compare two saved sessions.** Confirms persistence and that the comparison verdict appears.

## Targets the app does not know

Most ranges print their own face. Rather than score those against the wrong geometry, the custom
target editor builds one from what you measure: photograph the target, tap the two edges of the
paper to set the scale, tap the centre, then tap each ring. Evenly spaced faces can be typed in
instead, from the outer diameter and the ring count.

A measured face is marked as measured, never as verified, and a table that would score wrongly is
refused with a reason — two rings the same size, a ring wider than the paper it is printed on, an
inner ring larger than the ten. Decimal scoring is offered only when the rings really are evenly
spaced, because tenths of an uneven ring are invented precision.

## What is deliberately not here

Ring tables that could not be sourced with confidence are absent rather than guessed — the custom
target editor covers them. IPSC and IDPA faces are included but flagged as approximate, because only
their primary scoring zone is dimensioned from the rules.

No cloud sync, no accounts, no leaderboards. Nothing leaves the phone.

This is a training aid. It is not a competition scoring system, and every measurement depends on how
well the target was photographed and calibrated.
