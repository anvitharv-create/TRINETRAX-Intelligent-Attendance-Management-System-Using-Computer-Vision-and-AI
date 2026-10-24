package com.trinetrax.attendance.ui.screens.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trinetrax.attendance.data.model.AttendanceRecordDto
import com.trinetrax.attendance.data.model.CaptureRequest
import com.trinetrax.attendance.data.model.CaptureResultDto
import com.trinetrax.attendance.data.model.RecognitionStatusDto
import com.trinetrax.attendance.data.model.StudentDto
import com.trinetrax.attendance.data.model.StudentResultPayload
import com.trinetrax.attendance.data.model.SubjectDto
import com.trinetrax.attendance.ui.components.EmptyState
import com.trinetrax.attendance.ui.components.ErrorState
import com.trinetrax.attendance.ui.components.KeyValueRow
import com.trinetrax.attendance.ui.components.LoadingView
import com.trinetrax.attendance.ui.components.SectionCard
import com.trinetrax.attendance.ui.components.StatusPill
import com.trinetrax.attendance.ui.screens.attendance.SubjectFilterDropdown
import com.trinetrax.attendance.ui.screens.attendance.subjectLabel
import com.trinetrax.attendance.ui.theme.DangerRed
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.ui.theme.SuccessGreen
import com.trinetrax.attendance.ui.theme.WarningAmber
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * "Mark Attendance" - a faithful port of the web `AttendancePage`:
 * subject + date + three modes (Camera / Manual / Records), the result bar and
 * a per-student records table with status badges, confidence and delete.
 *
 * The backend is the only authority that writes attendance; recognition runs
 * server-side and unknown faces are never treated as absent.
 */
