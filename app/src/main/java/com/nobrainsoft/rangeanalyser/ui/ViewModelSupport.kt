package com.nobrainsoft.rangeanalyser.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nobrainsoft.rangeanalyser.AppContainer
import com.nobrainsoft.rangeanalyser.appContainer

/**
 * Builds a view model with the app's dependencies to hand.
 *
 * Manual construction rather than an injection framework: the dependency graph is four objects
 * deep, and this is less machinery than annotating it would be.
 */
@Composable
inline fun <reified VM : ViewModel> rangeViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalContext.current.appContainer
    return viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { create(container) }
        },
    )
}
