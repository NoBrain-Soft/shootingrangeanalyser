@file:Suppress("unused", "UNUSED_PARAMETER")

// The Android-only corners of Compose that Compose Multiplatform's desktop artifacts do not
// carry. Declared in their real packages so the app's imports resolve unchanged.

package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.LifecycleOwner

val LocalContext: ProvidableCompositionLocal<Context> =
    staticCompositionLocalOf { error("no context in a type-check stub") }

val LocalLifecycleOwner: ProvidableCompositionLocal<LifecycleOwner> =
    staticCompositionLocalOf { error("no lifecycle owner in a type-check stub") }