@ExperimentalMaterial3Api
@Composable
fun CaptureAttendanceScreen(vm: SessionViewModel) {
    val user = vm.user.value
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var subjects by remember { mutableStateOf<List<SubjectDto>>(emptyList()) }
    var selected by remember { mutableStateOf<SubjectDto?>(null) }
    var recStatus by remember { mutableStateOf<RecognitionStatusDto?>(null) }
    var students by remember { mutableStateOf<List<StudentDto>>(emptyList()) }

    var mode by remember { mutableStateOf("camera") }
    var captured by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CaptureResultDto?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }

    var records by remember { mutableStateOf<List<AttendanceRecordDto>>(emptyList()) }
    var recordsLoading by remember { mutableStateOf(false) }
    var recordsVersion by remember { mutableIntStateOf(0) }
    var manualPicked by remember { mutableStateOf<Set<Int>>(emptySet()) }

    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AttendanceRecordDto?>(null) }

    val subjectId = selected?.id
    val wantRecords = mode == "records" || result != null

    LaunchedEffect(Unit) {
        loading = true
        val dash = vm.repo.teacherDashboard()
            .getOrElse { error = vm.repo.humanError(it); loading = false; return@LaunchedEffect }
        subjects = dash.subjects.mapNotNull { s ->
            s.subjectId?.let {
                SubjectDto(id = it, name = s.name, code = s.code, branch = s.branch, semester = s.semester)
            }
        }
        selected = subjects.firstOrNull()
        students = vm.repo.students(
            branch = if (user?.role == "admin") null else user?.branch
        ).getOrNull() ?: emptyList()
        recStatus = vm.repo.recognitionStatus().getOrNull()
        loading = false
    }

    // The same records view the web app shows after a capture.
    LaunchedEffect(subjectId, date, recordsVersion, wantRecords) {
        if (!wantRecords || subjectId == null) return@LaunchedEffect
        recordsLoading = true
        records = vm.repo.attendance(subjectId, date).getOrElse {
            actionError = vm.repo.humanError(it); emptyList()
        }
        recordsLoading = false
    }

    if (loading) {
        LoadingView("Preparing camera…")
        return
    }
    if (error != null) {
        ErrorState(error!!)
        return
    }
    if (subjects.isEmpty()) {
        ErrorState("No subjects are assigned to you, so attendance cannot be captured.")
        return
    }

    val studentName: (Int?) -> String = { sid ->
        students.firstOrNull { it.id == sid }?.name ?: sid?.let { "Student #$it" } ?: "—"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                "Mark Attendance",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        item {
            SubjectFilterDropdown(
                subjects = subjects,
                selectedId = selected?.id,
                onSelect = { id -> selected = subjects.find { it.id == id } },
                label = selected?.let { subjectLabel(it) } ?: "Select subject"
            )
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Text(date)
                }
                modeTab("Camera", mode) { mode = "camera"; actionError = null }
                modeTab("Manual", mode) { mode = "manual"; actionError = null }
                modeTab("Records", mode) { mode = "records"; actionError = null }
            }
        }

        if (mode == "camera") {
            item {
                val bitmap = captured
                if (bitmap == null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    ) {
                        CameraSection(onImageCaptured = {
                            captured = it; result = null; actionError = null
                        })
                    }
                } else {
                    SectionCard("Captured photo") {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Captured classroom photo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = {
                                captured = null; result = null; actionError = null
                            }) {
                                Text("Retake")
                            }
                            Button(
                                enabled = !saving,
                                onClick = {
                                    val subject = selected ?: return@Button
                                    saving = true
                                    actionError = null
                                    result = null
                                    val payload = CaptureRequest(
                                        subjectId = subject.id ?: return@Button,
                                        date = date,
                                        imageBase64 = bitmapToBase64(bitmap),
                                        useRecognition = true
                                    )
                                    vm.requestFlowScope().launch {
                                        val r = vm.repo.captureAttendance(payload)
                                        saving = false
                                        r.onSuccess {
                                            result = it
                                            captured = null
                                            mode = "records"
                                            recordsVersion++
                                        }.onFailure { actionError = vm.repo.humanError(it) }
                                    }
                                }
                            ) {
                                Text(
                                    if (saving) "Processing…" else "Mark Attendance",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionCard("How it works") {
                    Text("1. Select the subject and date.")
                    Text("2. Allow camera access and point it at the classroom.")
                    Text("3. Press Capture.")
                    Text("4. Press Mark Attendance to run face recognition.")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Face recognition runs on the server. Faces it cannot match are " +
                            "recorded as Unknown and never counted as absent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                }
            }

            if (recStatus != null) {
                item {
                    SectionCard("Recognition backend") {
                        KeyValueRow("Active", if (recStatus!!.real == true) "Yes" else "No")
                        KeyValueRow("Method", recStatus!!.method ?: "—")
                        KeyValueRow("Registered faces", "${recStatus!!.registered ?: 0}")
                    }
                    if (recStatus!!.real != true) {
                        Text(
                            "The server reports that face recognition is not active, so attendance will be refused rather than faked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DangerRed
                        )
                    }
                }
            }
        }

        if (mode == "manual") {
            item {
                Text("Mark Present Manually", fontWeight = FontWeight.SemiBold)
                Text(
                    "Select the students who are present today for " +
                        (selected?.let { subjectLabel(it) } ?: "the selected subject") + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText
                )
            }
            items(students, key = { "m${it.id ?: it.hashCode()}" }) { student ->
                val id = student.id
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = id != null && id in manualPicked,
                            onCheckedChange = { checked ->
                                if (id == null) return@Checkbox
                                manualPicked = if (checked) manualPicked + id else manualPicked - id
                            }
                        )
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(student.name ?: "—", fontWeight = FontWeight.Medium)
                            Text(
                                listOfNotNull(student.studentId, student.branch).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedText
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        enabled = !saving,
                        onClick = { manualPicked = emptySet() }
                    ) {
                        Text("Clear")
                    }
                    Button(
                        enabled = !saving && manualPicked.isNotEmpty(),
                        onClick = {
                            val subject = selected ?: return@Button
                            saving = true
                            actionError = null
                            result = null
                            val results = manualPicked.map { id ->
                                StudentResultPayload(studentId = id, status = "present", confidence = 0.9)
                            }
                            vm.requestFlowScope().launch {
                                val r = vm.repo.captureAttendance(
                                    CaptureRequest(
                                        subjectId = subject.id ?: return@launch,
                                        date = date,
                                        studentResults = results
                                    )
                                )
                                saving = false
                                r.onSuccess {
                                    result = it
                                    manualPicked = emptySet()
                                    mode = "records"
                                    recordsVersion++
                                }.onFailure { actionError = vm.repo.humanError(it) }
                            }
                        }
                    ) {
                        Text(
                            if (saving) "Saving…" else "Save Attendance (${manualPicked.size})",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (wantRecords) {
            item {
                Text(
                    "Attendance Records" + (selected?.let { " — ${subjectLabel(it)}" } ?: ""),
                    fontWeight = FontWeight.SemiBold
                )
            }

            result?.let { res ->
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SuccessGreen.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Text("Present: ${res.present ?: 0}", fontWeight = FontWeight.SemiBold)
                            Text("Absent: ${res.absent ?: 0}", fontWeight = FontWeight.SemiBold)
                            Text("Unknown: ${res.unknown ?: 0}", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (recordsLoading) {
                item { Text("Loading records…", color = MutedText) }
            } else if (records.isEmpty()) {
                item {
                    EmptyState("No records yet. Capture attendance to see results.")
                }
            } else {
                items(records, key = { "r${it.id ?: it.hashCode()}" }) { rec ->
                    RecordRow(rec = rec, name = studentName(rec.studentId)) {
                        pendingDelete = rec
                    }
                }
            }
        }

        actionError?.let { msg ->
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DangerRed.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        msg,
                        color = DangerRed,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    showDatePicker = false
                    result = null
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    pendingDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this attendance record?") },
            text = {
                Text(
                    "${studentName(rec.studentId)} · ${rec.date ?: ""} · " +
                        (rec.status ?: "")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    val id = rec.id ?: return@TextButton
                    vm.requestFlowScope().launch {
                        val r = vm.repo.deleteAttendance(id)
                        r.onSuccess { recordsVersion++ }
                            .onFailure { actionError = vm.repo.humanError(it) }
                    }
                }) {
                    Text("Delete", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun modeTab(label: String, current: String, onClick: () -> Unit) {
    val active = label.equals(current, ignoreCase = true)
    OutlinedButton(onClick = onClick) {
        Text(
            label,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MutedText
        )
    }
}

/** One row of the records table: student, date, status badge, confidence, delete. */
@Composable
private fun RecordRow(rec: AttendanceRecordDto, name: String, onDelete: () -> Unit) {
    val (label, color) = when (rec.status) {
        "present" -> "Present" to SuccessGreen
        "absent" -> "Absent" to DangerRed
        "late" -> "Late" to WarningAmber
        else -> "Unknown" to MutedText
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Medium)
                Text(
                    "${rec.date ?: "—"} · ${formatConfidence(rec.confidence)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText
                )
            }
            StatusPill(label, color.copy(alpha = 0.14f), color)
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete record", tint = DangerRed)
            }
        }
    }
}

/** The web app prints confidence to 2 decimals (e.g. 0.95). */
private fun formatConfidence(value: Double?): String = when {
    value == null -> "—"
    value <= 1.0 -> "%.2f".format(value)
    else -> "%.1f%%".format(value)
}
