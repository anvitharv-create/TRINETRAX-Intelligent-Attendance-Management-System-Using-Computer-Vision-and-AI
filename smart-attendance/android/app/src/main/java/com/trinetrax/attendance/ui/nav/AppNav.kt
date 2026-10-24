package com.trinetrax.attendance.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trinetrax.attendance.core.SessionManager
import com.trinetrax.attendance.data.model.UserDto
import com.trinetrax.attendance.ui.screens.admin.AdminDashboardScreen as AdminHomeScreen
import com.trinetrax.attendance.ui.screens.admin.AdminReportsScreen
import com.trinetrax.attendance.ui.screens.admin.AdminUsersScreen
import com.trinetrax.attendance.ui.screens.admin.AnalyticsScreen
import com.trinetrax.attendance.ui.screens.attendance.AttendanceHistoryScreen
import com.trinetrax.attendance.ui.screens.attendance.StudentAttendanceScreen
import com.trinetrax.attendance.ui.screens.camera.CaptureAttendanceScreen
import com.trinetrax.attendance.ui.screens.camera.FaceRegistrationScreen
import com.trinetrax.attendance.ui.screens.dashboard.HodDashboardScreen
import com.trinetrax.attendance.ui.screens.dashboard.StudentDashboardScreen
import com.trinetrax.attendance.ui.screens.dashboard.TeacherDashboardScreen
import com.trinetrax.attendance.ui.screens.login.LoginScreen
import com.trinetrax.attendance.ui.screens.manage.StudentsScreen
import com.trinetrax.attendance.ui.screens.manage.SubjectsScreen
import com.trinetrax.attendance.ui.screens.manage.TeachersScreen
import com.trinetrax.attendance.ui.screens.notifications.NotificationsScreen
import com.trinetrax.attendance.ui.screens.profile.ProfileScreen
import com.trinetrax.attendance.ui.screens.reports.HodComparisonScreen
import com.trinetrax.attendance.ui.screens.reports.ReportsScreen
import com.trinetrax.attendance.ui.screens.staff.AiWorkflowScreen
import com.trinetrax.attendance.ui.screens.staff.TeacherReportsScreen
import com.trinetrax.attendance.ui.screens.student.StudentReportScreen
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.viewmodel.SessionViewModel

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/**
 * Bottom navigation mirrors the web portal's sidebar one-for-one, so every
 * role keeps the exact destinations it has on the website. Staff roles have up
 * to 7 entries, so the strip scrolls horizontally instead of dropping any.
 */
private fun tabsFor(role: String): List<Tab> = when (role) {
    "admin" -> listOf(
        Tab(Routes.DASHBOARD, "Overview", Icons.Filled.Home),
        Tab(Routes.ADMIN_USERS, "Users", Icons.Outlined.Badge),
        Tab(Routes.ADMIN_REPORTS, "Reports", Icons.Outlined.Assessment),
        Tab(Routes.NOTIFICATIONS, "Alerts", Icons.Filled.Notifications),
        Tab(Routes.PROFILE, "Profile", Icons.Filled.Person)
    )
    "hod" -> listOf(
        Tab(Routes.DASHBOARD, "Dashboard", Icons.Filled.Home),
        Tab(Routes.PEOPLE, "Students", Icons.Outlined.Groups),
        Tab(Routes.TEACHERS, "Teachers", Icons.Outlined.Badge),
        Tab(Routes.SUBJECTS, "Subjects", Icons.Outlined.MenuBook),
        Tab(Routes.CAPTURE, "Attendance", Icons.Outlined.CameraAlt),
        Tab(Routes.ANALYTICS, "Analytics", Icons.Outlined.Assessment),
        Tab(Routes.REPORTS, "Reports", Icons.Outlined.Assessment),
        Tab(Routes.PROFILE, "Profile", Icons.Filled.Person)
    )
    "teacher" -> listOf(
        Tab(Routes.DASHBOARD, "Dashboard", Icons.Filled.Home),
        Tab(Routes.AI_WORKFLOW, "Take Attendance", Icons.Outlined.CameraAlt),
        Tab(Routes.HISTORY, "History", Icons.Outlined.History),
        Tab(Routes.REPORTS, "Reports", Icons.Outlined.Assessment),
        Tab(Routes.PEOPLE, "Students", Icons.Outlined.Groups),
        Tab(Routes.PROFILE, "Profile", Icons.Filled.Person)
    )
    else -> listOf(
        Tab(Routes.DASHBOARD, "Dashboard", Icons.Filled.Home),
        Tab(Routes.HISTORY, "My Attendance", Icons.Outlined.History),
        Tab(Routes.REPORTS, "Reports", Icons.Outlined.Assessment),
        Tab(Routes.PROFILE, "Profile", Icons.Filled.Person)
    )
}

