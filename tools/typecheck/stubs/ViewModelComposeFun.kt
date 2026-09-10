@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.lifecycle.viewmodel.compose

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

@Composable
fun <VM : ViewModel> viewModel(
    modelClass: Class<VM>,
    key: String? = null,
    factory: ViewModelProvider.Factory? = null,
): VM = throw UnsupportedOperationException("type-check stub")

@Composable
inline fun <reified VM : ViewModel> viewModel(
    key: String? = null,
    factory: ViewModelProvider.Factory? = null,
): VM = viewModel(VM::class.java, key, factory)
