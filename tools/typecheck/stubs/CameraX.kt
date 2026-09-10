@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.camera.core

import android.content.stub
import java.nio.ByteBuffer
import java.util.concurrent.Executor

class CameraSelector {
    companion object {
        val DEFAULT_BACK_CAMERA: CameraSelector get() = stub()
        val DEFAULT_FRONT_CAMERA: CameraSelector get() = stub()
    }
}

interface UseCase

class ImageInfo {
    val timestamp: Long get() = stub()
    val rotationDegrees: Int get() = stub()
}

interface ImageProxy : AutoCloseable {
    val width: Int
    val height: Int
    val planes: Array<PlaneProxy>
    val imageInfo: ImageInfo
    override fun close()

    interface PlaneProxy {
        val buffer: ByteBuffer
        val rowStride: Int
        val pixelStride: Int
    }
}

class ImageAnalysis private constructor() : UseCase {
    fun setAnalyzer(executor: Executor, analyzer: Analyzer): Unit = stub()
    fun clearAnalyzer(): Unit = stub()

    fun interface Analyzer {
        fun analyze(image: ImageProxy)
    }

    class Builder {
        fun setBackpressureStrategy(strategy: Int): Builder = this
        fun setTargetResolution(size: android.util.Size): Builder = this
        fun build(): ImageAnalysis = stub()
    }

    companion object {
        const val STRATEGY_KEEP_ONLY_LATEST: Int = 0
        const val STRATEGY_BLOCK_PRODUCER: Int = 1
    }
}

class Preview private constructor() : UseCase {
    fun setSurfaceProvider(provider: SurfaceProvider?): Unit = stub()

    interface SurfaceProvider

    class Builder {
        fun build(): Preview = stub()
    }
}

class Camera {
    val cameraControl: CameraControl get() = stub()
    val cameraInfo: CameraInfo get() = stub()
}

class CameraControl {
    fun setZoomRatio(ratio: Float): Any = stub()
    fun setLinearZoom(linearZoom: Float): Any = stub()
}

class CameraInfo {
    val zoomState: LiveDataStub<ZoomState> get() = stub()
}

class ZoomState {
    val zoomRatio: Float get() = stub()
    val maxZoomRatio: Float get() = stub()
    val minZoomRatio: Float get() = stub()
    val linearZoom: Float get() = stub()
}

class LiveDataStub<T> {
    val value: T? get() = stub()
}
