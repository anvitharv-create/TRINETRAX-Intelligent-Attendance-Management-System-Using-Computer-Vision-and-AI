package com.trinetrax.attendance.ui.screens.staff

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trinetrax.attendance.data.model.SubjectDto
import com.trinetrax.attendance.data.model.TeacherReportSummaryDto
import com.trinetrax.attendance.ui.components.BannerCard
import com.trinetrax.attendance.ui.components.DateField
import com.trinetrax.attendance.ui.components.DividerThin
import com.trinetrax.attendance.ui.components.EmptyRowText
import com.trinetrax.attendance.ui.components.FilterSelect
import com.trinetrax.attendance.ui.components.SectionCard
import com.trinetrax.attendance.ui.components.SpacerH
import com.trinetrax.attendance.ui.components.StatCard
import com.trinetrax.attendance.ui.components.TableHeaderRow
import kotlinx.coroutines.launch
import com.trinetrax.attendance.ui.components.WebColors
import com.trinetrax.attendance.ui.components.WebOptions
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate

private data class TeacherSubjectOption(
    val id: Int,
    val code: String?,
    val name: String?,
    val semester: String?
)

private data class ReportRowView(
    val studentId: String,
    val name: String,
    val semester: String,
    val section: String,
    val present: Int,
    val absent: Int?,
    val total: Int,
    val pct: Double
)

private data class ReportView(
    val teacher: Boolean,
    val branch: String,
    val semester: String?,
    val subjectCode: String?,
    val rows: List<ReportRowView>,
    val summary: TeacherReportSummaryDto?,
    val startDate: String,
    val endDate: String
)

