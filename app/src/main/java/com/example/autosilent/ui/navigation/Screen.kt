package com.example.autosilent.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object CreateSchedule : Screen("create_schedule")
    object EditSchedule : Screen("edit_schedule/{scheduleId}") {
        const val ARG_SCHEDULE_ID = "scheduleId"
        fun createRoute(scheduleId: Long): String = "edit_schedule/$scheduleId"
    }
    object ScheduleDetails : Screen("schedule_details/{scheduleId}") {
        const val ARG_SCHEDULE_ID = "scheduleId"
        fun createRoute(scheduleId: Long): String = "schedule_details/$scheduleId"
    }
    object Settings : Screen("settings")
    object PermissionSetup : Screen("permission_setup")
}
