@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.lifecycle.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateWithLifecycle(): State<T> =
    remember { object : State<T> { override val value: T get() = this@collectAsStateWithLifecycle.value } }

@Composable
fun <T> Flow<T>.collectAsStateWithLifecycle(initialValue: T): State<T> =
    remember { object : State<T> { override val value: T get() = initialValue } }
