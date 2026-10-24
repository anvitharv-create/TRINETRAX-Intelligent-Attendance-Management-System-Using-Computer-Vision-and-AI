package com.trinetrax.attendance.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trinetrax.attendance.data.model.AdminOverviewDto
import com.trinetrax.attendance.data.model.BranchStatDto
import com.trinetrax.attendance.data.model.LowAttendanceItemDto
import com.trinetrax.attendance.ui.components.BannerCard
import com.trinetrax.attendance.ui.components.DividerThin
import com.trinetrax.attendance.ui.components.EmptyRowText
import com.trinetrax.attendance.ui.components.ErrorState
import com.trinetrax.attendance.ui.components.LoadingView
import com.trinetrax.attendance.ui.components.SectionCard
import com.trinetrax.attendance.ui.components.StatCard
import com.trinetrax.attendance.ui.components.TableHeaderRow
import com.trinetrax.attendance.ui.components.WebColors
import com.trinetrax.attendance.ui.theme.DangerRed
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.launch

private fun pctLabel(pct: Double?): String {
    val v = pct ?: return "—"
    return if (v == v.toLong().toDouble()) "${v.toLong()}%" else "$v%"
}

@Composable
private fun SmallButton(
    text: String,
    background: Color,
    contentColor: Color = Color.White,
    onClick: () -> Unit
) {
    Surface(shape = RoundedCornerShape(6.dp), color = background, onClick = onClick) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun RowScope.Cell(
    text: String,
    bold: Boolean = false,
    color: Color = Color.Unspecified
) {
    Text(
        text = text,
        modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else null,
        color = color
    )
}

@Composable
fun AdminDashboardScreen(vm: SessionViewModel) {
    var overview by remember { mutableStateOf<AdminOverviewDto?>(null) }
    var departments by remember { mutableStateOf<List<BranchStatDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var lowBranch by remember { mutableStateOf<String?>(null) }
    var lowStudents by remember { mutableStateOf<List<LowAttendanceItemDto>>(emptyList()) }
    var lowLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        vm.repo.adminOverview().fold(
            onSuccess = { data ->
                overview = data
                departments = vm.repo.adminDepartments().getOrElse { emptyList() }
            },
            onFailure = { error = vm.repo.humanError(it) }
        )
        loading = false
    }

    val loadLowAttendance: (String) -> Unit = { branch ->
        lowBranch = branch
        lowLoading = true
        vm.requestFlowScope().launch {
            lowStudents = vm.repo.lowAttendance(branch).getOrElse { emptyList() }
            lowLoading = false
        }
    }

    val data = overview

    when {
        loading -> LoadingView("Loading college overview...")
        error != null -> ErrorState(error ?: "")
        data == null -> LoadingView("Loading college overview...")
        else -> {
            val branchRows = if (departments.isNotEmpty()) departments else data.branchStats
            val stats = listOf(
                Triple("Total Students", "${data.totalStudents ?: 0}", WebColors.Info),
                Triple("Total Teachers", "${data.totalTeachers ?: 0}", WebColors.Success),
                Triple("Departments", "${data.totalDepartments ?: 0}", WebColors.Purple),
                Triple("Overall Attendance", pctLabel(data.overallAttendancePct), WebColors.Primary),
                Triple("Present Today", "${data.presentToday ?: 0}", WebColors.Success),
                Triple("Absent Today", "${data.absentToday ?: 0}", WebColors.Danger),
                Triple("Low Attendance", "${data.lowAttendanceCount ?: 0}", WebColors.Warning)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        text = "College Overview",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item {
                    Text(
                        text = "Welcome, Principal. Here is a snapshot of the entire college.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        stats.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                pair.forEach { (label, value, accent) ->
                                    StatCard(label, value, Modifier.weight(1f), accent = accent)
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = "Departments",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item {
                    TableHeaderRow(listOf("Department", "Students", "Teachers", "Subjects", "Attendance %"))
                }
                if (branchRows.isEmpty()) {
                    item { EmptyRowText() }
                } else {
                    items(branchRows, key = { it.branch ?: it.hashCode().toString() }) { branchStat ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val branchName = branchStat.branch
                                Box(Modifier.weight(1f)) {
                                    if (branchName.isNullOrBlank()) {
                                        Text(
                                            text = "—",
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        SmallButton(branchName, WebColors.Primary) {
                                            loadLowAttendance(branchName)
                                        }
                                    }
                                }
                                Cell("${branchStat.students ?: branchStat.totalStudents ?: 0}")
                                Cell("${branchStat.teachers ?: 0}")
                                Cell("${branchStat.subjects ?: 0}")
                                Cell(
                                    text = pctLabel(branchStat.attendancePct ?: branchStat.overallAttendancePct),
                                    bold = true
                                )
                            }
                            DividerThin()
                        }
                    }
                }
                item {
                    SectionCard("Monthly Trend (14 days)") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            data.monthlyTrend.forEach { point ->
                                val pct = point.pct ?: 0.0
                                val date = point.date ?: ""
                                val barHeight = (pct / 100.0 * 120.0).coerceAtLeast(3.0)
                                val dayLabel = if (date.isEmpty()) {
                                    "—"
                                } else if (date.length >= 8) {
                                    date.substring(8)
                                } else {
                                    date
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(26.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .width(18.dp)
                                            .height(barHeight.dp)
                                            .background(
                                                color = if (pct < 75.0) WebColors.Warning else WebColors.Success,
                                                shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                                            )
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = dayLabel,
                                        fontSize = 10.sp,
                                        color = MutedText
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Column {
                        Text(
                            text = "Students Below 75% Attendance",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        BannerCard(
                            tone = "warning",
                            message = "Click a department name above to see students below threshold."
                        )
                    }
                }
                val chosenBranch = lowBranch
                if (chosenBranch != null) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$chosenBranch — Low Attendance Students",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            SmallButton("Close", Color(0xFF95A5A6)) {
                                lowBranch = null
                                lowStudents = emptyList()
                            }
                        }
                    }
                    if (lowLoading) {
                        item {
                            Text(
                                text = "Loading...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedText
                            )
                        }
                    } else if (lowStudents.isEmpty()) {
                        item { EmptyRowText("No students below threshold in this department.") }
                    } else {
                        item {
                            TableHeaderRow(listOf("Student ID", "Name", "Semester", "Section", "Attendance %"))
                        }
                        items(lowStudents, key = { it.studentId ?: it.hashCode().toString() }) { student ->
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Cell(student.studentId ?: "—")
                                    Cell(student.name ?: "—", bold = true)
                                    Cell(student.semester ?: "—")
                                    Cell(student.section ?: "—")
                                    Cell(pctLabel(student.percentage), bold = true, color = DangerRed)
                                }
                                DividerThin()
                            }
                        }
                    }
                }
            }
        }
    }
}
