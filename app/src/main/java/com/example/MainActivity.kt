package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.core.firebase.configureAppCheck
import com.example.core.navigation.GurivaRoutes
import com.example.data.remote.AiGatewayFactory
import com.example.data.repository.GurivaLocalRepository
import com.example.feature.classroom.ClassroomScreen
import com.example.feature.classroom.ClassroomViewModel
import com.example.feature.home.HomeScreen
import com.example.feature.home.MainAppViewModel
import com.example.feature.lessons.LessonsScreen
import com.example.feature.progress.ProgressScreen
import com.example.feature.settings.SettingsScreen
import com.example.ui.theme.GurivaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure App Check with launch intent BEFORE any UI, ViewModels, or Firebase operations.
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            GurivaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GurivaAppShell()
                }
            }
        }
    }
}

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val topLevelDestinations = listOf(
    TopLevelDestination(GurivaRoutes.ROUTE_HOME, "Home", Icons.Rounded.Home, Icons.Outlined.Home),
    TopLevelDestination(GurivaRoutes.ROUTE_LESSONS, "Lessons", Icons.Rounded.AutoStories, Icons.Outlined.AutoStories),
    TopLevelDestination(GurivaRoutes.ROUTE_PROGRESS, "Progress", Icons.Rounded.Insights, Icons.Outlined.Insights),
    TopLevelDestination(GurivaRoutes.ROUTE_SETTINGS, "Settings", Icons.Rounded.Tune, Icons.Outlined.Tune)
)

@Composable
fun GurivaAppShell() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val mainViewModel: MainAppViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                MainAppViewModel(
                    localRepo = GurivaLocalRepository(app),
                    aiRepo = AiGatewayFactory.create(app)
                )
            }
        }
    )

    val showChrome = currentRoute in topLevelDestinations.map { it.route }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = if (showChrome) WindowInsets.statusBars else WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showChrome && !useNavRail) {
                    Column {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp
                        ) {
                            topLevelDestinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(dest.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.testTag("nav_${dest.route}")
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showChrome && useNavRail) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        topLevelDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) }
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = GurivaRoutes.ROUTE_HOME,
                    modifier = Modifier.weight(1f).fillMaxSize()
                ) {
                    composable(GurivaRoutes.ROUTE_HOME) {
                        HomeScreen(
                            viewModel = mainViewModel,
                            onStartNewLesson = { subj, lvl ->
                                navController.navigate(
                                    GurivaRoutes.classroomRoute(lessonId = null, subject = subj, level = lvl)
                                )
                            },
                            onResumeLesson = { id, subj, lvl ->
                                navController.navigate(
                                    GurivaRoutes.classroomRoute(lessonId = id, subject = subj, level = lvl)
                                )
                            },
                            onViewAllLessons = {
                                navController.navigate(GurivaRoutes.ROUTE_LESSONS)
                            }
                        )
                    }

                    composable(GurivaRoutes.ROUTE_LESSONS) {
                        LessonsScreen(
                            viewModel = mainViewModel,
                            onResumeLesson = { id, subj, lvl ->
                                navController.navigate(
                                    GurivaRoutes.classroomRoute(lessonId = id, subject = subj, level = lvl)
                                )
                            }
                        )
                    }

                    composable(GurivaRoutes.ROUTE_PROGRESS) {
                        ProgressScreen(viewModel = mainViewModel)
                    }

                    composable(GurivaRoutes.ROUTE_SETTINGS) {
                        SettingsScreen(viewModel = mainViewModel)
                    }

                    composable(
                        route = GurivaRoutes.ROUTE_CLASSROOM_PATTERN,
                        arguments = listOf(
                            navArgument("lessonId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument("subject") {
                                type = NavType.StringType
                                defaultValue = "Maths"
                            },
                            navArgument("level") {
                                type = NavType.StringType
                                defaultValue = "Beginner"
                            }
                        )
                    ) { entry ->
                        val lessonId = entry.arguments?.getString("lessonId")
                        val subject = entry.arguments?.getString("subject") ?: "Maths"
                        val level = entry.arguments?.getString("level") ?: "Beginner"

                        val classroomVm: ClassroomViewModel = viewModel(
                            key = "classroom_${lessonId ?: "new"}_${subject}",
                            factory = viewModelFactory {
                                initializer {
                                    val app = checkNotNull(this[APPLICATION_KEY])
                                    ClassroomViewModel(
                                        app = app,
                                        localRepo = GurivaLocalRepository(app),
                                        aiRepo = AiGatewayFactory.create(app),
                                        initialLessonId = lessonId,
                                        initialSubject = subject,
                                        initialLevel = level
                                    )
                                }
                            }
                        )

                        ClassroomScreen(
                            viewModel = classroomVm,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
