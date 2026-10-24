package com.trinetrax.attendance.ui.screens.student

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trinetrax.attendance.data.model.StudentReportDto
import com.trinetrax.attendance.ui.components.BannerCard
import com.trinetrax.attendance.ui.components.DateField
import com.trinetrax.attendance.ui.components.DividerThin
import com.trinetrax.attendance.ui.components.EmptyRowText
import com.trinetrax.attendance.ui.components.KeyValueRow
import com.trinetrax.attendance.ui.components.SectionCard
import com.trinetrax.attendance.ui.components.SpacerH
import com.trinetrax.attendance.ui.components.StatCard
import com.trinetrax.attendance.ui.components.StatusBadge
import com.trinetrax.attendance.ui.components.TableHeaderRow
import com.trinetrax.attendance.ui.components.WebColors
import com.trinetrax.attendance.ui.theme.DangerRed
import com.trinetrax.attendance.ui.theme.SuccessGreen
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun num(value: Double?): String = when {
    value == null -> "—"
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> value.toString()
}

@Composable
private fun ReportButton(
    text: String,
    background: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = background,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudentReportScreen(vm: SessionViewModel) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedToDownloads by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<StudentReportDto?>(null) }
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }

    fun generate(start: String, end: String) {
        loading = true
        error = null
        savedToDownloads = false
        vm.requestFlowScope().launch {
            vm.repo.studentReport(start, end).onSuccess {
                report = it
                startDate = start
                endDate = end
            }.onFailure {
                report = null
                error = vm.repo.humanError(it)
            }
            loading = false
        }
    }

    fun downloadPdf() {
        if (report == null) return
        downloading = true
        error = null
        savedToDownloads = false
        val start = startDate
        val end = endDate
        val fileName = "attendance_report_student.pdf"
        vm.requestFlowScope().launch {
            val outcome = withContext(Dispatchers.IO) {
                vm.repo.studentReportPdf(start, end).mapCatching { body ->
                    val bytes = body.bytes()
                    val cacheFile = File(context.cacheDir, fileName)
                    cacheFile.writeBytes(bytes)
                    val request = DownloadManager.Request(Uri.fromFile(cacheFile))
                        .setTitle(fileName)
                        .setDescription("Attendance report")
                        .setMimeType("application/pdf")
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
            downloading = false
            outcome.onSuccess { savedToDownloads = true }
                .onFailure { error = vm.repo.humanError(it) }
        }
    }

    val currentReport = report
    val currentError = error
    val showSaved = savedToDownloads
    val showEmpty = currentReport == null && !loading

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    "Attendance Report",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                SpacerH(4)
                Text(
                    "Your complete profile and attendance record in one report — generated for you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF64748B)
                )
            }
        }
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReportButton(
                    text = if (loading) "Generating..." else "Generate Report",
                    background = WebColors.Primary,
                    enabled = !loading,
                    onClick = { generate("", "") }
                )
                if (currentReport != null) {
                    ReportButton(
                        text = if (downloading) "Preparing PDF..." else "Download PDF",
                        background = WebColors.Success,
                        enabled = !downloading,
                        onClick = { downloadPdf() }
                    )
                }
            }
        }
        currentError?.let { message ->
            item { BannerCard("danger", message) }
        }
        if (showSaved) {
            item { BannerCard("success", "Report saved to Downloads") }
        }
        if (showEmpty) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = buildAnnotatedString {
                            append("Click ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("Generate Report")
                            }
                            append(" to view your profile, attendance summary and history.")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        textAlign = TextAlign.Center,
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        currentReport?.let { r ->
            item {
                SectionCard("Student Profile") {
                    val profileRows = listOf(
                        "Name" to "—",
                        "USN / Student ID" to "—",
                        "Roll number" to "—",
                        "Branch" to "—",
                        "Semester" to "—",
                        "Section" to "—",
                        "Email" to "—",
                        "Login ID" to "—"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        profileRows.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                pair.forEach { (label, value) ->
                                    Box(Modifier.weight(1f)) {
                                        KeyValueRow(label, value)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                val summary = r.summary
                val eligible = summary?.eligible
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCard(
                            label = "Attendance",
                            value = summary?.pct?.let { "${num(it)}%" } ?: "—",
                            modifier = Modifier.weight(1f),
                            accent = when (eligible) {
                                true -> SuccessGreen
                                false -> DangerRed
                                else -> WebColors.Primary
                            },
                            caption = "Minimum ${num(r.minAttendancePct)}%"
                        )
                        StatCard(
                            label = "Present",
                            value = summary?.present?.toString() ?: "—",
                            modifier = Modifier.weight(1f),
                            accent = SuccessGreen
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCard(
                            label = "Absent",
                            value = summary?.absent?.toString() ?: "—",
                            modifier = Modifier.weight(1f),
                            accent = DangerRed
                        )
                        StatCard(
                            label = "Total classes marked",
                            value = summary?.totalMarked?.toString() ?: "—",
                            modifier = Modifier.weight(1f),
                            accent = WebColors.Info
                        )
                    }
                }
            }
            item {
                val summary = r.summary
                val eligible = summary?.eligible
                BannerCard(
                    tone = when (eligible) {
                        true -> "success"
                        false -> "warning"
                        else -> "info"
                    },
                    message = when (eligible) {
                        true -> "Eligible — your attendance of ${num(summary?.pct)}% meets the " +
                            "minimum ${num(r.minAttendancePct)}% requirement."
                        false -> buildString {
                            append(
                                "Not eligible — your attendance of ${num(summary?.pct)}% is " +
                                    "${num(summary?.shortagePct)} percentage points below the " +
                                    "minimum ${num(r.minAttendancePct)}%."
                            )
                            val classes = summary?.classesToAttend
                            if (classes != null) {
                                append(
                                    " Attend the next $classes class(es) — all present — " +
                                        "to reach the requirement."
                                )
                            }
                        }
                        else -> "No attendance records yet — eligibility cannot be calculated."
                    }
                )
            }
            item {
                SectionCard("Attendance History") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Date range (optional)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF555555)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DateField(
                                label = "From",
                                value = startDate,
                                onDateChange = { picked ->
                                    if (picked.isEmpty() || endDate.isEmpty() || picked <= endDate) {
                                        startDate = picked
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            DateField(
                                label = "To",
                                value = endDate,
                                onDateChange = { picked ->
                                    if (picked.isEmpty() || startDate.isEmpty() || picked >= startDate) {
                                        endDate = picked
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReportButton(
                                text = "Update",
                                background = WebColors.Accent,
                                enabled = !loading,
                                onClick = { generate(startDate, endDate) }
                            )
                            if (startDate.isNotEmpty() || endDate.isNotEmpty()) {
                                ReportButton(
                                    text = "Clear",
                                    background = Color(0xFF94A3B8),
                                    enabled = true,
                                    onClick = { generate("", "") }
                                )
                            }
                        }
                        TableHeaderRow(columns = listOf("Date", "Subject", "Attendance"))
                        val records = r.records
                        if (records.isEmpty()) {
                            EmptyRowText("No attendance records in this period.")
                        } else {
                            records.forEachIndexed { index, record ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        record.date ?: "—",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = buildAnnotatedString {
                                            append(record.subject ?: "—")
                                            withStyle(SpanStyle(color = Color(0xFF94A3B8))) {
                                                append(" (${record.subjectCode ?: "—"})")
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Box(Modifier.weight(1f)) {
                                        StatusBadge(record.status)
                                    }
                                }
                                if (index != records.lastIndex) DividerThin()
                            }
                        }
                        Text(
                            "Generated on ${r.generatedAt ?: "—"} · Minimum attendance required: " +
                                "${num(r.minAttendancePct)}%",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }
    }
}
