package com.trinetrax.attendance.ui.screens.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trinetrax.attendance.data.model.AttendanceRecordDto
import com.trinetrax.attendance.data.model.StudentDto
import com.trinetrax.attendance.data.model.SubjectDto
import com.trinetrax.attendance.ui.components.BannerCard
import com.trinetrax.attendance.ui.components.EmptyState
import com.trinetrax.attendance.ui.components.ErrorState
import com.trinetrax.attendance.ui.components.LoadingView
import com.trinetrax.attendance.ui.components.SectionCard
import com.trinetrax.attendance.ui.components.StatCard
import com.trinetrax.attendance.ui.components.StatusPill
import com.trinetrax.attendance.ui.theme.DangerRed
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.ui.theme.SuccessGreen
import com.trinetrax.attendance.ui.theme.WarningAmber
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.launch

private fun statusColors(status: String?): Pair<String, androidx.compose.ui.graphics.Color> = when (status) {
    "present" -> "Present" to SuccessGreen
    "absent" -> "Absent" to DangerRed
    "unknown" -> "Unknown" to WarningAmber
    else -> (status ?: "—") to MutedText
}

/* =================== STUDENT: My attendance (web StudentAttendancePage) == */

private fun lowBannerNames(items: List<com.trinetrax.attendance.ui.screens.attendance.SubjectAttendanceUi>): String =
    when (items.size) {
        0 -> ""
        1 -> items[0].name
        else -> items.dropLast(1).joinToString(", ") { it.name } + " and " + items.last().name
    }

