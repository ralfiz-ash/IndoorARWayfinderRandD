package com.indoorar.wayfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHostState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.indoorar.wayfinder.ui.diagnostics.AccuracyLogScreen
import com.indoorar.wayfinder.ui.home.HomeScreen
import com.indoorar.wayfinder.ui.marker.MarkerScreen
import com.indoorar.wayfinder.ui.navigation.NavigationScreen
import com.indoorar.wayfinder.ui.selection.PointSelectionScreen
import com.indoorar.wayfinder.ui.theme.WayfinderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WayfinderApp).container
        setContent {
            WayfinderTheme { WayfinderNavHost(container) }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val SELECT = "select"
    const val MARKER = "marker"
    const val ACCURACY = "accuracy"
    const val NAVIGATE = "navigate/{id}"
    fun navigate(id: String) = "navigate/$id"
    const val SAVED_NAME = "saved_name"
}

@Composable
private fun WayfinderNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { entry ->
            val snackbar = remember { SnackbarHostState() }
            val savedName = entry.savedStateHandle.get<String>(Routes.SAVED_NAME)
            LaunchedEffect(savedName) {
                if (savedName != null) {
                    entry.savedStateHandle.remove<String>(Routes.SAVED_NAME)
                    snackbar.showSnackbar("Saved $savedName")
                }
            }
            HomeScreen(
                container = container,
                snackbarHostState = snackbar,
                onAddDestination = { nav.navigate(Routes.SELECT) },
                onShowMarker = { nav.navigate(Routes.MARKER) },
                onNavigate = { id -> nav.navigate(Routes.navigate(id)) },
                onShowAccuracyLog = { nav.navigate(Routes.ACCURACY) },
            )
        }
        composable(Routes.SELECT) {
            PointSelectionScreen(
                container = container,
                onClose = { nav.popBackStack() },
                onSaved = { name ->
                    nav.previousBackStackEntry?.savedStateHandle?.set(Routes.SAVED_NAME, name)
                    nav.popBackStack()
                },
            )
        }
        composable(Routes.NAVIGATE) { entry ->
            val id = checkNotNull(entry.arguments?.getString("id"))
            NavigationScreen(container = container, destinationId = id, onExit = { nav.popBackStack(Routes.HOME, inclusive = false) })
        }
        composable(Routes.ACCURACY) {
            AccuracyLogScreen(container = container, onBack = { nav.popBackStack() })
        }
        composable(Routes.MARKER) {
            MarkerScreen(container = container, onBack = { nav.popBackStack() })
        }
    }
}
