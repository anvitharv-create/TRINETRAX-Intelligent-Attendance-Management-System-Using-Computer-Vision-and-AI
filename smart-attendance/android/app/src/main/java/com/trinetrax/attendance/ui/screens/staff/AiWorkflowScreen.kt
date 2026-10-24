package com.trinetrax.attendance.ui.screens.staff

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trinetrax.attendance.data.model.AnalyzeRequest
import com.trinetrax.attendance.data.model.AnalyzeResponseDto
import com.trinetrax.attendance.data.model.CaptureRequest
import com.trinetrax.attendance.data.model.CaptureResultDto
import com.trinetrax.attendance.data.model.RecognitionStatusDto
import com.trinetrax.attendance.data.model.StudentDto
import com.trinetrax.attendance.data.model.StudentResultPayload
import com.trinetrax.attendance.data.model.SubjectDto
import com.trinetrax.attendance.ui.components.BannerCard
import com.trinetrax.attendance.ui.components.DividerThin
import com.trinetrax.attendance.ui.components.EmptyRowText
import com.trinetrax.attendance.ui.components.FieldSelect
import com.trinetrax.attendance.ui.components.FilterSelect
import com.trinetrax.attendance.ui.components.SectionCard
import com.trinetrax.attendance.ui.components.StatCard
import com.trinetrax.attendance.ui.components.TableHeaderRow
import com.trinetrax.attendance.ui.components.WebColors
import com.trinetrax.attendance.ui.components.WebOptions
import com.trinetrax.attendance.ui.screens.camera.CameraSection
import com.trinetrax.attendance.ui.screens.camera.bitmapToBase64
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate

private data class ReviewRow(
    val key: String,
    val dbStudentId: Int?,
    val studentId: String?,
    val name: String?,
    val rollNumber: String?,
    val status: String,
    val confidence: Double?,
    val matched: Boolean
)

private val WorkflowSteps = listOf(
    "1. Select Class & Subject",
    "2. Capture Classroom",
    "3. Review AI Results",
    "4. Confirm & Save"
)

private fun formatConfidencePct(value: Double?): String =
    value?.let { "%.0f%%".format(it) } ?: "—"

