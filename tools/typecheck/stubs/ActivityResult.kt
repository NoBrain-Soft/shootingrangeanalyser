@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity.result.contract

import android.net.Uri

interface ActivityResultContract<I, O>

object ActivityResultContracts {
    class RequestPermission : ActivityResultContract<String, Boolean>
    class GetContent : ActivityResultContract<String, Uri?>
    class TakePicture : ActivityResultContract<Uri, Boolean>
    class PickVisualMedia : ActivityResultContract<Any, Uri?>
}
