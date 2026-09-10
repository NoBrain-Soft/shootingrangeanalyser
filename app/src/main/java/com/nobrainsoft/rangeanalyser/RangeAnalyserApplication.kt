package com.nobrainsoft.rangeanalyser

import android.app.Application
import android.content.Context
import android.util.Log
import com.nobrainsoft.rangeanalyser.data.RangeRepository
import com.nobrainsoft.rangeanalyser.data.SettingsStore
import com.nobrainsoft.rangeanalyser.data.db.RangeDatabase
import org.opencv.android.OpenCVLoader

/**
 * Wires the app together.
 *
 * Dependencies are constructed by hand rather than with an injection framework. There are four of
 * them, they all live for the life of the process, and a graph this small is easier to follow as
 * plain code than as annotations.
 */
class RangeAnalyserApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    private val database = RangeDatabase.get(context)

    val repository = RangeRepository(database)
    val settings = SettingsStore(context)
    val files = FileStore(context)

    /**
     * Whether the OpenCV native libraries loaded.
     *
     * Checked once and remembered, so the camera and photo screens can offer a clear explanation
     * rather than crashing on the first frame. Everything except image analysis still works without
     * it - history, statistics, coaching and manual shot entry are all pure Kotlin.
     */
    val openCvAvailable: Boolean = runCatching { OpenCVLoader.initLocal() }
        .onFailure { Log.e(TAG, "OpenCV natives failed to load", it) }
        .getOrDefault(false)

    private companion object {
        const val TAG = "RangeAnalyser"
    }
}

/** Where saved target images, shot crops and exports live. */
class FileStore(private val context: Context) {
    val targetsDirectory get() = java.io.File(context.filesDir, "targets").apply { mkdirs() }

    val exportsDirectory get() = java.io.File(context.filesDir, "exports").apply { mkdirs() }

    fun targetImage(sessionId: String) = java.io.File(targetsDirectory, "$sessionId.png")

    fun shotCrop(sessionId: String, shotId: String) =
        java.io.File(targetsDirectory, "$sessionId-$shotId.png")
}

/** Convenience for reaching the container from a composable or a view model factory. */
val Context.appContainer: AppContainer
    get() = (applicationContext as RangeAnalyserApplication).container
