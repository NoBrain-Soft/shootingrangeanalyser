package com.nobrainsoft.rangeanalyser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nobrainsoft.rangeanalyser.appContainer
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.core.target.TargetLibrary
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.data.AppSettings
import com.nobrainsoft.rangeanalyser.ui.history.CompareScreen
import com.nobrainsoft.rangeanalyser.ui.history.HistoryScreen
import com.nobrainsoft.rangeanalyser.ui.history.ProgressScreen
import com.nobrainsoft.rangeanalyser.ui.home.HomeScreen
import com.nobrainsoft.rangeanalyser.ui.library.AmmoEditorScreen
import com.nobrainsoft.rangeanalyser.ui.library.AmmoLibraryScreen
import com.nobrainsoft.rangeanalyser.ui.library.FirearmEditorScreen
import com.nobrainsoft.rangeanalyser.ui.library.FirearmLibraryScreen
import com.nobrainsoft.rangeanalyser.ui.library.ProfileEditorScreen
import com.nobrainsoft.rangeanalyser.ui.library.TargetLibraryScreen
import com.nobrainsoft.rangeanalyser.ui.live.LiveScreen
import com.nobrainsoft.rangeanalyser.ui.photo.PhotoScreen
import com.nobrainsoft.rangeanalyser.ui.results.ResultsScreen
import com.nobrainsoft.rangeanalyser.ui.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val COMPARE = "compare"
    const val PROGRESS = "progress"
    const val FIREARMS = "firearms"
    const val AMMO = "ammo"
    const val TARGETS = "targets"
    const val FIREARM_EDIT = "firearm/edit"
    const val AMMO_EDIT = "ammo/edit"
    const val PROFILE_EDIT = "profile/edit"
    const val LIVE = "live"
    const val PHOTO = "photo"
    const val RESULTS = "results"

    fun firearmEdit(id: String?) = "$FIREARM_EDIT?id=${id.orEmpty()}"

    fun ammoEdit(id: String?) = "$AMMO_EDIT?id=${id.orEmpty()}"

    fun profileEdit(id: String?) = "$PROFILE_EDIT?id=${id.orEmpty()}"

    fun live(profileId: String) = "$LIVE/$profileId"

    fun photo(profileId: String) = "$PHOTO/$profileId"

    fun results(sessionId: String) = "$RESULTS/$sessionId"
}

/**
 * Everything the app can show, and how to get between the screens.
 *
 * Shooting flows deliberately do not stay on the back stack: finishing a session goes to its
 * results and popping from there returns home rather than back into a live camera. Nobody wants to
 * find themselves back in an armed session by pressing back twice.
 */
