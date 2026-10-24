package com.trinetrax.attendance.ui.screens.admin

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trinetrax.attendance.data.model.AdminOverviewDto
import com.trinetrax.attendance.data.model.DepartmentDetailDto
import com.trinetrax.attendance.data.model.SubjectDto
import com.trinetrax.attendance.data.model.SubjectReportDto
import com.trinetrax.attendance.ui.components.BannerCard
import com.trinetrax.attendance.ui.components.DividerThin
import com.trinetrax.attendance.ui.components.EmptyRowText
import com.trinetrax.attendance.ui.components.FieldSelect
import com.trinetrax.attendance.ui.components.FilterSelect
import com.trinetrax.attendance.ui.components.TableHeaderRow
import com.trinetrax.attendance.ui.components.WebColors
import com.trinetrax.attendance.ui.components.WebOptions
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class GeneratedReport(
    val type: String,
    val overview: AdminOverviewDto? = null,
    val department: DepartmentDetailDto? = null,
    val branch: String = "",
    val subjectReport: SubjectReportDto? = null,
    val subject: SubjectDto? = null
)

private data class SubjectRow(
    val studentId: String,
    val total: Int,
    val present: Int,
    val pct: Int
)

private fun pctLabel(pct: Double?): String {
    val v = pct ?: return "—"
    return if (v == v.toLong().toDouble()) "${v.toLong()}%" else "$v%"
}

@Composable
private fun SmallButton(
    text: String,
    background: Color,
    onClick: () -> Unit,
    contentColor: Color = Color.White,
    border: BorderStroke? = null
) {
    Surface(shape = RoundedCornerShape(6.dp), color = background, border = border, onClick = onClick) {
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
private fun MiniTile(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF8F9FA)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                color = WebColors.Primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                fontSize = 12.sp,
                color = MutedText
            )
        }
    }
}

