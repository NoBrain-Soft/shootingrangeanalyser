@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.camera.lifecycle

import android.content.Context
import android.content.stub
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.lifecycle.LifecycleOwner

class ProcessCameraProvider {
    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector,
        vararg useCases: UseCase,
    ): Camera = stub()

    fun unbindAll(): Unit = stub()

    companion object {
        fun getInstance(context: Context): ListenableFutureStub<ProcessCameraProvider> = stub()
    }
}

class ListenableFutureStub<T> {
    fun get(): T = stub()
    fun addListener(runnable: Runnable, executor: java.util.concurrent.Executor): Unit = stub()
}
