@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.core.content

import android.content.Context
import android.content.stub
import android.net.Uri
import java.io.File
import java.util.concurrent.Executor

object ContextCompat {
    fun checkSelfPermission(context: Context, permission: String): Int = stub()
    fun getMainExecutor(context: Context): Executor = stub()
}

object FileProvider {
    fun getUriForFile(context: Context, authority: String, file: File): Uri = stub()
}
