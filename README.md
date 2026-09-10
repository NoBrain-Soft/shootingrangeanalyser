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

```bash
./gradlew :core:test :vision:test    # the analysis engine - 177 tests
./gradlew :app:assembleDebug         # the app; needs an Android SDK
```

`settings.gradle.kts` includes `:app` only when an Android SDK is present (`ANDROID_HOME`, or
`sdk.dir` in `local.properties`). Resolving the Android Gradle Plugin happens at configuration time
for the whole build, so without that guard `./gradlew :core:test` would fail on any machine or CI
container without an SDK.

## Status and handover

The analysis engine is complete and tested: **177 tests**, covering scoring against every ring
boundary, group statistics against hand-computed fixtures, flyer detection held to its nominal
false-positive rate by Monte Carlo, ballistics validated against published velocity and
time-of-flight figures, and detection run against synthetically rendered targets with perspective,
blur, noise and uneven lighting.

**The `:app` module has never been compiled.** It was written in an environment where Google's Maven
repository is unreachable, so the Android Gradle Plugin, Compose and CameraX could not be resolved.
Everything in `:app` is deliberately logic-free — it is camera plumbing, screens and persistence over
an engine that is tested — so expect build fixes there to be mechanical rather than conceptual.

What was checked statically in place of compiling: every `:core`/`:vision` symbol the app imports
resolves, no module-internal symbol is referenced across the module boundary, and Compose imports
match their usages.

When you first open it in Android Studio, three manual checks are worth doing in order:

1. **Photo of a target with known hole positions.** Confirms calibration, rectification, detection
   and scoring end to end.
2. **A live string.** Confirms the camera pipeline, the watcher's confirmation logic, and that the
   spoken and on-screen calls agree.
3. **Compare two saved sessions.** Confirms persistence and that the comparison verdict appears.

## What is deliberately not here

Ring tables that could not be sourced with confidence are absent rather than guessed — the custom
target editor covers them. IPSC and IDPA faces are included but flagged as approximate, because only
their primary scoring zone is dimensioned from the rules.

No cloud sync, no accounts, no leaderboards. Nothing leaves the phone.

This is a training aid. It is not a competition scoring system, and every measurement depends on how
well the target was photographed and calibrated.
