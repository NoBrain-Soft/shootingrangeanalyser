package com.nobrainsoft.rangeanalyser.vision

import org.opencv.core.Core

/**
 * Loads the desktop OpenCV natives once for the whole test run.
 *
 * On Android this is [org.opencv.android.OpenCVLoader]'s job and lives in `:app`; here the
 * `org.openpnp:opencv` artifact ships the natives inside the jar and extracts them on demand.
 */
object TestOpenCv {
    private val loaded: Boolean by lazy {
        runCatching { nu.pattern.OpenCV.loadLocally() }
            .recoverCatching { System.loadLibrary(Core.NATIVE_LIBRARY_NAME) }
            .isSuccess
    }

    /** Call at the top of any test that touches `Mat`. */
    fun ensureLoaded() {
        check(loaded) { "OpenCV natives could not be loaded" }
    }
}
