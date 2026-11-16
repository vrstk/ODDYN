package com.oddyn.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.oddyn.app.OddynViewModel

private enum class Destination(val route: String, val title: String, val icon: ImageVector) {
    HOME("home", "Главная", Icons.Filled.Home),
    MATCHES("matches", "Матчи", Icons.AutoMirrored.Filled.List),
    EXPRESSES("expresses", "Экспрессы", Icons.Filled.Share),
    HISTORY("history", "История", Icons.Filled.DateRange),
    SETTINGS("settings", "Настройки", Icons.Filled.Settings)
}

private const val eventRoute = "event/{eventId}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OddynApp(model: OddynViewModel) {
    val nav = rememberNavController()
    val active by nav.currentBackStackEntryAsState()
    val route = active?.destination?.route
    val dashboard by model.dashboard.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val warning by model.searchWarning.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val mode = dashboard.preferences.mode

    LaunchedEffect(model) {
        model.notices.collect { snackbar.showSnackbar(it) }
    }

    fun goTo(destination: Destination) {
        nav.navigate(destination.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openEvent(id: String) = nav.navigate("event/${Uri.encode(id)}")

    Scaffold(
        containerColor = Graphite,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (route == eventRoute) "Анализ матча" else "ODDYN",
                        fontSize = if (route == eventRoute) 20.sp else 24.sp,
                        letterSpacing = if (route == eventRoute) 0.sp else 2.sp,
                        fontWeight = FontWeight.Bold,
                        color = White
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Graphite),
                navigationIcon = {
                    if (route == eventRoute) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Silver)
                        }
                    }
                },
                actions = {
                    if (route != Destination.HISTORY.route) Box(Modifier.padding(end = 16.dp)) { ModeTag(mode) }
                }
            )
        },
        bottomBar = {
            if (route != eventRoute) {
                NavigationBar(containerColor = Panel) {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = route == item.route,
                            onClick = { goTo(item) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = {
                                Text(item.title, maxLines = 1, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
                            }
                        )
                    }
                }
            }
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            NavHost(
                navController = nav,
                startDestination = Destination.HOME.route,
                modifier = Modifier.widthIn(max = 740.dp).fillMaxWidth()
            ) {
                composable(Destination.HOME.route) {
                    ScheduleScreen(dashboard, busy, warning, true, model::pickDate, model::search, ::openEvent)
                }
                composable(Destination.MATCHES.route) {
                    ScheduleScreen(dashboard, busy, warning, false, model::pickDate, model::search, ::openEvent)
                }
                composable(Destination.EXPRESSES.route) {
                    ExpressScreen(model, mode, busy, onFindMatches = { goTo(Destination.MATCHES) })
                }
                composable(Destination.HISTORY.route) { HistoryScreen(model, busy) }
                composable(Destination.SETTINGS.route) { SettingsScreen(model, dashboard.preferences, busy) }
                composable(eventRoute, arguments = listOf(navArgument("eventId") { type = NavType.StringType })) { entry ->
                    val match = dashboard.search.events.firstOrNull { it.id == entry.arguments?.getString("eventId") }
                    MatchScreen(match, model, busy)
                }
            }
        }
    }
}
