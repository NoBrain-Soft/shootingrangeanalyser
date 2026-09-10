@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.camera.view

import android.content.Context
import android.content.stub
import android.view.ViewGroup
import androidx.camera.core.Preview

class PreviewView(context: Context) : ViewGroup(context) {
    var scaleType: ScaleType = ScaleType.FILL_CENTER
    var implementationMode: ImplementationMode = ImplementationMode.PERFORMANCE
    val surfaceProvider: Preview.SurfaceProvider get() = stub()
    var layoutParams: LayoutParams
        get() = stub()
        set(value) {}

    enum class ScaleType { FILL_CENTER, FIT_CENTER, FILL_START, FILL_END, FIT_START, FIT_END }
    enum class ImplementationMode { PERFORMANCE, COMPATIBLE }
}
