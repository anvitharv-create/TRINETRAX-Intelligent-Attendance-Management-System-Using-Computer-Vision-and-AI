package com.trinetrax.attendance.ui.screens.reports

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trinetrax.attendance.data.model.AdminOverviewDto
import com.trinetrax.attendance.data.model.AnalyticsOverallDto
import com.trinetrax.attendance.ui.components.ErrorState
import com.trinetrax.attendance.ui.components.KeyValueRow
import com.trinetrax.attendance.ui.components.LoadingView
import com.trinetrax.attendance.ui.components.SectionCard
import com.trinetrax.attendance.ui.components.StatCard
import com.trinetrax.attendance.ui.screens.dashboard.LowAttendanceList
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.ui.theme.SuccessGreen
import com.trinetrax.attendance.viewmodel.SessionViewModel

/**
 * Reports screen. Analytics are always whatever the backend computes for the
 * signed-in role (admin sees the college, HOD/teacher their department,
 * students their own college-wide numbers) - never client-side fabrications.
 */
@Composable
fun ReportsScreen(vm: SessionViewModel) {
    val user = vm.user.value
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var analytics by remember { mutableStateOf<AnalyticsOverallDto?>(null) }
    var overview by remember { mutableStateOf<AdminOverviewDto?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        val a = vm.repo.analyticsOverall().getOrElse {
            error = vm.repo.humanError(it); loading = false; return@LaunchedEffect
        }
        analytics = a
        if (user?.role == "admin") {
            overview = vm.repo.adminOverview().getOrNull()
        }
        loading = false
    }

    when {
        loading -> LoadingView("Loading reports…")
        error != null -> ErrorState(error!!)
        else -> {
            val data = analytics
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text("Reports", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCard("Students", "${data?.totalStudents ?: 0}", Modifier.width(150.dp))
                        StatCard("Teachers", "${data?.totalTeachers ?: 0}", Modifier.width(150.dp))
                        StatCard("Subjects", "${data?.totalSubjects ?: 0}", Modifier.width(150.dp))
                        StatCard(
                            "Attendance",
                            data?.overallAttendancePct?.let { "$it%" } ?: "—",
                            Modifier.width(150.dp),
                            accent = SuccessGreen
                        )
                    }
                }
                if (!data?.branchStats.isNullOrEmpty()) {
                    item {
                        SectionCard("Attendance by department") {
                            data!!.branchStats.forEach { b ->
                                KeyValueRow(
                                    b.branch ?: "—",
                                    "${b.totalStudents ?: b.students ?: 0} students · ${b.overallAttendancePct ?: b.attendancePct ?: "—"}%"
                                )
                            }
                        }
                    }
                }
                overview?.let { ov ->
                    if (ov.monthlyTrend.isNotEmpty()) {
                        item {
                            SectionCard("Attendance trend") {
                                ov.monthlyTrend.forEach { point ->
                                    KeyValueRow(point.date ?: "—", "${point.pct ?: 0.0}%")
                                }
                            }
                        }
                    }
                    if (ov.lowAttendanceStudents.isNotEmpty()) {
                        item { LowAttendanceList(ov.lowAttendanceStudents) }
                    }
                }
                if (data?.branchStats.isNullOrEmpty() && overview == null) {
                    item {
                        Text(
                            "No analytics are available for your role yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
                        )
                    }
                }
            }
        }
    }
}
