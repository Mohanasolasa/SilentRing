package com.example.autosilent.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.autosilent.AutoSilentApplication
import com.example.autosilent.ui.details.ScheduleDetailsScreen
import com.example.autosilent.ui.details.ScheduleDetailsViewModel
import com.example.autosilent.ui.form.ScheduleFormScreen
import com.example.autosilent.ui.form.ScheduleFormViewModel
import com.example.autosilent.ui.home.HomeScreen
import com.example.autosilent.ui.home.HomeViewModel
import com.example.autosilent.ui.permissions.PermissionSetupScreen
import com.example.autosilent.ui.permissions.PermissionSetupViewModel
import com.example.autosilent.ui.settings.SettingsScreen
import com.example.autosilent.ui.viewmodel.AppViewModelFactory

@Composable
fun AppNavigation(
    app: AutoSilentApplication,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val factory = AppViewModelFactory(app)

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        // 1. Home Screen
        composable(route = Screen.Home.route) {
            val homeViewModel: HomeViewModel = viewModel(factory = factory)
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToCreate = { navController.navigate(Screen.CreateSchedule.route) },
                onNavigateToDetails = { scheduleId ->
                    navController.navigate(Screen.ScheduleDetails.createRoute(scheduleId))
                },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToPermissions = { navController.navigate(Screen.PermissionSetup.route) }
            )
        }

        // 2. Create Schedule Screen
        composable(route = Screen.CreateSchedule.route) {
            val formViewModel: ScheduleFormViewModel = viewModel(factory = factory)
            ScheduleFormScreen(
                viewModel = formViewModel,
                scheduleId = null,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 3. Edit Schedule Screen
        composable(
            route = Screen.EditSchedule.route,
            arguments = listOf(
                navArgument(Screen.EditSchedule.ARG_SCHEDULE_ID) { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val scheduleId = backStackEntry.arguments?.getLong(Screen.EditSchedule.ARG_SCHEDULE_ID)
            val formViewModel: ScheduleFormViewModel = viewModel(factory = factory)
            ScheduleFormScreen(
                viewModel = formViewModel,
                scheduleId = scheduleId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 4. Schedule Details Screen
        composable(
            route = Screen.ScheduleDetails.route,
            arguments = listOf(
                navArgument(Screen.ScheduleDetails.ARG_SCHEDULE_ID) { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val scheduleId = backStackEntry.arguments?.getLong(Screen.ScheduleDetails.ARG_SCHEDULE_ID) ?: -1L
            val detailsViewModel: ScheduleDetailsViewModel = viewModel(factory = factory)
            ScheduleDetailsScreen(
                viewModel = detailsViewModel,
                scheduleId = scheduleId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { id ->
                    navController.navigate(Screen.EditSchedule.createRoute(id))
                }
            )
        }

        // 5. Settings Screen
        composable(route = Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPermissions = { navController.navigate(Screen.PermissionSetup.route) },
                isSoundControlGranted = app.soundController.canControlSound(),
                isExactAlarmGranted = app.alarmScheduler.canScheduleExactAlarms()
            )
        }

        // 6. Permission / Setup Screen
        composable(route = Screen.PermissionSetup.route) {
            val permissionsViewModel: PermissionSetupViewModel = viewModel(factory = factory)
            PermissionSetupScreen(
                viewModel = permissionsViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
