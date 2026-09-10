@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.navigation.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

@Composable
fun rememberNavController(): NavHostController = remember { NavHostController() }

@Composable
fun NavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    route: String? = null,
    builder: NavGraphBuilder.() -> Unit,
) {
    NavGraphBuilder().builder()
}

fun NavGraphBuilder.composable(
    route: String,
    arguments: List<Pair<String, Any>> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
}
