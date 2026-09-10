@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.lifecycle

import android.content.stub
import kotlinx.coroutines.CoroutineScope

interface LifecycleOwner

open class ViewModel {
    protected open fun onCleared() {}
}

val ViewModel.viewModelScope: CoroutineScope get() = stub()

interface ViewModelStoreOwner

class ViewModelProvider {
    interface Factory
}
