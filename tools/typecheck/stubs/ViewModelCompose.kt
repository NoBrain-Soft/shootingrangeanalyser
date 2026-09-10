@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.lifecycle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class CreationExtras

class InitializerViewModelFactoryBuilder {
    fun <T : ViewModel> addInitializer(clazz: Class<T>, initializer: CreationExtras.() -> T) {}
}

inline fun <reified VM : ViewModel> InitializerViewModelFactoryBuilder.initializer(
    noinline initializer: CreationExtras.() -> VM,
) = addInitializer(VM::class.java, initializer)

fun viewModelFactory(
    builder: InitializerViewModelFactoryBuilder.() -> Unit,
): ViewModelProvider.Factory {
    InitializerViewModelFactoryBuilder().builder()
    return object : ViewModelProvider.Factory {}
}