@Composable
fun AppRoot(session: SessionManager) {
    val vm: SessionViewModel = viewModel(factory = SessionViewModel.Factory)
    val token by vm.token.collectAsState()

    if (token.isNullOrBlank()) {
        LoginScreen(vm)
    } else {
        MainScaffold(vm, session)
    }
}

@Composable
private fun MainScaffold(vm: SessionViewModel, session: SessionManager) {
    val navController = rememberNavController()
    val user by vm.user.collectAsState()
    val role = user?.role ?: "student"
    val tabs = remember(role) { tabsFor(role) }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Routes.DASHBOARD

    Scaffold(
        bottomBar = {
            // Horizontally scrollable so 6-8 website destinations all fit
            // without a "More" menu hiding any feature. The selected tab is
            // scrolled into view so it is never hidden off-screen.
            val scrollState = rememberScrollState()
            LaunchedEffect(currentRoute, tabs.size) {
                val index = tabs.indexOfFirst { it.route == currentRoute }
                val target = (index * 104).coerceAtLeast(0)
                val delta = target - scrollState.value
                if (delta != 0) scrollState.dispatchRawDelta(delta.toFloat())
            }
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                tabs.forEach { tab ->
                    val selectedTab = currentRoute == tab.route
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (selectedTab) MaterialTheme.colorScheme.primary else MutedText,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selectedTab) MaterialTheme.colorScheme.primary else MutedText,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.DASHBOARD) {
                when (role) {
                    "admin" -> AdminHomeScreen(vm)
                    "hod" -> HodDashboardScreen(vm) { navController.navigate(it) }
                    "teacher" -> TeacherDashboardScreen(vm) { navController.navigate(it) }
                    else -> StudentDashboardScreen(vm) { navController.navigate(it) }
                }
            }
            composable(Routes.PEOPLE) { StudentsScreen(vm) { navController.navigate(it) } }
            composable(Routes.TEACHERS) { TeachersScreen(vm) }
            composable(Routes.SUBJECTS) { SubjectsScreen(vm) }
            composable(Routes.HISTORY) {
                if (role == "student") {
                    StudentAttendanceScreen(vm)
                } else {
                    AttendanceHistoryScreen(vm)
                }
            }
            composable(Routes.CAPTURE) { CaptureAttendanceScreen(vm) }
            composable(Routes.REGISTER_FACE) { FaceRegistrationScreen(vm) }
            composable(Routes.REPORTS) {
                when (role) {
                    "admin" -> AdminReportsScreen(vm)
                    "teacher" -> TeacherReportsScreen(vm)
                    "hod" -> HodComparisonScreen(vm)
                    else -> StudentReportScreen(vm)
                }
            }
            composable(Routes.ADMIN_USERS) { AdminUsersScreen(vm) }
            composable(Routes.ADMIN_REPORTS) { AdminReportsScreen(vm) }
            composable(Routes.ANALYTICS) { AnalyticsScreen(vm) }
            composable(Routes.STUDENT_REPORT) { StudentReportScreen(vm) }
            composable(Routes.AI_WORKFLOW) { AiWorkflowScreen(vm) }
            composable(Routes.NOTIFICATIONS) { NotificationsScreen(vm) }
            composable(Routes.PROFILE) {
                ProfileScreen(vm, onLoggedOut = { navController.navigate(Routes.DASHBOARD) }) {
                    navController.navigate(it)
                }
            }
        }
    }
}
