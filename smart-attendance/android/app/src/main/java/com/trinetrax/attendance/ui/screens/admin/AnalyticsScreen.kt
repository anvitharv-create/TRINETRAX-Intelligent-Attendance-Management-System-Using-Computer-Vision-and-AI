package com.trinetrax.attendance.ui.screens.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.trinetrax.attendance.data.model.AnalyticsOverallDto
import com.trinetrax.attendance.data.model.SemesterAnalyticsDto
import com.trinetrax.attendance.ui.components.DividerThin
import com.trinetrax.attendance.ui.components.EmptyRowText
import com.trinetrax.attendance.ui.components.LoadingView
import com.trinetrax.attendance.ui.components.StatCard
import com.trinetrax.attendance.ui.components.TableHeaderRow
import com.trinetrax.attendance.ui.components.WebColors
import com.trinetrax.attendance.ui.components.WebOptions
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
    onClick: () -> Unit,
    contentColor: Color = Color.White
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
fun AnalyticsScreen(vm: SessionViewModel) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var overall by remember { mutableStateOf<AnalyticsOverallDto?>(null) }
    var semester by remember { mutableStateOf("") }
    var semData by remember { mutableStateOf<SemesterAnalyticsDto?>(null) }
    var semLoading by remember { mutableStateOf(false) }
    var semError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        vm.repo.analyticsOverall().fold(
            onSuccess = { overall = it },
            onFailure = { error = vm.repo.humanError(it) }
        )
        loading = false
    }

    val loadSemester: (String) -> Unit = { selectedSemester ->
        semester = selectedSemester
        semLoading = true
        semError = null
        semData = null
        vm.requestFlowScope().launch {
            vm.repo.semesterAnalytics(selectedSemester).fold(
                onSuccess = { semData = it },
                onFailure = { semError = vm.repo.humanError(it) }
            )
            semLoading = false
        }
    }

    val role = vm.user.value?.role
    val showSemesterSection = role == "teacher" || role == "hod" || role == "admin"
    val data = overall

    when {
        loading -> LoadingView("Loading analytics...")
        error != null -> Text(
            text = error ?: "",
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = DangerRed
        )
        data == null -> Text(
            text = "No data yet",
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MutedText
        )
        else -> {
            val stats = listOf(
                Triple("Total Students", "${data.totalStudents ?: 0}", WebColors.Info),
                Triple("Total Teachers", "${data.totalTeachers ?: 0}", WebColors.Success),
                Triple("Total Subjects", "${data.totalSubjects ?: 0}", WebColors.Warning),
                Triple("Classes Held", "${data.totalClasses ?: 0}", WebColors.Purple),
                Triple("Present Today", "${data.presentToday ?: 0}", WebColors.Success),
                Triple("Absent Today", "${data.absentToday ?: 0}", WebColors.Danger),
                Triple("Below 75%", "${data.belowThresholdStudents ?: 0}", WebColors.Warning)
            )
            val semesterData = semData
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        text = "Analytics Overview",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
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
                        text = "Branch-wise Attendance",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item {
                    TableHeaderRow(listOf("Branch", "Students", "Attendance %"))
                }
                if (data.branchStats.isEmpty()) {
                    item { EmptyRowText() }
                } else {
                    items(data.branchStats, key = { "br_${it.branch ?: it.hashCode()}" }) { branchStat ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Cell(branchStat.branch ?: "—")
                                Cell("${branchStat.totalStudents ?: branchStat.students ?: 0}")
                                Cell(pctLabel(branchStat.overallAttendancePct ?: branchStat.attendancePct))
                            }
                            DividerThin()
                        }
                    }
                }

                if (showSemesterSection) {
                    item {
                        Text(
                            text = "View by Semester",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            WebOptions.SEMESTERS.forEach { option ->
                                val selected = semester == option
                                SmallButton(
                                    text = option,
                                    background = if (selected) WebColors.Primary else Color(0xFFE0E0E0),
                                    contentColor = if (selected) Color.White else Color(0xFF333333),
                                    onClick = { loadSemester(option) }
                                )
                            }
                        }
                    }
                    if (semLoading) {
                        item {
                            Text(
                                text = "Loading...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedText
                            )
                        }
                    }
                    semError?.let { semErr ->
                        item {
                            Text(
                                text = semErr,
                                style = MaterialTheme.typography.bodyMedium,
                                color = DangerRed
                            )
                        }
                    }
                    if (semesterData != null) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                StatCard(
                                    label = "",
                                    value = "${semesterData.totalStudents ?: 0}",
                                    modifier = Modifier.weight(1f),
                                    accent = WebColors.Info,
                                    caption = "Students in ${semesterData.semester ?: semester}"
                                )
                                StatCard(
                                    label = "",
                                    value = "${semesterData.below_threshold.size}",
                                    modifier = Modifier.weight(1f),
                                    accent = WebColors.Warning,
                                    caption = "Below 75%"
                                )
                            }
                        }
                        if (semesterData.subjects.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Subject-wise Attendance",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            item {
                                TableHeaderRow(
                                    listOf("Subject Code", "Subject", "Total", "Present", "Attendance %")
                                )
                            }
                            items(semesterData.subjects, key = { "sub_${it.code ?: it.hashCode()}" }) { subjectStat ->
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Cell(subjectStat.code ?: "—")
                                        Cell(subjectStat.name ?: "—")
                                        Cell("${subjectStat.total ?: 0}")
                                        Cell("${subjectStat.present ?: 0}")
                                        Cell(
                                            text = pctLabel(subjectStat.pct),
                                            bold = true,
                                            color = if ((subjectStat.pct ?: 0.0) >= 75.0) {
                                                WebColors.Success
                                            } else {
                                                WebColors.Danger
                                            }
                                        )
                                    }
                                    DividerThin()
                                }
                            }
                        }
                        if (semesterData.below_threshold.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Students Below 75%",
                                    modifier = Modifier.padding(top = 12.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            item {
                                TableHeaderRow(listOf("Student ID", "Name", "Branch", "Attendance %"))
                            }
                            items(
                                semesterData.below_threshold,
                                key = { "sem_${it.studentId ?: it.hashCode()}" }
                            ) { belowStudent ->
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Cell(belowStudent.studentId ?: "—")
                                        Cell(belowStudent.name ?: "—")
                                        Cell(belowStudent.branch ?: "—")
                                        Cell(
                                            text = pctLabel(belowStudent.percentage),
                                            bold = true,
                                            color = WebColors.Danger
                                        )
                                    }
                                    DividerThin()
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Students below 75% attendance",
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item {
                    TableHeaderRow(listOf("Student ID", "Name", "Attendance %"))
                }
                if (data.belowThresholdList.isEmpty()) {
                    item { EmptyRowText("No students below threshold") }
                } else {
                    items(
                        data.belowThresholdList,
                        key = { "all_${it.studentId ?: it.hashCode()}" }
                    ) { belowStudent ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Cell(belowStudent.studentId ?: "—")
                                Cell(belowStudent.name ?: "—")
                                Cell(
                                    text = pctLabel(belowStudent.percentage),
                                    bold = true,
                                    color = Color.Black
                                )
                            }
                            DividerThin()
                        }
                    }
                }
            }
        }
    }
}
