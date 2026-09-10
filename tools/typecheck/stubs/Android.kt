@file:Suppress("unused", "UNUSED_PARAMETER", "ClassName", "ObjectPropertyName")

// Stand-ins for the Android platform surface the app touches, so :app can be type-checked
// off-device. Signatures mirror the real API; bodies are absent on purpose.

package android

object Manifest {
    object permission {
        const val CAMERA: String = "android.permission.CAMERA"
    }
}