@Composable
fun AiWorkflowScreen(vm: SessionViewModel) {
    var step by remember { mutableIntStateOf(1) }
    var branch by remember { mutableStateOf("ISE") }
    var semester by remember { mutableStateOf("1st Semester") }
    var section by remember { mutableStateOf("Section A") }
    var subjectId by remember { mutableStateOf<Int?>(null) }
    var subjects by remember { mutableStateOf<List<SubjectDto>>(emptyList()) }
    var classStudents by remember { mutableStateOf<List<StudentDto>>(emptyList()) }
    var recStatus by remember { mutableStateOf<RecognitionStatusDto?>(null) }

    var captured by remember { mutableStateOf<Bitmap?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<AnalyzeResponseDto?>(null) }
    var rows by remember { mutableStateOf<List<ReviewRow>>(emptyList()) }
    var editing by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var saveResult by remember { mutableStateOf<CaptureResultDto?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var addSelection by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        recStatus = vm.repo.recognitionStatus().getOrNull()
    }
    LaunchedEffect(branch, semester, section) {
        classStudents = vm.repo.students(branch, semester, section).getOrElse { emptyList() }
    }
    LaunchedEffect(branch, semester) {
        subjects = vm.repo.subjects(branch, semester).getOrElse { emptyList() }
    }

    val selectedSubject = subjects.firstOrNull { it.id == subjectId }
    val subjectName = selectedSubject?.name ?: "—"
    val subjectOptions = subjects.map { "${it.name ?: "—"} (${it.code ?: "—"})" }
    val subjectDisplay = selectedSubject?.let { "${it.name ?: "—"} (${it.code ?: "—"})" } ?: ""
    val classOptions = classStudents.map { "${it.name ?: "—"} (${it.rollNumber ?: "—"})" }

    val presentCount = rows.count { it.status == "Present" }
    val absentCount = rows.count { it.status == "Absent" }
    val unknownCount = rows.count { it.status == "Unknown" }

    val resetAll = {
        step = 1
        captured = null
        analysis = null
        rows = emptyList()
        message = null
        saveResult = null
        editing = true
        addSelection = ""
    }

    val onAnalyze: () -> Unit = {
        val img = captured
        val sid = subjectId
        if (img == null || sid == null) {
            message = "warning" to "Please capture a classroom photo first."
        } else {
            analyzing = true
            message = null
            vm.requestFlowScope().launch {
                val result = vm.repo.analyzeImage(
                    AnalyzeRequest(
                        imageBase64 = bitmapToBase64(img),
                        subjectId = sid,
                        section = section
                    )
                )
                analyzing = false
                result.onSuccess { res ->
                    analysis = res
                    rows = res.results.mapIndexed { i, r ->
                        ReviewRow(
                            key = "${r.studentId ?: "unknown"}-$i",
                            dbStudentId = r.dbStudentId,
                            studentId = r.studentId,
                            name = r.name,
                            rollNumber = r.rollNumber,
                            status = if (r.status == "present") "Present" else "Unknown",
                            confidence = r.confidence,
                            matched = r.matched == true
                        )
                    }
                    step = 3
                }.onFailure { t ->
                    message = "danger" to vm.repo.humanError(t)
                }
            }
        }
    }

    val onSave: () -> Unit = {
        val sid = subjectId
        if (sid != null) {
            val payload = rows
                .filter { it.dbStudentId != null && it.status != "Unknown" }
                .map {
                    StudentResultPayload(
                        studentId = it.dbStudentId!!,
                        status = it.status.lowercase(),
                        confidence = it.confidence?.div(100.0) ?: 0.9
                    )
                }
            if (payload.isEmpty()) {
                message = "warning" to
                    "No students to save. Assign students to the detected faces or add them manually."
            } else {
                saving = true
                message = null
                vm.requestFlowScope().launch {
                    val r = vm.repo.captureAttendance(
                        CaptureRequest(
                            subjectId = sid,
                            date = LocalDate.now().toString(),
                            imageBase64 = null,
                            useRecognition = true,
                            studentResults = payload
                        )
                    )
                    saving = false
                    r.onSuccess { res ->
                        saveResult = res
                        message = "success" to
                            ("✅ Attendance saved. ${res.present ?: 0} present, " +
                                "${res.absent ?: 0} absent, ${res.unknown ?: 0} unknown.")
                        step = 4
                    }.onFailure { t ->
                        message = "danger" to vm.repo.humanError(t)
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                "AI Attendance Workflow",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        item { WorkflowStepper(step) }

        recStatus?.let { st ->
            item {
                if (st.real == true) {
                    BannerCard(
                        "success",
                        "✅ Real face recognition active (${st.method ?: "—"}). " +
                            "${st.registered ?: 0} face(s) registered. " +
                            "Register faces on the Students page to improve accuracy."
                    )
                } else {
                    BannerCard(
                        "warning",
                        "⚠️ Real recognition backend unavailable — analysis uses simulated " +
                            "results. Install `opencv-contrib-python` on the backend to enable " +
                            "real face recognition."
                    )
                }
            }
        }

        message?.let { m ->
            item { BannerCard(m.first, m.second) }
        }

        if (step == 1) {
            item {
                SectionCard("Select Class & Subject") {
                    Text(
                        "Follow the drill-down to the exact class you are teaching, then " +
                            "choose the subject for which attendance is being taken.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.weight(1f)) {
                            FilterSelect(
                                label = "Branch",
                                options = WebOptions.BRANCHES,
                                value = branch,
                                onSelect = { branch = it; subjectId = null }
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            FilterSelect(
                                label = "Semester",
                                options = WebOptions.SEMESTERS,
                                value = semester,
                                onSelect = { semester = it; subjectId = null }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.weight(1f)) {
                            FilterSelect(
                                label = "Section",
                                options = WebOptions.SECTIONS,
                                value = section,
                                onSelect = { section = it }
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            FilterSelect(
                                label = "Subject",
                                options = subjectOptions,
                                value = subjectDisplay,
                                onSelect = { label ->
                                    subjectId = subjects
                                        .firstOrNull { "${it.name ?: "—"} (${it.code ?: "—"})" == label }
                                        ?.id
                                },
                                placeholder = "Select subject"
                            )
                        }
                    }
                    if (subjectId != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                buildString {
                                    append(subjectName)
                                    append(" — $semester / $branch / $section")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${classStudents.size} students in class",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedText
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    WebButton(
                        text = "Next: Capture Classroom →",
                        container = WebColors.Primary,
                        enabled = subjectId != null
                    ) { step = 2 }
                }
            }
        }

        if (step == 2) {
            item {
                Text("Capture the Classroom", style = MaterialTheme.typography.titleMedium)
            }
            item {
                val bitmap = captured
                if (bitmap == null) {
                    Box(Modifier.fillMaxWidth().height(320.dp)) {
                        CameraSection(onImageCaptured = { captured = it })
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Captured classroom photo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                            )
                            Row(Modifier.padding(12.dp)) {
                                OutlinedButton(onClick = { captured = null }) {
                                    Text("Retake")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WebButton(
                        text = if (analyzing) "Analyzing image..." else "🔍 Analyze Image",
                        container = WebColors.Success,
                        enabled = !analyzing && captured != null,
                        onClick = onAnalyze
                    )
                    WebButton(text = "← Back", container = Color(0xFF95A5A6)) { step = 1 }
                }
            }
            item {
                Text(
                    "Tip: Position the camera to capture faces clearly. Good lighting and " +
                        "front-facing angles improve recognition accuracy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText
                )
            }
            item {
                Text("Selected Class", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = Color(0xFFF8F9FA),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            selectedSubject?.name ?: "—",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "$semester · $branch · $section",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText
                        )
                    }
                }
            }
            item {
                Text(
                    "Students in this class",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        TableHeaderRow(listOf("Name", "Roll"))
                        Column(
                            Modifier
                                .heightIn(max = 300.dp)
                                // scrollable containers don't clip by default, so a restored
                                // scroll offset used to paint the first row over the header
                                .clipScrollableContainer(Orientation.Vertical)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (classStudents.isEmpty()) {
                                EmptyRowText("No students found for this class.")
                            }
                            classStudents.forEach { s ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        s.name ?: "—",
                                        modifier = Modifier.weight(1f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        s.rollNumber ?: "—",
                                        modifier = Modifier.weight(1f),
                                        fontSize = 12.sp,
                                        color = MutedText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                DividerThin()
                            }
                        }
                    }
                }
            }
        }

        if (step == 3) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text("Review AI Results", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$subjectName · $semester · $branch · $section",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editing) {
                            WebButton(text = "Lock Editing", container = WebColors.Warning) {
                                editing = false
                            }
                        } else {
                            WebButton(text = "✏️ Edit Attendance", container = WebColors.Primary) {
                                editing = true
                            }
                        }
                        WebButton(text = "Start Over", container = Color(0xFF95A5A6), onClick = resetAll)
                    }
                }
            }
            item {
                if (editing) {
                    BannerCard(
                        "warning",
                        "AI analyzed the classroom photo. Review every row below — correct " +
                            "any misidentifications, mark absent students, and add anyone the " +
                            "camera missed. Nothing is saved yet."
                    )
                } else {
                    BannerCard(
                        "info",
                        "Editing is locked. Press Confirm & Save to write the attendance " +
                            "records to the database."
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(
                        "Detected Faces",
                        "${rows.size}",
                        Modifier.width(140.dp),
                        accent = WebColors.Info
                    )
                    StatCard(
                        "Present",
                        "$presentCount",
                        Modifier.width(140.dp),
                        accent = WebColors.Success
                    )
                    StatCard(
                        "Absent",
                        "$absentCount",
                        Modifier.width(140.dp),
                        accent = WebColors.Danger
                    )
                    StatCard(
                        "Unknown",
                        "$unknownCount",
                        Modifier.width(140.dp),
                        accent = MutedText
                    )
                    StatCard(
                        "Class Size",
                        "${classStudents.size}",
                        Modifier.width(140.dp),
                        accent = WebColors.Primary
                    )
                }
            }
            analysis?.backend?.let { b ->
                item {
                    BannerCard(
                        if (b.real == true) "success" else "warning",
                        "${analysis?.note ?: "—"} (method: ${b.method ?: "—"}, " +
                            "${b.registered ?: 0} registered face(s))."
                    )
                }
            }
            item {
                TableHeaderRow(
                    if (editing) {
                        listOf("Student", "Roll No", "Status", "Confidence", "Actions")
                    } else {
                        listOf("Student", "Roll No", "Status", "Confidence")
                    }
                )
            }
            if (rows.isEmpty()) {
                item {
                    EmptyRowText(
                        "No faces were detected in the image. Add students manually below " +
                            "or retake the photo."
                    )
                }
            } else {
                items(rows, key = { it.key }) { row ->
                    ReviewRowItem(
                        row = row,
                        classOptions = classOptions,
                        editing = editing,
                        onAssign = { label ->
                            val s = classStudents.firstOrNull {
                                "${it.name ?: "—"} (${it.rollNumber ?: "—"})" == label
                            }
                            if (s != null) {
                                rows = rows.map { r ->
                                    if (r.key == row.key) {
                                        r.copy(
                                            dbStudentId = s.id,
                                            studentId = s.studentId,
                                            name = s.name,
                                            rollNumber = s.rollNumber
                                        )
                                    } else r
                                }
                            }
                        },
                        onStatus = { newStatus ->
                            rows = rows.map { r ->
                                if (r.key == row.key) r.copy(status = newStatus) else r
                            }
                        },
                        onRemove = { rows = rows.filterNot { it.key == row.key } }
                    )
                    DividerThin()
                }
            }
            if (editing) {
                item {
                    Surface(
                        color = Color(0xFFF8F9FA),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "➕ Add student not detected by the camera:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(Modifier.weight(1f)) {
                                    FieldSelect(
                                        label = "Select student…",
                                        options = classOptions,
                                        value = addSelection,
                                        onSelect = { addSelection = it }
                                    )
                                }
                                WebButton(
                                    text = "Add",
                                    container = WebColors.Info,
                                    enabled = addSelection.isNotEmpty()
                                ) {
                                    val s = classStudents.firstOrNull {
                                        "${it.name ?: "—"} (${it.rollNumber ?: "—"})" == addSelection
                                    }
                                    if (s != null && s.id != null &&
                                        rows.none { it.dbStudentId == s.id }
                                    ) {
                                        rows = rows + ReviewRow(
                                            key = "manual-${s.id}",
                                            dbStudentId = s.id,
                                            studentId = s.studentId,
                                            name = s.name,
                                            rollNumber = s.rollNumber,
                                            status = "Present",
                                            confidence = null,
                                            matched = true
                                        )
                                    }
                                    addSelection = ""
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                ) {
                    WebButton(text = "← Retake Photo", container = Color(0xFF95A5A6)) { step = 2 }
                    WebButton(
                        text = if (saving) "Saving..." else "✓ Confirm & Save Attendance",
                        container = WebColors.Success,
                        enabled = !saving,
                        onClick = onSave
                    )
                }
            }
        }

        if (step == 4) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🎉", fontSize = 48.sp)
                    Text("Attendance Saved", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The attendance record has been written to the database.",
                        color = MutedText,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCard(
                            "Present",
                            "${saveResult?.present ?: presentCount}",
                            Modifier.width(120.dp),
                            accent = WebColors.Success
                        )
                        StatCard(
                            "Absent",
                            "${saveResult?.absent ?: absentCount}",
                            Modifier.width(120.dp),
                            accent = WebColors.Danger
                        )
                        StatCard(
                            "Unknown",
                            "${saveResult?.unknown ?: unknownCount}",
                            Modifier.width(120.dp),
                            accent = MutedText
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    WebButton(text = "Take Another Class", container = WebColors.Primary, onClick = resetAll)
                }
            }
        }
    }
}

@Composable
private fun WorkflowStepper(step: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8F9FA), RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WorkflowSteps.forEachIndexed { index, label ->
            val active = step == index + 1
            val done = step > index + 1
            Box(
                Modifier
                    .size(24.dp)
                    .background(
                        when {
                            active -> WebColors.Primary
                            done -> WebColors.Success
                            else -> Color(0xFFE0E0E0)
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (done) "✓" else label[0].toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                label,
                fontSize = 12.sp,
                color = if (active) WebColors.Primary else Color(0xFF666666),
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
            )
            if (index < WorkflowSteps.lastIndex) {
                Text("→", color = Color(0xFFBBBBBB), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ReviewRowItem(
    row: ReviewRow,
    classOptions: List<String>,
    editing: Boolean,
    onAssign: (String) -> Unit,
    onStatus: (String) -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        color = if (row.status == "Unknown") Color(0xFFFFF8F0) else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1.3f)) {
                    if (editing && !row.matched) {
                        FieldSelect(
                            label = "Assign student…",
                            options = classOptions,
                            value = if (row.dbStudentId != null) {
                                "${row.name ?: "—"} (${row.rollNumber ?: "—"})"
                            } else "",
                            onSelect = onAssign
                        )
                    } else {
                        Text(
                            row.name ?: "—",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    Text(
                        row.rollNumber ?: "—",
                        fontSize = 12.sp,
                        color = MutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(Modifier.weight(1f)) {
                    StatusSelect(row.status, enabled = editing, onSelect = onStatus)
                }
                Box(Modifier.weight(1f)) {
                    Text(
                        formatConfidencePct(row.confidence),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(Modifier.weight(0.8f)) {
                    if (editing) {
                        TextButton(onClick = onRemove) {
                            Text("Remove", color = WebColors.Danger, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSelect(status: String, enabled: Boolean, onSelect: (String) -> Unit) {
    if (enabled) {
        FieldSelect(
            label = "Status",
            options = listOf("Present", "Absent", "Unknown"),
            value = status,
            onSelect = onSelect
        )
    } else {
        OutlinedTextField(
            value = status,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            singleLine = true,
            label = { Text("Status") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun WebButton(
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
