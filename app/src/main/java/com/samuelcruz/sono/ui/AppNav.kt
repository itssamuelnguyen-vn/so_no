package com.samuelcruz.sono.ui

import android.app.Activity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("debts", "Sổ nợ", Icons.AutoMirrored.Filled.MenuBook),
    Tab("hui", "Hụi", Icons.Default.Groups),
    Tab("calendar", "Lịch", Icons.Default.CalendarMonth),
    Tab("stats", "Thống kê", Icons.Default.BarChart),
    Tab("settings", "Cài đặt", Icons.Default.Settings),
)

/** Routes, like Rails routes: five tabs plus detail and edit screens on top. */
@Composable
fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val onTab = tabs.any { it.route == route }
    val app = app()

    // Tab headers are drawn on the teal gradient (light status bar icons); other screens follow the theme.
    val view = LocalView.current
    val dark = LocalIsDark.current
    LaunchedEffect(route, dark) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val bars = WindowCompat.getInsetsController(window, view)
        bars.isAppearanceLightNavigationBars = !dark
        bars.isAppearanceLightStatusBars = !dark && (route != "debts" && route != "hui")
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        app.messages.collect { m ->
            val r = snackbar.showSnackbar(m.text, actionLabel = m.action, withDismissAction = true, duration = if (m.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (onTab) BottomBar(nav, route) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        NavHost(
            nav,
            startDestination = "debts",
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            enterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.97f) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.97f) },
        ) {
            composable("debts") {
                DebtsScreen(
                    pad,
                    onPerson = { nav.navigate("person/$it") },
                    onDebt = { nav.navigate("debt/$it") },
                    onAdd = { nav.navigate("debtEdit/0") },
                )
            }
            composable("hui") {
                HuiListScreen(pad, onOpen = { nav.navigate("huiView/$it") }, onAdd = { nav.navigate("huiEdit/0") })
            }
            composable("calendar") {
                CalendarScreen(pad, onDebt = { nav.navigate("debt/$it") }, onRound = { hui, n -> nav.navigate("round/$hui/$n") })
            }
            composable("stats") { StatsScreen(pad) }
            composable("settings") { SettingsScreen(pad) }
            composable("iou/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                IouScreen(e.arguments?.getLong("id") ?: 0, onBack = { nav.popBackStack() })
            }

            composable("person/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments?.getLong("id") ?: 0
                PersonScreen(
                    id, onBack = { nav.popBackStack() },
                    onDebt = { nav.navigate("debt/$it") },
                    onNewDebt = { dir -> nav.navigate("debtEdit/0?person=$id&dir=$dir") },
                )
            }
            composable("debt/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments?.getLong("id") ?: 0
                DebtScreen(id, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("debtEdit/$id") }, onIou = { nav.navigate("iou/$id") })
            }
            composable(
                "debtEdit/{id}?person={person}&dir={dir}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("person") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("dir") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { e ->
                val id = e.arguments?.getLong("id") ?: 0
                DebtEditScreen(
                    id, e.arguments?.getLong("person") ?: 0, e.arguments?.getInt("dir") ?: 0,
                    onDone = { saved ->
                        nav.popBackStack()
                        // A new debt opens its page, so the schedule and buttons are right there.
                        if (saved != null && id == 0L) nav.navigate("debt/$saved")
                    },
                )
            }
            composable("huiEdit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments?.getLong("id") ?: 0
                HuiEditScreen(id, onDone = { saved ->
                    nav.popBackStack()
                    if (saved != null && id == 0L) nav.navigate("huiView/$saved")
                })
            }
            composable("huiView/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments?.getLong("id") ?: 0
                HuiScreen(
                    id, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("huiEdit/$id") },
                    onRound = { nav.navigate("round/$id/$it") }, onDebt = { nav.navigate("debt/$it") },
                )
            }
            composable(
                "round/{id}/{n}?edit={edit}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("n") { type = NavType.IntType },
                    navArgument("edit") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { e ->
                val id = e.arguments?.getLong("id") ?: 0
                val n = e.arguments?.getInt("n") ?: 1
                RoundScreen(
                    id, n, e.arguments?.getBoolean("edit") == true,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate("round/$id/$n?edit=true") },
                    onDebt = { nav.navigate("debt/$it") },
                )
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun BottomBar(nav: NavHostController, current: String?) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = current == tab.route,
                onClick = { if (current != tab.route) nav.switchTab(tab.route) },
                icon = { Icon(tab.icon, null) },
                label = { Text(tab.label, maxLines = 1) },
            )
        }
    }
}