private fun formatPct(value: Double): String =
    if (value == kotlin.math.floor(value) && !value.isInfinite()) {
        value.toLong().toString()
    } else {
        "%.1f".format(value)
    }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TeacherReportsScreen(vm: SessionViewModel) {
    val user = vm.user.value
    val isTeacher = user?.role == "teacher"
    val myBranch = user?.branch ?: ""
    val context = LocalContext.current

    var branch by remember { mutableStateOf(myBranch.ifEmpty { "ISE" }) }
    var semester by remember { mutableStateOf("5th Semester") }
    var teacherSubjects by remember { mutableStateOf<List<TeacherSubjectOption>>(emptyList()) }
    var subjects by remember { mutableStateOf<List<SubjectDto>>(emptyList()) }
    var subjectId by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ReportView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notifyMenu by remember { mutableStateOf(false) }
    var notifyMode by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(branch, semester, isTeacher) {
        subjectId = ""
        report = null
        if (isTeacher) {
            val dash = vm.repo.teacherDashboard().getOrElse {
                error = vm.repo.humanError(it)
                return@LaunchedEffect
            }
            teacherSubjects = dash.subjects.mapNotNull { s ->
                s.subjectId?.let { TeacherSubjectOption(it, s.code, s.name, s.semester) }
            }
        } else {
            subjects = vm.repo.subjects(branch, semester).getOrElse { emptyList() }
        }
    }

    val subjectOptionLabels: List<String> = if (isTeacher) {
        teacherSubjects.map { "${it.code ?: "—"} - ${it.name ?: "—"}" }
    } else {
        subjects.map { "${it.code ?: "—"} - ${it.name ?: "—"}" }
    }
    val subjectDisplay: String = if (isTeacher) {
        teacherSubjects.find { it.id.toString() == subjectId }
            ?.let { "${it.code ?: "—"} - ${it.name ?: "—"}" } ?: ""
    } else {
        subjects.find { it.id?.toString() == subjectId }
            ?.let { "${it.code ?: "—"} - ${it.name ?: "—"}" } ?: ""
    }

    val onGenerate: () -> Unit = {
        if (startDate.isNotEmpty() && endDate.isNotEmpty() && startDate > endDate) {
            error = "The start date must be on or before the end date."
        } else {
            loading = true
            error = null
            report = null
            val pickedSubjectId = subjectId.toIntOrNull()
            val start = startDate.ifEmpty { null }
            val end = endDate.ifEmpty { null }
            vm.requestFlowScope().launch {
                if (isTeacher) {
                    val r = vm.repo.teacherReport(pickedSubjectId, start, end)
                    r.onSuccess { d ->
                        val subject = teacherSubjects.find { it.id.toString() == subjectId }
                        report = ReportView(
                            teacher = true,
                            branch = d.branch ?: myBranch,
                            semester = subject?.semester,
                            subjectCode = subject?.code,
                            rows = d.rows
                                .map { row ->
                                    ReportRowView(
                                        studentId = row.studentId ?: "—",
                                        name = row.name ?: "—",
                                        semester = row.semester ?: "—",
                                        section = row.section ?: "—",
                                        present = row.present ?: 0,
                                        absent = row.absent ?: 0,
                                        total = row.total ?: 0,
                                        pct = row.pct ?: 0.0
                                    )
                                }
                                .sortedBy { it.studentId },
                            summary = d.summary,
                            startDate = startDate,
                            endDate = endDate
                        )
                    }.onFailure { t -> error = vm.repo.humanError(t) }
                } else {
                    val records = vm.repo.attendance(pickedSubjectId, null, null, null)
                    records.onSuccess { list ->
                        val allStudents = vm.repo.students(branch, semester).getOrElse { emptyList() }
                        val studentMap = allStudents
                            .filter { it.id != null }
                            .associateBy { it.id!! }
                        val byStudent = HashMap<Int, IntArray>()
                        list.forEach { rec ->
                            val sid = rec.studentId ?: return@forEach
                            val entry = byStudent.getOrPut(sid) { intArrayOf(0, 0) }
                            entry[1] += 1
                            if (rec.status == "present") entry[0] += 1
                        }
                        val aggregated = byStudent.map { (sid, counts) ->
                            val s = studentMap[sid]
                            val total = counts[1]
                            val present = counts[0]
                            ReportRowView(
                                studentId = s?.studentId ?: "#$sid",
                                name = s?.name ?: "Student #$sid",
                                semester = s?.semester ?: "—",
                                section = s?.section ?: "—",
                                present = present,
                                absent = null,
                                total = total,
                                pct = if (total > 0) {
                                    Math.round(present.toDouble() / total * 100).toDouble()
                                } else 0.0
                            )
                        }.sortedBy { it.pct }
                        report = ReportView(
                            teacher = false,
                            branch = branch,
                            semester = semester,
                            subjectCode = subjects
                                .find { it.id?.toString() == subjectId }
                                ?.code,
                            rows = aggregated,
                            summary = null,
                            startDate = "",
                            endDate = ""
                        )
                    }.onFailure { t -> error = vm.repo.humanError(t) }
                }
                loading = false
            }
        }
    }

    val onPrint = {
        val rep = report
        if (rep != null) {
            val text = buildString {
                append("Attendance Report — ${rep.branch}")
                rep.semester?.let { append(" / $it") }
                rep.subjectCode?.let { append(" / $it") }
                appendLine()
                appendLine("${rep.rows.size} students · Generated on ${LocalDate.now()}")
                rep.summary?.let { sum ->
                    appendLine(
                        "Students: ${sum.students ?: 0} · Records: ${sum.records ?: 0} · " +
                            "Present: ${sum.present ?: 0} · Absent: ${sum.absent ?: 0} · " +
                            "Attendance %: ${formatPct(sum.pct ?: 0.0)}%"
                    )
                }
                appendLine()
                if (rep.teacher) {
                    appendLine("USN\tName\tSem\tSection\tPresent\tAbsent\tTotal\tAttendance %")
                    rep.rows.forEach { r ->
                        appendLine(
                            "${r.studentId}\t${r.name}\t${r.semester}\t${r.section}\t" +
                                "${r.present}\t${r.absent ?: 0}\t${r.total}\t${formatPct(r.pct)}%"
                        )
                    }
                } else {
                    appendLine("Student ID\tName\tSem\tSection\tPresent\tTotal\tAttendance %")
                    rep.rows.forEach { r ->
                        appendLine(
                            "${r.studentId}\t${r.name}\t${r.semester}\t${r.section}\t" +
                                "${r.present}\t${r.total}\t${formatPct(r.pct)}%"
                        )
                    }
                }
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "Print Report"))
        }
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                "Reports",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (isTeacher) {
            item {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth()) {
                        FilterSelect(
                            label = "Subject",
                            options = subjectOptionLabels,
                            value = subjectDisplay,
                            onSelect = { label ->
                                subjectId = teacherSubjects
                                    .find { "${it.code ?: "—"} - ${it.name ?: "—"}" == label }
                                    ?.id
                                    ?.toString() ?: ""
                            },
                            placeholder = "All my subjects"
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DateField(
                        label = "From",
                        value = startDate,
                        onDateChange = { startDate = it },
                        modifier = Modifier.weight(1f)
                    )
                    DateField(
                        label = "To",
                        value = endDate,
                        onDateChange = { endDate = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Text(
                    "Reports cover only the subjects assigned to you. Leave the dates empty " +
                        "to include every recorded session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText
                )
            }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.weight(1f)) {
                        FilterSelect(
                            label = "Branch",
                            options = WebOptions.BRANCHES,
                            value = branch,
                            onSelect = { branch = it }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        FilterSelect(
                            label = "Semester",
                            options = WebOptions.SEMESTERS,
                            value = semester,
                            onSelect = { semester = it }
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth()) {
                        FilterSelect(
                            label = "Subject (optional)",
                            options = subjectOptionLabels,
                            value = subjectDisplay,
                            onSelect = { label ->
                                subjectId = subjects
                                    .find { "${it.code ?: "—"} - ${it.name ?: "—"}" == label }
                                    ?.id
                                    ?.toString() ?: ""
                            },
                            placeholder = "All Subjects"
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportsWebButton(
                    text = if (loading) "Generating..." else "Generate Report",
                    container = WebColors.Primary,
                    enabled = !loading,
                    onClick = onGenerate
                )
                Box {
                    ReportsWebButton(
                        text = "Notification ▾",
                        container = WebColors.Info,
                        onClick = { notifyMenu = !notifyMenu }
                    )
                    if (notifyMenu) {
                        androidx.compose.material3.DropdownMenu(
                            expanded = true,
                            onDismissRequest = { notifyMenu = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Select Students") },
                                onClick = {
                                    notifyMenu = false
                                    notifyMode = "select"
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Send All") },
                                onClick = {
                                    notifyMenu = false
                                    notifyMode = "all"
                                }
                            )
                        }
                    }
                }
                if (report != null) {
                    ReportsWebButton(text = "Print", container = Color(0xFF95A5A6), onClick = onPrint)
                }
            }
        }

        error?.let { e ->
            item { BannerCard("danger", e) }
        }

        report?.let { rep ->
            item {
                val title = buildString {
                    append("Attendance Report — ${rep.branch}")
                    rep.semester?.let { append(" / $it") }
                    rep.subjectCode?.let { append(" / $it") }
                }
                val period: String? = if (rep.teacher) {
                    if (rep.startDate.isNotEmpty() || rep.endDate.isNotEmpty()) {
                        "Period: ${rep.startDate.ifEmpty { "beginning" }} to " +
                            rep.endDate.ifEmpty { "today" }
                    } else {
                        "Period: all dates"
                    }
                } else null
                val subtitle =
                    "${rep.rows.size} students${period?.let { " · $it" } ?: ""}" +
                        " · Generated on ${LocalDate.now()}"

                SectionCard(title) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                    val summary = rep.summary
                    if (summary != null) {
                        SpacerH(10)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatCard(
                                "Students",
                                "${summary.students ?: 0}",
                                Modifier.width(140.dp),
                                accent = WebColors.Accent
                            )
                            StatCard(
                                "Records",
                                "${summary.records ?: 0}",
                                Modifier.width(140.dp),
                                accent = WebColors.Primary
                            )
                            StatCard(
                                "Present",
                                "${summary.present ?: 0}",
                                Modifier.width(140.dp),
                                accent = WebColors.Success
                            )
                            StatCard(
                                "Absent",
                                "${summary.absent ?: 0}",
                                Modifier.width(140.dp),
                                accent = WebColors.Danger
                            )
                            StatCard(
                                "Attendance %",
                                "${formatPct(summary.pct ?: 0.0)}%",
                                Modifier.width(140.dp),
                                accent = if ((summary.pct ?: 0.0) >= 75.0) {
                                    WebColors.Success
                                } else {
                                    WebColors.Danger
                                }
                            )
                        }
                    }
                    SpacerH(10)
                    TableHeaderRow(
                        if (rep.teacher) {
                            listOf("USN", "Name", "Sem", "Section", "Present", "Absent", "Total", "Attendance %")
                        } else {
                            listOf("Student ID", "Name", "Sem", "Section", "Present", "Total", "Attendance %")
                        },
                        weights = if (rep.teacher) {
                            listOf(1.2f, 1.3f, 0.9f, 0.9f, 0.7f, 0.7f, 0.7f, 1.0f)
                        } else {
                            listOf(1.2f, 1.3f, 0.9f, 0.9f, 0.7f, 0.7f, 1.0f)
                        }
                    )
                    if (rep.rows.isEmpty()) {
                        EmptyRowText(
                            if (rep.teacher) {
                                "No attendance records for the selected period"
                            } else {
                                "No data"
                            }
                        )
                    } else {
                        rep.rows.forEach { row ->
                            ReportTableRow(row = row, teacher = rep.teacher)
                            DividerThin()
                        }
                    }
                }
            }
        }
    }

    notifyMode?.let { mode ->
        ParentNotifySheet(
            vm = vm,
            mode = mode,
            startDate = startDate.ifBlank { null },
            endDate = endDate.ifBlank { null },
            onClose = { notifyMode = null }
        )
    }
    }
}

@Composable
private fun ReportTableRow(row: ReportRowView, teacher: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        ReportTableCell(row.studentId, Modifier.weight(1.2f))
        ReportTableCell(row.name, Modifier.weight(1.3f))
        ReportTableCell(row.semester, Modifier.weight(0.9f))
        ReportTableCell(row.section, Modifier.weight(0.9f))
        ReportTableCell("${row.present}", Modifier.weight(0.7f))
        if (teacher) {
            ReportTableCell("${row.absent ?: 0}", Modifier.weight(0.7f))
        }
        ReportTableCell("${row.total}", Modifier.weight(0.7f))
        Text(
            "${formatPct(row.pct)}%",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = if (row.pct >= 75.0) WebColors.Success else WebColors.Danger,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ReportTableCell(text: String, modifier: Modifier) {
    Text(
        text,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/* ---- Parent report delivery — web ParentReportNotify.jsx ---- */

private data class NotifyOutcomeMeta(val label: String, val color: Color)

private fun outcomeMeta(outcome: String?): NotifyOutcomeMeta = when (outcome) {
    "sent" -> NotifyOutcomeMeta("Sent", WebColors.Success)
    "failed" -> NotifyOutcomeMeta("Failed", WebColors.Danger)
    "skipped" -> NotifyOutcomeMeta("Skipped", Color(0xFF95A5A6))
    "unconfigured" -> NotifyOutcomeMeta("Not configured", WebColors.Warning)
    "duplicate" -> NotifyOutcomeMeta("Duplicate", WebColors.Info)
    else -> NotifyOutcomeMeta(outcome ?: "—", Color(0xFF64748B))
}

private fun blockReasonText(reason: String?): String = when (reason) {
    "no_parent_contact" -> "No parent contact on file"
    "no_valid_address" -> "No email address on file"
    "unverified_contact" -> "Parent contact not verified"
    "invalid_number" -> "Invalid phone number"
    "invalid_address" -> "Invalid email address"
    "undeliverable_domain" -> "Email not registered (domain has no mail server)"
    null -> ""
    else -> reason.replace("_", " ")
}

private fun channelLabel(channel: String) =
    if (channel == "email") "Email" else "Parent's WhatsApp"

@androidx.compose.material3.ExperimentalMaterial3Api
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ParentNotifySheet(
    vm: com.trinetrax.attendance.viewmodel.SessionViewModel,
    mode: String,
    startDate: String?,
    endDate: String?,
    onClose: () -> Unit
) {
    var channel by remember { mutableStateOf("whatsapp") }
    var phase by remember { mutableStateOf("loading") } // loading|choose|confirm|sending|done
    var data by remember { mutableStateOf<com.trinetrax.attendance.data.model.NotifyEligibleDto?>(null) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    var batch by remember { mutableStateOf<com.trinetrax.attendance.data.model.NotifyBatchDto?>(null) }

    LaunchedEffect(channel) {
        phase = "loading"
        errMsg = null
        vm.repo.notifyEligible(channel)
            .onSuccess {
                data = it
                selected = emptySet()
                phase = if (mode == "all") "confirm" else "choose"
            }
            .onFailure {
                errMsg = vm.repo.humanError(it)
                phase = if (mode == "all") "confirm" else "choose"
            }
    }

    // Poll the batch exactly like the web modal (700 ms) until it reports done.
    LaunchedEffect(batch?.batchId) {
        val id = batch?.batchId ?: return@LaunchedEffect
        if (batch?.status == "done") return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(700)
            val next = vm.repo.notifyBatch(id)
            next.onSuccess {
                batch = it
                if (it.status == "done") return@onSuccess
            }.onFailure {
                errMsg = vm.repo.humanError(it)
                phase = "done"
                return@LaunchedEffect
            }
            if (batch?.status == "done") return@LaunchedEffect
        }
    }

    val counts = data?.counts
    val channelStatus = data?.channels?.get(channel)
    val students = data?.students ?: emptyList()
    val summary = batch?.summary ?: emptyMap()
    val total = batch?.total ?: 0
    val processed = batch?.processed ?: 0
    val pct = if (total > 0) (processed * 100 / total) else 0

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Color.White
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        if (mode == "all") "Send report to all parents" else "Select students",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    ReportsWebButton(text = "Close", container = Color(0xFF95A5A6), onClick = onClose)
                }
            }

            errMsg?.let { item { BannerCard("danger", it) } }

            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "Send via",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF475569)
                    )
                    Spacer(Modifier.width(8.dp))
                    listOf("whatsapp", "email").forEach { ch ->
                        Box(Modifier.padding(end = 6.dp)) {
                            ReportsWebButton(
                                text = channelLabel(ch),
                                container = if (channel == ch) WebColors.Primary else Color(0xFFE2E8F0),
                                onClick = {
                                    if (batch == null) {
                                        batch = null
                                        channel = ch
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (channelStatus != null && channelStatus.configured != true) {
                item {
                    Text(
                        channelLabel(channel) + " not configured",
                        fontSize = 12.sp,
                        color = WebColors.Warning,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (channelStatus != null && channelStatus.configured != true) {
                item {
                    BannerCard(
                        "warning",
                        channelLabel(channel) + " delivery is not configured on this server. " +
                            "Nothing will actually be delivered until an operator sets " +
                            (channelStatus.missing ?: emptyList()).joinToString(", ") +
                            ". Every attempt below is reported truthfully as \u201cNot configured\u201d " +
                            "\u2014 no message is ever claimed as sent."
                    )
                }
            }

            if (phase == "loading") {
                item { Text("Loading your students\u2026", color = Color(0xFF666666)) }
            }

            if ((phase == "choose" || phase == "confirm") && data != null) {
                item {
                    val invalidCount = counts?.invalid ?: 0
                    Text(
                        "${counts?.total ?: 0} student(s) in your classes · " +
                            "${counts?.deliverable ?: 0} with a verified " +
                            (if (channel == "email") "email address" else "parent number") +
                            " · ${counts?.noContact ?: 0} with nothing on file · " +
                            "${counts?.unverified ?: 0} unverified" +
                            (if (invalidCount > 0) " · $invalidCount not registered" else ""),
                        fontSize = 13.sp,
                        color = Color(0xFF555555)
                    )
                }

                if (mode == "all") {
                    item {
                        BannerCard(
                            "info",
                            "This will attempt to send each student's own attendance PDF over " +
                                channelLabel(channel) + " \u2014 " + "${counts?.deliverable ?: 0} " +
                                "recipient(s) can actually receive it. The rest will be " +
                                "reported as skipped."
                        )
                    }
                } else {
                    items(students.size) { index ->
                        val s = students[index]
                        val checked = s.studentId != null && selected.contains(s.studentId)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    val id = s.studentId ?: return@Checkbox
                                    selected = if (on) selected + id else selected - id
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${s.usn ?: "—"} \u00b7 ${s.name ?: "—"}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${s.branch ?: "—"} / ${s.semester ?: "—"} / ${s.section ?: "—"}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF64748B),
                                    maxLines = 1
                                )
                                if (s.deliverable != true) {
                                    Text(
                                        blockReasonText(s.blockReason),
                                        fontSize = 11.sp,
                                        color = Color(0xFF95A5A6),
                                        maxLines = 2
                                    )
                                }
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(
                                    s.recipientMasked ?: "\u2014",
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                                if (s.deliverable == true) {
                                    com.trinetrax.attendance.ui.components.StatusBadge("present")
                                }
                            }
                        }
                        DividerThin()
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportsWebButton(text = "Refresh", container = Color(0xFF95A5A6)) {
                            phase = "loading"
                            vm.requestFlowScope().launch {
                                vm.repo.notifyEligible(channel).onSuccess {
                                    data = it
                                    selected = emptySet()
                                    phase = if (mode == "all") "confirm" else "choose"
                                }
                            }
                        }
                        if (mode == "select") {
                            ReportsWebButton(
                                text = "Send to ${selected.size} selected via ${channelLabel(channel)}",
                                container = WebColors.Primary,
                                enabled = selected.isNotEmpty()
                            ) {
                                phase = "sending"
                                vm.requestFlowScope().launch {
                                    vm.repo.notifyStart(
                                        com.trinetrax.attendance.data.model.NotifyStartRequest(
                                            studentIds = selected.toList(),
                                            channel = channel,
                                            startDate = startDate,
                                            endDate = endDate
                                        )
                                    ).onSuccess { batch = it; phase = "sending" }
                                        .onFailure {
                                            errMsg = vm.repo.humanError(it)
                                            phase = if (mode == "all") "confirm" else "choose"
                                        }
                                }
                            }
                        } else {
                            ReportsWebButton(
                                text = "Send to all ${counts?.total ?: 0} via ${channelLabel(channel)}",
                                container = WebColors.Primary
                            ) {
                                phase = "sending"
                                vm.requestFlowScope().launch {
                                    vm.repo.notifyStart(
                                        com.trinetrax.attendance.data.model.NotifyStartRequest(
                                            studentIds = emptyList(),
                                            channel = channel,
                                            startDate = startDate,
                                            endDate = endDate
                                        )
                                    ).onSuccess { batch = it; phase = "sending" }
                                        .onFailure {
                                            errMsg = vm.repo.humanError(it)
                                            phase = "confirm"
                                        }
                                }
                            }
                        }
                    }
                }
            }

            if (phase == "sending") {
                val b = batch
                item {
                    Text(
                        "Sending via ${channelLabel(b?.channel ?: channel)}\u2026 " +
                            "${b?.processed ?: 0} of ${b?.total ?: 0}",
                        fontSize = 14.sp
                    )
                }
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(pct / 100f)
                                .height(10.dp)
                                .background(WebColors.Primary, RoundedCornerShape(8.dp))
                        )
                    }
                }
            }

            val doneBatch = batch
            if (phase == "done" && doneBatch != null) {
                item {
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("sent", "failed", "skipped", "unconfigured", "duplicate").forEach { key ->
                            val meta = outcomeMeta(key)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    Color(0xFFDEE2E6)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "${summary[key] ?: 0}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = meta.color,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(meta.label, fontSize = 11.sp, color = Color(0xFF475569))
                                }
                            }
                        }
                    }
                }
                doneBatch.error?.let { item { BannerCard("danger", it) } }
                items(doneBatch.results ?: emptyList()) { r ->
                    val meta = outcomeMeta(r.outcome)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${r.usn ?: "—"} \u00b7 ${r.name ?: "—"}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${r.recipientMasked ?: "\u2014"} " +
                                    (when (r.recipientVia) {
                                        "parent" -> "\u00b7 Parent"
                                        "student" -> "\u00b7 Student"
                                        else -> ""
                                    }),
                                fontSize = 11.sp,
                                color = Color(0xFF64748B),
                                maxLines = 1
                            )
                            Text(
                                r.detail ?: "",
                                fontSize = 11.sp,
                                color = Color(0xFF666666),
                                maxLines = 2
                            )
                        }
                        Text(
                            meta.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = meta.color
                        )
                    }
                    DividerThin()
                }
                if ((doneBatch.results ?: emptyList()).isEmpty()) {
                    item { EmptyRowText("Nothing was processed.") }
                }
            }
        }
    }
}

@Composable
private fun ReportsWebButton(
    text: String,
    container: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = Color.White
        )
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}
