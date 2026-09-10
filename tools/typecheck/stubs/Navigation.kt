@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.navigation

import android.content.stub
import android.os.Bundle

class NavBackStackEntry {
    val arguments: Bundle? get() = stub()
}

open class NavController {
    fun navigate(route: String): Unit = stub()
    fun navigate(route: String, builder: NavOptionsBuilder.() -> Unit): Unit = stub()
    fun popBackStack(): Boolean = stub()
    fun navigateUp(): Boolean = stub()
}

class NavHostController : NavController()

class NavOptionsBuilder {
    var launchSingleTop: Boolean = false
    var restoreState: Boolean = false
    fun popUpTo(route: String, builder: PopUpToBuilder.() -> Unit = {}): Unit = stub()
}

class PopUpToBuilder {
    var inclusive: Boolean = false
    var saveState: Boolean = false
}

class NavGraphBuilder

abstract class NavType<T> {
    companion object {
        val StringType: NavType<String> get() = stub()
        val IntType: NavType<Int> get() = stub()
        val BoolType: NavType<Boolean> get() = stub()
        val FloatType: NavType<Float> get() = stub()
    }
}

class NavArgumentBuilder {
    var type: NavType<*>
        get() = stub()
        set(value) {}
    var nullable: Boolean = false
    var defaultValue: Any? = null
}

fun navArgument(name: String, builder: NavArgumentBuilder.() -> Unit): Pair<String, Any> {
    NavArgumentBuilder().builder()
    return name to Unit
}