@Composable
fun AdminReportsScreen(vm: SessionViewModel) {
    var reportType by remember { mutableStateOf("college") }
    var overview by remember { mutableStateOf<AdminOverviewDto?>(null) }
    var branch by remember { mutableStateOf("ISE") }
    var semester by remember { mutableStateOf("1st Semester") }
    var subjects by remember { mutableStateOf<List<SubjectDto>>(emptyList()) }
    var subjectId by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<GeneratedReport?>(null) }
    var generating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        overview = vm.repo.adminOverview().getOrNull()
    }

    LaunchedEffect(branch, semester) {
        subjects = vm.repo.subjects(branch, semester).getOrElse { emptyList() }
        subjectId = ""
    }

    val generateReport: () -> Unit = {
        message = null
        generating = true
        vm.requestFlowScope().launch {
            when (reportType) {
                "college" -> {
                    report = GeneratedReport(type = "college", overview = overview)
                    generating = false
                }
                "department" -> {
                    vm.repo.adminDepartment(branch).fold(
                        onSuccess = {
                            report = GeneratedReport(type = "department", department = it, branch = branch)
                        },
                        onFailure = { message = "danger" to vm.repo.humanError(it) }
                    )
                    generating = false
                }
                "subject" -> {
                    val id = subjectId.toIntOrNull()
                    if (id == null) {
                        message = "warning" to "Select a subject first."
                        generating = false
                    } else {
                        vm.repo.subjectReport(id).fold(
                            onSuccess = { data ->
                                report = GeneratedReport(
                                    type = "subject",
                                    subjectReport = data,
                                    subject = subjects.firstOrNull { it.id == id }
                                )
                            },
                            onFailure = { message = "danger" to vm.repo.humanError(it) }
                        )
                        generating = false
                    }
                }
                else -> {
                    vm.repo.adminOverview().fold(
                        onSuccess = { report = GeneratedReport(type = "low", overview = it) },
                        onFailure = { message = "danger" to vm.repo.humanError(it) }
                    )
                    generating = false
                }
            }
        }
    }

    val reportTypes = listOf(
        "college" to "College Report",
        "department" to "Department Report",
        "subject" to "Attendance Report",
        "low" to "Low Attendance Report"
    )

    val context = LocalContext.current

    /**
     * Sends the same filters the report was generated with, so the workbook can
     * never disagree with what is on screen.
     */
    val exportReport: () -> Unit = {
        val current = report
        if (current == null) {
            message = "warning" to "Generate a report first."
        } else {
            val fileName = "admin_report_${current.type}.xlsx"
            exporting = true
            vm.requestFlowScope().launch {
                val outcome = withContext(Dispatchers.IO) {
                    vm.repo.adminReportsExport(
                        reportType = current.type,
                        branch = if (current.type == "department" || current.type == "subject") branch else null,
                        semester = if (current.type == "subject") semester else null,
                        subjectId = current.subject?.id
                    ).mapCatching { body ->
                        val cacheFile = File(context.cacheDir, fileName)
                        cacheFile.writeBytes(body.bytes())
                        val request = DownloadManager.Request(Uri.fromFile(cacheFile))
                            .setTitle(fileName)
                            .setDescription(current.type.replaceFirstChar { it.uppercase() } + " report")
                            .setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            .setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                            )
                            .setDestinationInExternalPublicDir(
                                android.os.Environment.DIRECTORY_DOWNLOADS,
                                fileName
                            )
                        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        manager.enqueue(request)
                    }
                }
                exporting = false
                outcome.onSuccess { message = "success" to "Excel report saved to your Downloads folder." }
                    .onFailure { message = "danger" to vm.repo.humanError(it) }
            }
        }
    }

    val subjectLabel: (SubjectDto) -> String = { "${it.name ?: "—"} (${it.code ?: "—"})" }
    val subjectLabels = subjects.map(subjectLabel)
    val selectedSubjectLabel = subjects.firstOrNull { it.id?.toString() == subjectId }
        ?.let(subjectLabel) ?: ""
    val subjectIdByLabel = subjects.associateBy(subjectLabel) { it.id?.toString() ?: "" }

    val today = remember { DateFormat.getDateInstance().format(Date()) }

    val currentReport = report
    val subjectRows = remember(currentReport) {
        val current = currentReport
        if (current != null && current.type == "subject") {
            val data = current.subjectReport
            if (data != null) {
                data.records.map { (studentId, records) ->
                    val total = records.size
                    val present = records.count { it.status == "present" }
                    val pct = if (total > 0) Math.round(present * 100.0 / total).toInt() else 0
                    SubjectRow(studentId, total, present, pct)
                }
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Reports",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        item {
            Text(
                text = "Generate college-wide, department, subject, and low-attendance reports.",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                reportTypes.forEach { (key, label) ->
                    val selected = reportType == key
                    SmallButton(
                        text = label,
                        background = if (selected) WebColors.Primary else Color.White,
                        contentColor = if (selected) Color.White else Color(0xFF333333),
                        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        onClick = {
                            reportType = key
                            report = null
                        }
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                if (reportType == "department" || reportType == "subject") {
                    FieldSelect(
                        label = if (reportType == "department") "Department" else "Branch",
                        options = WebOptions.BRANCHES,
                        value = branch,
                        onSelect = { branch = it },
                        modifier = Modifier.width(150.dp)
                    )
                    FieldSelect(
                        label = "Semester",
                        options = WebOptions.SEMESTERS,
                        value = semester,
                        onSelect = { semester = it },
                        modifier = Modifier.width(180.dp)
                    )
                }
                if (reportType == "subject") {
                    FilterSelect(
                        label = "Subject",
                        options = subjectLabels,
                        value = selectedSubjectLabel,
                        onSelect = { label -> subjectId = subjectIdByLabel[label] ?: "" },
                        modifier = Modifier.width(230.dp),
                        placeholder = "Select subject"
                    )
                }
                Button(
                    onClick = { generateReport() },
                    enabled = !generating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WebColors.Primary,
                        contentColor = Color.White
                    )
                ) {
                    Text(if (generating) "Generating..." else "Generate Report")
                }
                // Export appears only once there is a report on screen, and always
                // uses the same filters the report was generated with.
                if (currentReport != null) {
                    OutlinedButton(
                        onClick = { exportReport() },
                        enabled = !exporting,
                        border = BorderStroke(1.dp, WebColors.Primary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WebColors.Primary)
                    ) {
                        Text(if (exporting) "Exporting..." else "Export Excel")
                    }
                }
            }
        }
        message?.let { (tone, text) ->
            item { BannerCard(tone = tone, message = text) }
        }

        if (currentReport != null) {
            when (currentReport.type) {
                "college" -> {
                    val ov = currentReport.overview
                    if (ov != null) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "College Report — $today",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val tiles = listOf(
                                        "Students" to "${ov.totalStudents ?: 0}",
                                        "Teachers" to "${ov.totalTeachers ?: 0}",
                                        "Departments" to "${ov.totalDepartments ?: 0}",
                                        "Overall %" to pctLabel(ov.overallAttendancePct),
                                        "Present Today" to "${ov.presentToday ?: 0}",
                                        "Absent Today" to "${ov.absentToday ?: 0}",
                                        "Low Attendance" to "${ov.lowAttendanceCount ?: 0}"
                                    )
                                    tiles.chunked(2).forEach { pair ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            pair.forEach { (tileLabel, tileValue) ->
                                                Box(Modifier.weight(1f)) {
                                                    MiniTile(tileLabel, tileValue)
                                                }
                                            }
                                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                    Text(
                                        text = "Department-wise",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    TableHeaderRow(listOf("Department", "Students", "Attendance %"))
                                    if (ov.branchStats.isEmpty()) {
                                        EmptyRowText()
                                    } else {
                                        ov.branchStats.forEach { branchStat ->
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Cell(branchStat.branch ?: "—")
                                                    Cell("${branchStat.students ?: branchStat.totalStudents ?: 0}")
                                                    Cell(
                                                        pctLabel(
                                                            branchStat.attendancePct
                                                                ?: branchStat.overallAttendancePct
                                                        )
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
                }
                "department" -> {
                    val department = currentReport.department
                    if (department != null) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Department Report — ${currentReport.branch}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    TableHeaderRow(listOf("Semester", "Students", "Attendance %"))
                                    if (department.semesters.isEmpty()) {
                                        EmptyRowText()
                                    } else {
                                        department.semesters.forEach { semesterStat ->
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Cell(semesterStat.semester ?: "—")
                                                    Cell("${semesterStat.students ?: 0}")
                                                    Cell(pctLabel(semesterStat.attendancePct))
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
                "subject" -> {
                    val data = currentReport.subjectReport
                    if (data != null) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val subject = currentReport.subject
                                    Text(
                                        text = "Attendance Report — ${subject?.name ?: "—"} (${subject?.code ?: "—"})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    TableHeaderRow(
                                        listOf("Student ID", "Total Classes", "Present", "Attendance %")
                                    )
                                    if (subjectRows.isEmpty()) {
                                        EmptyRowText()
                                    } else {
                                        subjectRows.forEach { row ->
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Cell(row.studentId)
                                                    Cell("${row.total}")
                                                    Cell("${row.present}")
                                                    Cell(
                                                        text = "${row.pct}%",
                                                        bold = true,
                                                        color = if (row.pct < 75) {
                                                            WebColors.Danger
                                                        } else {
                                                            WebColors.Success
                                                        }
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
                }
                "low" -> {
                    val ov = currentReport.overview
                    if (ov != null) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Low Attendance Report",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    BannerCard(
                                        tone = "warning",
                                        message = "Students listed below have attendance under 75% and may be at risk."
                                    )
                                    TableHeaderRow(
                                        listOf("Student ID", "Name", "Branch", "Attendance %")
                                    )
                                    if (ov.lowAttendanceStudents.isEmpty()) {
                                        EmptyRowText()
                                    } else {
                                        ov.lowAttendanceStudents.forEach { student ->
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Cell(student.studentId ?: "—")
                                                    Cell(student.name ?: "—", bold = true)
                                                    Cell(student.branch ?: "—")
                                                    Cell(
                                                        text = pctLabel(student.percentage),
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
                        }
                    }
                }
            }
        }
    }
}