data class SubjectAttendanceUi(val name: String, val code: String, val pct: Double, val present: Int, val total: Int)

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun StudentAttendanceScreen(vm: SessionViewModel) {
    val user = vm.user.value

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<AttendanceRecordDto>>(emptyList()) }
    var subjects by remember { mutableStateOf<List<SubjectDto>>(emptyList()) }
    var subjectFilter by remember { mutableStateOf<Int?>(null) }

    androidx.compose.runtime.LaunchedEffect(user?.id) {
        loading = true
        error = null
        val me = user ?: run { loading = false; return@LaunchedEffect }
        val students = vm.repo.students(me.branch, me.semester, me.section)
            .getOrElse { error = vm.repo.humanError(it); loading = false; return@LaunchedEffect }
        val mine = students.find { it.studentId == me.studentId }
        if (mine?.id == null) {
            error = "Your student record was not found."
            loading = false
            return@LaunchedEffect
        }
        val subs = vm.repo.subjects(me.branch, me.semester)
            .getOrElse { error = vm.repo.humanError(it); loading = false; return@LaunchedEffect }
        val recs = vm.repo.studentAttendance(mine.id)
            .getOrElse { error = vm.repo.humanError(it); loading = false; return@LaunchedEffect }
        subjects = subs.filter { s ->
            s.branch == mine.branch && s.semester == mine.semester && s.id != null
        }
        records = recs.sortedWith(
            compareByDescending<AttendanceRecordDto> { it.date ?: "" }.thenByDescending { it.id ?: 0 }
        )
        loading = false
    }

    val selectedSubject = subjects.find { it.id == subjectFilter }
    val filtered = if (subjectFilter == null) records else records.filter { it.subjectId == subjectFilter }
    val present = filtered.count { it.status == "present" }
    val absent = filtered.count { it.status == "absent" }
    val pctNum = if (filtered.isEmpty()) 0.0 else present * 100.0 / filtered.size
    val atRisk = filtered.isNotEmpty() && pctNum < 75

    val subjectStats = remember(subjects, records) {
        subjects.map { sub ->
            val subRecs = records.filter { r -> r.subjectId == sub.id }
            val p = subRecs.count { it.status == "present" }
            SubjectAttendanceUi(
                name = sub.name ?: "—",
                code = sub.code ?: "—",
                pct = if (subRecs.isNotEmpty()) p * 100.0 / subRecs.size else 0.0,
                present = p,
                total = subRecs.size
            )
        }.sortedBy { it.pct }
    }
    val lowSubjects = subjectStats.filter { it.total > 0 && it.pct > 0.0 && it.pct < 75.0 }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    "My Attendance",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Attendance records for ${user?.fullName ?: "Student"} " +
                        "(Student ID: ${user?.studentId ?: "—"})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubjectFilterDropdown(
                    subjects = subjects,
                    selectedId = subjectFilter,
                    onSelect = { subjectFilter = it },
                    label = selectedSubject?.let { subjectLabel(it) } ?: "All subjects"
                )
                Text(
                    if (selectedSubject != null) {
                        "Showing attendance for ${selectedSubject.name} (${selectedSubject.code})"
                    } else {
                        "Showing attendance for all subjects"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666)
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    "Overall Attendance",
                    if (filtered.isEmpty()) "—" else "%.1f%%".format(pctNum),
                    Modifier.width(180.dp),
                    accent = when {
                        filtered.isEmpty() -> Color(0xFF888888)
                        atRisk -> Color(0xFFF39C12)
                        else -> Color(0xFF27AE60)
                    },
                    caption = selectedSubject?.code ?: "All subjects"
                )
                StatCard("Classes Present", "$present", Modifier.width(150.dp),
                    accent = Color(0xFF27AE60))
                StatCard("Classes Absent", "$absent", Modifier.width(150.dp),
                    accent = Color(0xFFE74C3C))
                StatCard("Total Classes", "${filtered.size}", Modifier.width(140.dp),
                    accent = Color(0xFF0277BD))
            }
        }

        if (lowSubjects.isNotEmpty()) {
            item {
                BannerCard(
                    "warning",
                    "⚠️ Your attendance is below 75% in ${lowBannerNames(lowSubjects)}. " +
                        "Please attend classes regularly to maintain eligibility."
                )
            }
        }

        item {
            Text(
                "Subject-wise Attendance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (subjects.isEmpty() && !loading) {
            item {
                Text("No subject data yet.", color = Color(0xFF888888),
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(subjectStats, key = { "sa${it.code}" }) { s ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDEE2E6)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(s.name, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "%.1f%% %s".format(s.pct, if (s.pct > 0 && s.pct < 75) "⚠️" else ""),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    s.pct >= 75 -> Color(0xFF27AE60)
                                    s.pct > 0 -> Color(0xFFF39C12)
                                    else -> Color(0xFF888888)
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .background(Color(0xFFECF0F1), RoundedCornerShape(4.dp))
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth(
                                        fraction = s.pct.coerceIn(0.0, 100.0).toFloat() / 100f
                                    )
                                    .height(8.dp),
                                color = when {
                                    s.pct >= 75 -> Color(0xFF27AE60)
                                    s.pct > 0 -> Color(0xFFF39C12)
                                    else -> Color(0xFFD0D0D0)
                                }
                            ) {}
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${s.present}/${s.total} classes attended",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF888888)
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Attendance History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        when {
            loading -> item { LoadingView("Loading...") }
            error != null -> item {
                Text(error!!, color = Color(0xFFC53030),
                    style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                item {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            com.trinetrax.attendance.ui.components.TableHeaderRow(
                                listOf("Date", "Subject", "Attendance", "Confidence"),
                                weights = listOf(1.1f, 2.3f, 1.2f, 1.1f)
                            )
                            if (filtered.isEmpty()) {
                                com.trinetrax.attendance.ui.components.EmptyRowText(
                                    if (subjectFilter != null)
                                        "No attendance records for this subject yet."
                                    else
                                        "No attendance records yet."
                                )
                            }
                            filtered.forEach { r ->
                                val subj = subjects.find { it.id == r.subjectId }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        r.date ?: "—",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        modifier = Modifier.weight(1.1f)
                                    )
                                    Text(
                                        subj?.let { "${it.name} (${it.code})" }
                                            ?: "Subject #${r.subjectId}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(2.3f),
                                        maxLines = 2
                                    )
                                    Box(Modifier.weight(1.2f)) {
                                        com.trinetrax.attendance.ui.components.StatusBadge(r.status)
                                    }
                                    Text(
                                        r.confidence?.let { "%.0f%%".format(it * 100) } ?: "—",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MutedText,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1.1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun SubjectFilterDropdown(
    subjects: List<SubjectDto>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    label: String
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Subject") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All subjects") },
                onClick = { onSelect(null); expanded = false }
            )
            subjects.forEach { subject ->
                DropdownMenuItem(
                    text = { Text(subjectLabel(subject), maxLines = 1) },
                    onClick = { onSelect(subject.id); expanded = false }
                )
            }
        }
    }
}

/* =================== TEACHER/HOD: Previous Attendance (web parity) ======= */

private data class HistoryRow(
    val studentPk: Int,
    val usn: String,
    val name: String,
    val present: Int,
    val total: Int
) {
    val pct: Int get() = if (total > 0) (present * 100 / total) else 0
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun AttendanceHistoryScreen(vm: SessionViewModel) {
    val user = vm.user.value
    val isTeacher = user?.role == "teacher"

    var semester by remember { mutableStateOf("") }
    var subjects by remember { mutableStateOf<List<SubjectDto>>(emptyList()) }
    var subjectId by remember { mutableStateOf<Int?>(null) }
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var teacherId by remember { mutableStateOf<Int?>(null) }
    var records by remember { mutableStateOf<List<AttendanceRecordDto>>(emptyList()) }
    var studentMap by remember { mutableStateOf<Map<Int, StudentDto>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var subjectsLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (isTeacher && teacherId == null) {
            teacherId = vm.repo.teacherDashboard().getOrNull()?.teacherId
        }
    }

    androidx.compose.runtime.LaunchedEffect(semester, teacherId) {
        if (semester.isEmpty()) {
            subjects = emptyList()
            subjectId = null
            return@LaunchedEffect
        }
        subjectId = null
        subjects = emptyList()
        subjectsLoading = true
        val list = vm.repo.subjects(
            branch = user?.branch ?: "",
            semester = semester,
            teacherId = if (isTeacher) teacherId else null
        ).getOrNull() ?: emptyList()
        subjects = list
        subjectsLoading = false
    }

    val selectedSubject = subjects.find { it.id == subjectId }

    val rows = remember(records, studentMap) {
        records.groupBy { it.studentId ?: 0 }.map { (pk, recs) ->
            HistoryRow(
                studentPk = pk,
                usn = studentMap[pk]?.studentId ?: "#$pk",
                name = studentMap[pk]?.name ?: "Student #$pk",
                present = recs.count { it.status == "present" },
                total = recs.size
            )
        }.sortedBy { it.usn }
    }

    val period = if (startDate.isNotEmpty() || endDate.isNotEmpty()) {
        "Period: ${startDate.ifEmpty { "beginning" }} to ${endDate.ifEmpty { "today" }}"
    } else {
        "All dates"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Attendance History",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                com.trinetrax.attendance.ui.components.FilterSelect(
                    label = "Semester",
                    options = com.trinetrax.attendance.ui.components.WebOptions.SEMESTERS,
                    value = semester,
                    placeholder = "Select semester",
                    onSelect = { semester = it }
                )
                com.trinetrax.attendance.ui.components.FilterSelect(
                    label = if (isTeacher) "Subject (yours)" else "Subject",
                    options = subjects.map { "${it.code ?: ""} - ${it.name ?: ""}" },
                    value = selectedSubject?.let { "${it.code ?: ""} - ${it.name ?: ""}" } ?: "",
                    placeholder = if (semester.isEmpty()) "Select semester first" else "Select subject",
                    onSelect = { label ->
                        subjectId = subjects
                            .firstOrNull { "${it.code ?: ""} - ${it.name ?: ""}" == label }
                            ?.id
                    }
                )
                if (subjectsLoading) {
                    Text("Loading subjects...", style = MaterialTheme.typography.bodySmall, color = MutedText)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        com.trinetrax.attendance.ui.components.DateField(
                            label = "From",
                            value = startDate,
                            onDateChange = { startDate = it }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        com.trinetrax.attendance.ui.components.DateField(
                            label = "To",
                            value = endDate,
                            onDateChange = { endDate = it }
                        )
                    }
                    OutlinedButton(
                        enabled = subjectId != null && !loading,
                        onClick = {
                            if (subjectId == null) {
                                error = "Select a subject first."
                                return@OutlinedButton
                            }
                            if (startDate.isNotEmpty() && endDate.isNotEmpty() && startDate > endDate) {
                                error = "The start date must be on or before the end date."
                                return@OutlinedButton
                            }
                            loading = true
                            error = null
                            loaded = false
                            val sid = subjectId ?: return@OutlinedButton
                            val s = startDate.ifEmpty { null }
                            val e = endDate.ifEmpty { null }
                            vm.requestFlowScope().launch {
                                vm.repo.attendance(subjectId = sid, startDate = s, endDate = e)
                                    .onSuccess { list ->
                                        records = list
                                        loaded = true
                                        if (list.isNotEmpty()) {
                                            val all = vm.repo.students().getOrNull() ?: emptyList()
                                            studentMap = all.filter { it.id != null }
                                                .associate { it.id!! to it }
                                        } else {
                                            studentMap = emptyMap()
                                        }
                                        loading = false
                                    }.onFailure {
                                        error = vm.repo.humanError(it)
                                        loading = false
                                    }
                            }
                        }
                    ) {
                        Text(if (loading) "Loading..." else "Load")
                    }
                }
            }
        }

        error?.let { msg ->
            item {
                Text(msg, color = Color(0xFFC53030), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (loaded && selectedSubject != null && records.isNotEmpty()) {
            item {
                Column {
                    Text(
                        "${selectedSubject.code ?: "—"} — ${selectedSubject.name ?: "—"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "$period · ${rows.size} students",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                }
            }
            item {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                            com.trinetrax.attendance.ui.components.TableHeaderRow(
                                listOf("USN", "Name", "Present", "Total", "Attendance %"),
                                weights = listOf(1.3f, 2.0f, 0.8f, 0.8f, 1.7f)
                            )
                            if (rows.isEmpty()) {
                                com.trinetrax.attendance.ui.components.EmptyRowText("No records found")
                            }
                            rows.forEach { row ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        row.usn,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1.3f)
                                    )
                                    Text(
                                        row.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(2.0f),
                                        maxLines = 1
                                    )
                                    Text(
                                        "${row.present}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(0.8f)
                                    )
                                    Text(
                                        "${row.total}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(0.8f)
                                    )
                                    Text(
                                        "${row.pct}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (row.pct >= 75) SuccessGreen else DangerRed,
                                        modifier = Modifier.weight(1.7f)
                                    )
                                }
                            }
                    }
                }
            }
        }

        if (loaded && records.isEmpty() && error == null) {
            item {
                Text(
                    "No attendance records found",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    color = Color(0xFF6C757D),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
