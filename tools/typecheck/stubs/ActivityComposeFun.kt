@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

fun ComponentActivity.setContent(content: @Composable () -> Unit) {}

class ManagedActivityResultLauncher<I, O> {
    fun launch(input: I) {}
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> = remember { ManagedActivityResultLauncher() }

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
}