@Composable
fun RangeNavGraph(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val container = context.appContainer
    val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onStartLive = { navController.navigate(Routes.live(it.id)) },
                onStartPhoto = { navController.navigate(Routes.photo(it.id)) },
                onEditProfile = { navController.navigate(Routes.profileEdit(it)) },
                onNewProfile = { navController.navigate(Routes.profileEdit(null)) },
                onOpenSession = { navController.navigate(Routes.results(it)) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenProgress = { navController.navigate(Routes.PROGRESS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = navController::popBackStack,
                onOpenFirearms = { navController.navigate(Routes.FIREARMS) },
                onOpenAmmo = { navController.navigate(Routes.AMMO) },
                onOpenTargets = { navController.navigate(Routes.TARGETS) },
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = navController::popBackStack,
                onOpen = { navController.navigate(Routes.results(it)) },
                onCompare = { navController.navigate(Routes.COMPARE) },
            )
        }

        composable(Routes.COMPARE) { CompareScreen(onBack = navController::popBackStack) }
        composable(Routes.PROGRESS) { ProgressScreen(onBack = navController::popBackStack) }

        composable(Routes.FIREARMS) {
            FirearmLibraryScreen(
                onBack = navController::popBackStack,
                onEdit = { navController.navigate(Routes.firearmEdit(it)) },
            )
        }
        composable(Routes.AMMO) {
            AmmoLibraryScreen(
                onBack = navController::popBackStack,
                onEdit = { navController.navigate(Routes.ammoEdit(it)) },
            )
        }
        composable(Routes.TARGETS) {
            TargetLibraryScreen(onBack = navController::popBackStack, onEditCustom = {})
        }

        composable(
            route = "${Routes.FIREARM_EDIT}?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            FirearmEditorScreen(
                firearmId = entry.arguments?.getString("id")?.takeIf { it.isNotBlank() },
                onDone = navController::popBackStack,
            )
        }

        composable(
            route = "${Routes.AMMO_EDIT}?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            AmmoEditorScreen(
                ammoId = entry.arguments?.getString("id")?.takeIf { it.isNotBlank() },
                onDone = navController::popBackStack,
            )
        }

        composable(
            route = "${Routes.PROFILE_EDIT}?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            ProfileEditorScreen(
                profileId = entry.arguments?.getString("id")?.takeIf { it.isNotBlank() },
                onDone = navController::popBackStack,
                onAddFirearm = { navController.navigate(Routes.firearmEdit(null)) },
                onAddAmmo = { navController.navigate(Routes.ammoEdit(null)) },
                onEditFirearm = { navController.navigate(Routes.firearmEdit(it)) },
                onEditAmmo = { navController.navigate(Routes.ammoEdit(it)) },
                onEditTargets = { navController.navigate(Routes.TARGETS) },
            )
        }

        composable(
            route = "${Routes.LIVE}/{profileId}",
            arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
        ) { entry ->
            val profileId = entry.arguments?.getString("profileId").orEmpty()
            WithProfile(profileId) { profile, spec, caliber ->
                LiveScreen(
                    profile = profile,
                    spec = spec,
                    caliber = caliber,
                    speech = settings.speech,
                    onFinished = { sessionId ->
                        navController.navigate(Routes.results(sessionId)) {
                            popUpTo(Routes.HOME)
                        }
                    },
                    onBack = navController::popBackStack,
                )
            }
        }

        composable(
            route = "${Routes.PHOTO}/{profileId}",
            arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
        ) { entry ->
            val profileId = entry.arguments?.getString("profileId").orEmpty()
            WithProfile(profileId) { profile, spec, caliber ->
                PhotoScreen(
                    profile = profile,
                    spec = spec,
                    caliber = caliber,
                    onFinished = { sessionId ->
                        navController.navigate(Routes.results(sessionId)) {
                            popUpTo(Routes.HOME)
                        }
                    },
                    onBack = navController::popBackStack,
                )
            }
        }

        composable(
            route = "${Routes.RESULTS}/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            ResultsScreen(
                sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                onBack = navController::popBackStack,
            )
        }
    }
}

/**
 * Resolves a profile and everything it points at before showing a shooting screen.
 *
 * The screens need the target face and the calibre, not just their ids, and looking them up here
 * keeps that lookup out of three different view models.
 */
@Composable
private fun WithProfile(
    profileId: String,
    content: @Composable (Profile, TargetSpec, Caliber?) -> Unit,
) {
    val container = LocalContext.current.appContainer
    var resolved by remember(profileId) {
        mutableStateOf<Triple<Profile, TargetSpec, Caliber?>?>(null)
    }

    LaunchedEffect(profileId) {
        val profile = container.repository.findProfile(profileId) ?: return@LaunchedEffect
        val spec = container.repository.findTarget(profile.targetSpecId)
            ?: TargetLibrary.BLANK_A4
        val caliber = container.repository.findFirearm(profile.firearmId)
            ?.let { Calibers.find(it.caliberId) }
        resolved = Triple(profile, spec, caliber)
    }

    resolved?.let { (profile, spec, caliber) -> content(profile, spec, caliber) }
}
