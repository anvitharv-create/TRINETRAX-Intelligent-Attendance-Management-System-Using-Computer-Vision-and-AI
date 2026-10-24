package com.trinetrax.attendance.ui.screens.manage

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trinetrax.attendance.data.model.BulkFaceCommitDto
import com.trinetrax.attendance.data.model.BulkFaceItemDto
import com.trinetrax.attendance.data.model.BulkFacePreviewDto
import com.trinetrax.attendance.data.model.RecognitionStatusDto
import com.trinetrax.attendance.data.model.StudentDto
import com.trinetrax.attendance.data.model.StudentWriteRequest
import com.trinetrax.attendance.data.model.SubjectDto
import com.trinetrax.attendance.data.model.SubjectWriteRequest
import com.trinetrax.attendance.data.model.TeacherDto
import com.trinetrax.attendance.data.model.TeacherWriteRequest
import com.trinetrax.attendance.ui.components.BannerCard
import com.trinetrax.attendance.ui.components.ConfirmDialog
import com.trinetrax.attendance.ui.components.ErrorState
import com.trinetrax.attendance.ui.components.FieldSelect
import com.trinetrax.attendance.ui.components.FilterSelect
import com.trinetrax.attendance.ui.components.KeyValueRow
import com.trinetrax.attendance.ui.components.LoadingView
import com.trinetrax.attendance.ui.components.SearchField
import com.trinetrax.attendance.ui.components.TableHeaderRow
import com.trinetrax.attendance.ui.nav.Routes
import com.trinetrax.attendance.ui.theme.DangerRed
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.ui.theme.SuccessGreen
import com.trinetrax.attendance.ui.theme.WarningAmber
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.launch

private val BulkStatusColors = mapOf(
    "ready" to Color(0xFF1B5E20),
    "already_registered" to Color(0xFF0277BD),
    "missing_photo" to Color(0xFFE65100),
    "not_in_db" to Color(0xFFB71C1C),
    "invalid" to Color(0xFFB71C1C),
    "blocked" to Color(0xFFB71C1C),
    "unvalidated" to Color(0xFFE65100)
)

private val BulkStatusLabels = mapOf(
    "ready" to "Ready",
    "already_registered" to "Already registered",
    "missing_photo" to "No photo",
    "not_in_db" to "Not in DB",
    "invalid" to "Invalid image",
    "blocked" to "Backend unavailable",
    "unvalidated" to "Not validated"
)

private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull() ?: uri.lastPathSegment ?: "file"

private fun readBytes(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
}.getOrNull()

private fun thumbnailBitmap(dataUrl: String?) = runCatching {
    if (dataUrl.isNullOrBlank()) return null
    val base64 = dataUrl.substringAfter(",", dataUrl)
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

@Composable
private fun FormField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Parent / guardian contact block, used by both the Add and Edit student
 * forms so the two always look and behave the same.
 *
 * Kept deliberately grouped: the student's own details stay above, the
 * guardian's details sit in their own tinted card with one clear "Verified"
 * switch per channel - a report is only ever delivered to a verified contact.
 */
/**
 * One-line, honest summary of where a parent report can go for this student,
 * mirroring how the backend decides deliverability.
 */
private fun parentContactSummary(student: StudentDto): String {
    val email = student.parentEmail?.trim().orEmpty()
    val whatsapp = student.parentWhatsapp?.trim().orEmpty()
    val channels = mutableListOf<String>()
    if (email.isNotEmpty()) {
        channels += if (student.parentEmailVerified == true) "Email ✓" else "Email (unverified)"
    }
    if (whatsapp.isNotEmpty()) {
        channels += if (student.parentWhatsappVerified == true) "WhatsApp ✓" else "WhatsApp (unverified)"
    }
    return if (channels.isEmpty()) {
        "Parent: not registered"
    } else {
        "Parent: " + channels.joinToString(" · ")
    }
}

@Composable
private fun ParentContactFields(
    parentEmail: String,
    parentEmailVerified: Boolean,
    parentWhatsapp: String,
    parentWhatsappVerified: Boolean,
    onParentEmailChange: (String) -> Unit,
    onParentEmailVerifiedChange: (Boolean) -> Unit,
    onParentWhatsappChange: (String) -> Unit,
    onParentWhatsappVerifiedChange: (Boolean) -> Unit
) {
    val whatsappLooksValid = parentWhatsapp.isBlank() ||
        parentWhatsapp.trim().matches(Regex("^\\+[1-9]\\d{7,14}$"))

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF4F7FB),
        border = BorderStroke(1.dp, Color(0xFFD7E3F4)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Parent / Guardian Contact",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1F3B63)
            )
            Text(
                "Attendance reports are delivered here. A contact is only used " +
                    "once it is marked verified.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5A6B82)
            )

            FormField("Parent email", parentEmail, onValueChange = onParentEmailChange)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = parentEmailVerified,
                    onCheckedChange = { on ->
                        if (!on && parentEmail.isBlank()) {
                            onParentEmailChange("")
                        }
                        onParentEmailVerifiedChange(on)
                    }
                )
                Text(
                    if (parentEmailVerified) "Email verified" else "Email not verified",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            FormField("Parent WhatsApp (with country code)", parentWhatsapp, onValueChange = onParentWhatsappChange)
            if (!whatsappLooksValid) {
                Text(
                    "Use international format, e.g. +919876543210",
                    style = MaterialTheme.typography.labelSmall,
                    color = DangerRed
                )
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = parentWhatsappVerified,
                    onCheckedChange = { on ->
                        if (!on && parentWhatsapp.isBlank()) {
                            onParentWhatsappChange("")
                        }
                        onParentWhatsappVerifiedChange(on)
                    }
                )
                Text(
                    if (parentWhatsappVerified) "WhatsApp verified" else "WhatsApp not verified",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SmallActionButton(text: String, container: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = container,
        onClick = onClick
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private data class StudentForm(
    val id: Int? = null,
    val studentId: String = "",
    val name: String = "",
    val rollNumber: String = "",
    val email: String = "",
    val branch: String = "ISE",
    val semester: String = "1st Semester",
    val section: String = "Section A",
    val password: String = "",
    // Parent / guardian contact - where parent reports get delivered.
    val parentEmail: String = "",
    val parentEmailVerified: Boolean = false,
    val parentWhatsapp: String = "",
    val parentWhatsappVerified: Boolean = false
)

private data class TeacherForm(
    val id: Int? = null,
    val employeeId: String = "",
    val name: String = "",
    val email: String = "",
    val branch: String = "ISE",
    val semester: String = "1st Semester",
    val section: String = "Section A",
    val password: String = ""
)

/**
 * Students list — mirrors web `StudentsPage.jsx`:
 * face-status header, Add/Edit/Delete, per-row Register Face,
 * Bulk Face Registration (xlsx + zip → validate → import).
 */
@Composable
fun StudentsScreen(vm: SessionViewModel, onNavigate: (String) -> Unit = {}) {
    val user = vm.user.value
    val myBranch = if (user?.role == "hod" || user?.role == "teacher") user.branch ?: "" else ""
    val branchOptions = if (myBranch.isNotEmpty()) listOf(myBranch)
    else com.trinetrax.attendance.ui.components.WebOptions.BRANCHES

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var students by remember { mutableStateOf<List<StudentDto>>(emptyList()) }
    var faceStatus by remember { mutableStateOf<RecognitionStatusDto?>(null) }
    var refresh by remember { mutableStateOf(0) }

    var branch by remember { mutableStateOf(myBranch) }
    var semester by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    var showForm by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(StudentForm(branch = myBranch.ifEmpty { "ISE" })) }
    var editing by remember { mutableStateOf<StudentForm?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageTone by remember { mutableStateOf("success") }
    var deleteTarget by remember { mutableStateOf<StudentDto?>(null) }
    var bulkOpen by remember { mutableStateOf(false) }

    LaunchedEffect(branch, semester, section, refresh) {
        loading = true
        error = null
        vm.repo.students(
            branch = branch.ifEmpty { null },
            semester = semester.ifEmpty { null },
            section = section.ifEmpty { null }
        ).onSuccess {
            students = it
            loading = false
        }.onFailure {
            error = vm.repo.humanError(it)
            loading = false
        }
    }

    LaunchedEffect(refresh) {
        vm.repo.recognitionStatus().onSuccess { faceStatus = it }
    }

    val filtered = remember(students, query) {
        students.filter { s ->
            query.isBlank() ||
                listOfNotNull(s.name, s.studentId, s.rollNumber, s.email)
                    .any { it.contains(query, ignoreCase = true) }
        }
    }

    val semesterOptions = com.trinetrax.attendance.ui.components.WebOptions.SEMESTERS
    val sectionOptions = com.trinetrax.attendance.ui.components.WebOptions.SECTIONS

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            // Title + status on their own line: sharing one row with the two wide
            // buttons squeezed the heading into a 1-word-wide column.
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Students",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    faceStatus?.let { status ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (status.real == true) {
                                "✅ Face recognition active — ${status.registered ?: 0} registered face(s). Click \"Register Face\" to add a student's photo."
                            } else {
                                "⚠️ Real recognition backend unavailable — results simulated."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.real == true) SuccessGreen else WarningAmber
                        )
                    }
                }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { bulkOpen = !bulkOpen },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (bulkOpen) "Close Bulk Import" else "⬇ Bulk Face Import",
                            style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            showForm = !showForm
                            editing = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (showForm) "Close" else "+ Add Student",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        message?.let { msg ->
            item { BannerCard(messageTone, msg) }
        }

        if (bulkOpen) {
            item {
                BulkFaceImportPanel(vm, faceStatus) {
                    refresh++
                    form = StudentForm(branch = myBranch.ifEmpty { "ISE" })
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.weight(1f)) {
                    FilterSelect("Branch", branchOptions, branch,
                        placeholder = "All Branches",
                        onSelect = { branch = it; semester = ""; section = "" })
                }
                Box(Modifier.weight(1f)) {
                    FilterSelect("Semester", semesterOptions, semester,
                        placeholder = "All Semesters",
                        onSelect = { semester = it; section = "" })
                }
                Box(Modifier.weight(1f)) {
                    FilterSelect("Section", sectionOptions, section,
                        placeholder = "All Sections",
                        onSelect = { section = it })
                }
            }
        }

        item {
            SearchField(value = query, onValueChange = { query = it },
                hint = "Search by name, USN or email")
            Spacer(Modifier.height(6.dp))
            Text(
                "${filtered.size} of ${students.size} students",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText
            )
        }

        editing?.let { edit ->
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFF8E1),
                    border = BorderStroke(1.dp, Color(0xFFF1C48B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Edit Student — ${edit.name}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        FormField("Full Name", edit.name) { editing = edit.copy(name = it) }
                        FormField("Email", edit.email) { editing = edit.copy(email = it) }
                        FormField("Roll Number", edit.rollNumber) { editing = edit.copy(rollNumber = it) }
                        FormField("New Password (optional)", edit.password) { editing = edit.copy(password = it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Branch",
                                    options = branchOptions,
                                    value = edit.branch,
                                    onSelect = { editing = edit.copy(branch = it) }
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Semester",
                                    options = semesterOptions,
                                    value = edit.semester,
                                    onSelect = { editing = edit.copy(semester = it) }
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Section",
                                    options = sectionOptions,
                                    value = edit.section,
                                    onSelect = { editing = edit.copy(section = it) }
                                )
                            }
                        }
                        ParentContactFields(
                            parentEmail = edit.parentEmail,
                            parentEmailVerified = edit.parentEmailVerified,
                            parentWhatsapp = edit.parentWhatsapp,
                            parentWhatsappVerified = edit.parentWhatsappVerified,
                            onParentEmailChange = { editing = edit.copy(parentEmail = it) },
                            onParentEmailVerifiedChange = {
                                editing = edit.copy(parentEmailVerified = it)
                            },
                            onParentWhatsappChange = { editing = edit.copy(parentWhatsapp = it) },
                            onParentWhatsappVerifiedChange = {
                                editing = edit.copy(parentWhatsappVerified = it)
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val body = edit.copy(password = edit.password.ifBlank { "" })
                                vm.requestFlowScope().launch {
                                    vm.repo.updateStudent(
                                        edit.id ?: -1,
                                        StudentWriteRequest(
                                            studentId = edit.studentId,
                                            name = body.name,
                                            rollNumber = body.rollNumber,
                                            email = body.email,
                                            branch = body.branch,
                                            semester = body.semester,
                                            section = body.section,
                                            password = body.password.ifBlank { null },
                                            parentEmail = body.parentEmail.trim(),
                                            parentEmailVerified = body.parentEmailVerified,
                                            parentWhatsapp = body.parentWhatsapp.trim(),
                                            parentWhatsappVerified = body.parentWhatsappVerified
                                        )
                                    ).onSuccess {
                                        message = "Student updated successfully"
                                        messageTone = "success"
                                        editing = null
                                        refresh++
                                    }.onFailure {
                                        message = vm.repo.humanError(it)
                                        messageTone = "danger"
                                    }
                                }
                            }) { Text("Save Changes") }
                            OutlinedButton(onClick = { editing = null }) { Text("Cancel") }
                        }
                    }
                }
            }
        }

        if (showForm) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add New Student",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        FormField("Student ID", form.studentId) { form = form.copy(studentId = it) }
                        FormField("Full Name", form.name) { form = form.copy(name = it) }
                        FormField("Roll Number", form.rollNumber) { form = form.copy(rollNumber = it) }
                        FormField("Email", form.email) { form = form.copy(email = it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Branch",
                                    options = branchOptions,
                                    value = form.branch,
                                    onSelect = { form = form.copy(branch = it) }
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Semester",
                                    options = semesterOptions,
                                    value = form.semester,
                                    onSelect = { form = form.copy(semester = it) }
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Section",
                                    options = sectionOptions,
                                    value = form.section,
                                    onSelect = { form = form.copy(section = it) }
                                )
                            }
                        }
                        ParentContactFields(
                            parentEmail = form.parentEmail,
                            parentEmailVerified = form.parentEmailVerified,
                            parentWhatsapp = form.parentWhatsapp,
                            parentWhatsappVerified = form.parentWhatsappVerified,
                            onParentEmailChange = { form = form.copy(parentEmail = it) },
                            onParentEmailVerifiedChange = { form = form.copy(parentEmailVerified = it) },
                            onParentWhatsappChange = { form = form.copy(parentWhatsapp = it) },
                            onParentWhatsappVerifiedChange = {
                                form = form.copy(parentWhatsappVerified = it)
                            }
                        )
                        OutlinedButton(onClick = {
                            message = null
                            vm.requestFlowScope().launch {
                                vm.repo.createStudent(
                                    StudentWriteRequest(
                                        studentId = form.studentId,
                                        name = form.name,
                                        rollNumber = form.rollNumber,
                                        email = form.email,
                                        branch = form.branch,
                                        semester = form.semester,
                                        section = form.section,
                                        parentEmail = form.parentEmail.trim(),
                                        parentEmailVerified = form.parentEmailVerified,
                                        parentWhatsapp = form.parentWhatsapp.trim(),
                                        parentWhatsappVerified = form.parentWhatsappVerified
                                    )
                                ).onSuccess {
                                    message = "Student added successfully"
                                    messageTone = "success"
                                    showForm = false
                                    form = StudentForm(branch = myBranch.ifEmpty { "ISE" })
                                    refresh++
                                }.onFailure {
                                    message = vm.repo.humanError(it)
                                    messageTone = "danger"
                                }
                            }
                        }) { Text("Save Student") }
                    }
                }
            }
        }

        when {
            loading -> item { LoadingView("Loading students...") }
            error != null -> item { ErrorState(error!!, onRetry = { refresh++ }) }
            filtered.isEmpty() -> item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "No students found",
                        modifier = Modifier.padding(24.dp),
                        color = MutedText
                    )
                }
            }
            else -> {
                items(filtered, key = { "s${it.id ?: it.hashCode()}" }) { student ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(student.name ?: "—", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (student.faceEmbedding.isNullOrBlank()) "Not registered" else "✓ Registered",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (student.faceEmbedding.isNullOrBlank()) MutedText else SuccessGreen
                                )
                            }
                            Text(student.studentId ?: "—",
                                style = MaterialTheme.typography.bodySmall, color = MutedText)
                            Text(student.email ?: "No email",
                                style = MaterialTheme.typography.bodySmall, color = MutedText)
                            Text(
                                listOfNotNull(
                                    student.rollNumber,
                                    student.branch,
                                    student.semester,
                                    student.section
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MutedText
                            )
                            // Which channel a parent report can actually use
                            // for this student.
                            Text(
                                parentContactSummary(student),
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedText
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallActionButton("✏️ Edit", Color(0xFF0277BD)) {
                                    editing = StudentForm(
                                        id = student.id,
                                        studentId = student.studentId ?: "",
                                        name = student.name ?: "",
                                        rollNumber = student.rollNumber ?: "",
                                        email = student.email ?: "",
                                        branch = student.branch ?: branchOptions.first(),
                                        semester = student.semester ?: "1st Semester",
                                        section = student.section ?: "Section A",
                                        parentEmail = student.parentEmail ?: "",
                                        parentEmailVerified = student.parentEmailVerified == true,
                                        parentWhatsapp = student.parentWhatsapp ?: "",
                                        parentWhatsappVerified = student.parentWhatsappVerified == true
                                    )
                                    showForm = false
                                }
                                SmallActionButton("📷 Register Face", Color(0xFF0277BD)) {
                                    onNavigate(Routes.REGISTER_FACE)
                                }
                                SmallActionButton("Delete", Color(0xFFE53935)) {
                                    deleteTarget = student
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete student",
            message = "Delete this student and all their attendance records?",
            confirmLabel = "Delete",
            onConfirm = {
                deleteTarget = null
                vm.requestFlowScope().launch {
                    vm.repo.deleteStudent(target.id ?: -1).onSuccess {
                        refresh++
                    }.onFailure {
                        message = vm.repo.humanError(it)
                        messageTone = "danger"
                    }
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

/**
 * Bulk Face Registration panel — web `StudentsPage.jsx` bulk block:
 * xlsx roster + photos zip → 1. Validate → preview table → 2. Import N face(s).
 */
@Composable
private fun BulkFaceImportPanel(
    vm: SessionViewModel,
    faceStatus: RecognitionStatusDto?,
    onImported: () -> Unit
) {
    val context = LocalContext.current
    var rosterName by remember { mutableStateOf<String?>(null) }
    var rosterBytes by remember { mutableStateOf<ByteArray?>(null) }
    var photosName by remember { mutableStateOf<String?>(null) }
    var photosBytes by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<BulkFacePreviewDto?>(null) }
    var previewBusy by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var commitResult by remember { mutableStateOf<BulkFaceCommitDto?>(null) }
    var commitBusy by remember { mutableStateOf(false) }
    var commitError by remember { mutableStateOf<String?>(null) }
    var overwrite by remember { mutableStateOf(false) }

    val rosterPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            rosterName = displayName(context, uri)
            rosterBytes = readBytes(context, uri)
            preview = null
            commitResult = null
        }
    }
    val photosPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            photosName = displayName(context, uri)
            photosBytes = readBytes(context, uri)
            preview = null
            commitResult = null
        }
    }

    val summary = preview?.summary
    val pendingCount = (summary?.ready ?: 0) +
        if (overwrite) (summary?.already_registered ?: 0) else 0

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bulk Face Registration",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Upload an Excel roster (.xlsx with a Student ID / USN column) and a ZIP of photos " +
                    "named like 1AR23IS006.jpg. Nothing is saved until you press Import — existing " +
                    "face registrations are never overwritten unless you opt in below.",
                style = MaterialTheme.typography.bodySmall, color = MutedText
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Excel roster:", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold)
                    OutlinedButton(
                        onClick = { rosterPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(rosterName ?: "Choose .xlsx", maxLines = 1,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Photos ZIP:", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold)
                    OutlinedButton(
                        onClick = { photosPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(photosName ?: "Choose .zip", maxLines = 1,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    enabled = rosterBytes != null && photosBytes != null && !previewBusy,
                    onClick = {
                        previewBusy = true
                        previewError = null
                        preview = null
                        commitResult = null
                        commitError = null
                        val rn = rosterName ?: "roster.xlsx"
                        val rb = rosterBytes ?: return@OutlinedButton
                        val pn = photosName ?: "photos.zip"
                        val pb = photosBytes ?: return@OutlinedButton
                        vm.requestFlowScope().launch {
                            vm.repo.bulkFacesPreview(rn, rb, pn, pb).onSuccess {
                                preview = it
                                previewBusy = false
                            }.onFailure {
                                previewError = vm.repo.humanError(it)
                                previewBusy = false
                            }
                        }
                    }
                ) { Text(if (previewBusy) "Validating..." else "1. Validate") }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
                    Text("Overwrite existing registrations",
                        style = MaterialTheme.typography.labelSmall)
                }
            }

            OutlinedButton(
                enabled = preview != null && pendingCount > 0 && !commitBusy,
                onClick = {
                    commitBusy = true
                    commitError = null
                    commitResult = null
                    val rn = rosterName ?: "roster.xlsx"
                    val rb = rosterBytes ?: return@OutlinedButton
                    val pn = photosName ?: "photos.zip"
                    val pb = photosBytes ?: return@OutlinedButton
                    vm.requestFlowScope().launch {
                        vm.repo.bulkFacesCommit(rn, rb, pn, pb, overwrite).onSuccess {
                            commitResult = it
                            commitBusy = false
                            onImported()
                        }.onFailure {
                            commitError = vm.repo.humanError(it)
                            commitBusy = false
                        }
                    }
                }
            ) {
                Text(if (commitBusy) "Importing..." else "2. Import $pendingCount face(s)")
            }

            if (faceStatus?.real == false) {
                BannerCard(
                    "warning",
                    "⚠️ Recognition backend unavailable — validation and import are blocked until OpenCV + models are present."
                )
            }
            previewError?.let { BannerCard("danger", it) }
            commitError?.let { BannerCard("danger", it) }

            preview?.let { prev ->
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val s = prev.summary
                    listOfNotNull(
                        s?.ready?.takeIf { it > 0 }?.let { "Ready" to it },
                        s?.already_registered?.takeIf { it > 0 }?.let { "Already registered" to it },
                        s?.missing_photo?.takeIf { it > 0 }?.let { "No photo" to it },
                        s?.not_in_db?.takeIf { it > 0 }?.let { "Not in DB" to it },
                        s?.invalid?.takeIf { it > 0 }?.let { "Invalid image" to it },
                        s?.blocked?.takeIf { it > 0 }?.let { "Backend unavailable" to it },
                        s?.unvalidated?.takeIf { it > 0 }?.let { "Not validated" to it }
                    ).forEach { (label, count) ->
                        val key = BulkStatusLabels.entries
                            .firstOrNull { it.value == label }?.key
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = BulkStatusColors[key] ?: Color(0xFF607D8B)
                        ) {
                            Text(
                                "$label: $count",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Surface(shape = RoundedCornerShape(50), color = Color(0xFFECEFF1)) {
                    Text(
                        "Roster rows: ${summary?.rosterRows ?: 0} · Matched photos: ${summary?.photosMatched ?: 0} " +
                            "· Unmatched photos: ${summary?.photosUnmatched ?: 0}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = Color(0xFF37474F),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                val errorLines = buildList {
                    prev.roster?.errors?.take(8)?.forEach { add("Row ${it.row}: ${it.error}") }
                    prev.photos?.errors?.take(8)?.forEach { add("${it.file}: ${it.error}") }
                }.take(8)
                if (errorLines.isNotEmpty()) {
                    Column {
                        errorLines.forEach { line ->
                            Text("• $line", style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFB71C1C))
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Color(0xFFE3E8EF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        TableHeaderRow(listOf("", "USN", "Name", "Photo", "Status", "Details"))
                        if (prev.items.isEmpty()) {
                            Text(
                                "No roster rows found",
                                modifier = Modifier.padding(14.dp),
                                color = MutedText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        prev.items.forEach { item ->
                            BulkPreviewRow(item)
                        }
                    }
                }

                val unmatched = prev.match?.unmatched.orEmpty()
                if (unmatched.isNotEmpty()) {
                    Text(
                        "Unmatched photos: ${unmatched.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE65100)
                    )
                }
            }

            commitResult?.let { result ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFF1F8E9),
                    border = BorderStroke(1.dp, Color(0xFFC5E1A5)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "✅ Imported ${result.summary?.imported ?: 0} · Skipped ${result.summary?.skipped ?: 0} " +
                                "· Failed ${result.summary?.failed ?: 0}",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Total registered faces now: ${result.registeredTotal ?: 0}",
                            style = MaterialTheme.typography.labelSmall, color = MutedText
                        )
                        result.failed.take(10).forEach {
                            Text("• ${it.studentId}: ${it.reason}",
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFFB71C1C))
                        }
                        result.skipped.take(10).forEach {
                            Text("• ${it.studentId}: ${it.reason}",
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFFE65100))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BulkPreviewRow(item: BulkFaceItemDto) {
    val statusLabel = BulkStatusLabels[item.status] ?: item.status ?: "—"
    val statusColor = BulkStatusColors[item.status] ?: Color(0xFF444444)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = remember(item.thumbnail) { thumbnailBitmap(item.thumbnail) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Spacer(Modifier.size(40.dp))
        }
        Text(
            item.studentId ?: "—",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(76.dp)
        )
        Text(
            item.name ?: "—",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Text(
            item.filename ?: "—",
            style = MaterialTheme.typography.labelSmall,
            color = MutedText,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Text(
            statusLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = statusColor
        )
        val details = buildString {
            append(item.reason.orEmpty().ifBlank {
                item.faces?.let { "$it face(s) detected" } ?: ""
            })
            if (!item.extraPhotos.isNullOrEmpty()) {
                append(" · +${item.extraPhotos.size} extra photo(s)")
            }
        }
        Text(
            details,
            style = MaterialTheme.typography.labelSmall,
            color = MutedText,
            modifier = Modifier.weight(1.2f),
            maxLines = 2
        )
    }
}

/**
 * Teachers list — mirrors web `TeachersPage.jsx`
 * (branch filter, Add/Edit/Delete).
 */
@Composable
fun TeachersScreen(vm: SessionViewModel) {
    val user = vm.user.value
    val myBranch = if (user?.role == "hod" || user?.role == "teacher") user.branch ?: "" else ""
    val branchOptions = if (myBranch.isNotEmpty()) listOf(myBranch)
    else com.trinetrax.attendance.ui.components.WebOptions.BRANCHES

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var teachers by remember { mutableStateOf<List<TeacherDto>>(emptyList()) }
    var branchFilter by remember { mutableStateOf(myBranch) }
    var refresh by remember { mutableStateOf(0) }

    var showForm by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(TeacherForm(branch = myBranch.ifEmpty { "ISE" })) }
    var editing by remember { mutableStateOf<TeacherForm?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageTone by remember { mutableStateOf("success") }
    var deleteTarget by remember { mutableStateOf<TeacherDto?>(null) }

    LaunchedEffect(branchFilter, refresh) {
        loading = true
        error = null
        vm.repo.teachers(branch = branchFilter.ifEmpty { null }).onSuccess {
            teachers = it
            loading = false
        }.onFailure {
            error = vm.repo.humanError(it)
            loading = false
        }
    }

    val semesterOptions = com.trinetrax.attendance.ui.components.WebOptions.SEMESTERS
    val sectionOptions = com.trinetrax.attendance.ui.components.WebOptions.SECTIONS

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Teachers", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = {
                    showForm = !showForm
                    editing = null
                }) {
                    Text(if (showForm) "Close" else "+ Add Teacher",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        message?.let { msg -> item { BannerCard(messageTone, msg) } }

        item {
            FilterSelect("Branch", branchOptions, branchFilter,
                placeholder = "All Branches",
                onSelect = { branchFilter = it })
        }

        editing?.let { edit ->
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFF8E1),
                    border = BorderStroke(1.dp, Color(0xFFF1C48B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Edit Teacher — ${edit.name}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        FormField("Full Name", edit.name) { editing = edit.copy(name = it) }
                        FormField("Email", edit.email) { editing = edit.copy(email = it) }
                        FormField("New Password (optional)", edit.password) { editing = edit.copy(password = it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Branch",
                                    options = branchOptions,
                                    value = edit.branch,
                                    onSelect = { editing = edit.copy(branch = it) }
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Semester",
                                    options = semesterOptions,
                                    value = edit.semester,
                                    onSelect = { editing = edit.copy(semester = it) }
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Section",
                                    options = sectionOptions,
                                    value = edit.section,
                                    onSelect = { editing = edit.copy(section = it) }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                vm.requestFlowScope().launch {
                                    vm.repo.updateTeacher(
                                        edit.id ?: -1,
                                        TeacherWriteRequest(
                                            employeeId = edit.employeeId,
                                            name = edit.name,
                                            email = edit.email,
                                            branch = edit.branch,
                                            semester = edit.semester.ifBlank { null },
                                            section = edit.section.ifBlank { null },
                                            password = edit.password.ifBlank { null }
                                        )
                                    ).onSuccess {
                                        message = "Teacher updated successfully"
                                        messageTone = "success"
                                        editing = null
                                        refresh++
                                    }.onFailure {
                                        message = vm.repo.humanError(it)
                                        messageTone = "danger"
                                    }
                                }
                            }) { Text("Save Changes") }
                            OutlinedButton(onClick = { editing = null }) { Text("Cancel") }
                        }
                    }
                }
            }
        }

        if (showForm) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add New Teacher",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        FormField("Employee ID", form.employeeId) { form = form.copy(employeeId = it) }
                        FormField("Full Name", form.name) { form = form.copy(name = it) }
                        FormField("Email", form.email) { form = form.copy(email = it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Branch",
                                    options = branchOptions,
                                    value = form.branch,
                                    onSelect = { form = form.copy(branch = it) }
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Semester",
                                    options = semesterOptions,
                                    value = form.semester,
                                    onSelect = { form = form.copy(semester = it) }
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Section",
                                    options = sectionOptions,
                                    value = form.section,
                                    onSelect = { form = form.copy(section = it) }
                                )
                            }
                        }
                        OutlinedButton(onClick = {
                            message = null
                            vm.requestFlowScope().launch {
                                vm.repo.createTeacher(
                                    TeacherWriteRequest(
                                        employeeId = form.employeeId,
                                        name = form.name,
                                        email = form.email,
                                        branch = form.branch,
                                        semester = form.semester,
                                        section = form.section
                                    )
                                ).onSuccess {
                                    message = "Teacher added successfully"
                                    messageTone = "success"
                                    showForm = false
                                    form = TeacherForm(branch = myBranch.ifEmpty { "ISE" })
                                    refresh++
                                }.onFailure {
                                    message = vm.repo.humanError(it)
                                    messageTone = "danger"
                                }
                            }
                        }) { Text("Save Teacher") }
                    }
                }
            }
        }

        when {
            loading -> item { LoadingView("Loading teachers...") }
            error != null -> item { ErrorState(error!!, onRetry = { refresh++ }) }
            teachers.isEmpty() -> item {
                Text("No teachers found", color = MutedText,
                    style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                items(teachers, key = { "t${it.id ?: it.hashCode()}" }) { teacher ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Badge, contentDescription = null, tint = MutedText)
                                    Text(teacher.name ?: "—",
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                            Text(
                                listOfNotNull(
                                    teacher.employeeId,
                                    teacher.email,
                                    teacher.branch
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MutedText
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallActionButton("✏️ Edit", Color(0xFFE94F37)) {
                                    editing = TeacherForm(
                                        id = teacher.id,
                                        employeeId = teacher.employeeId ?: "",
                                        name = teacher.name ?: "",
                                        email = teacher.email ?: "",
                                        branch = teacher.branch ?: branchOptions.first(),
                                        semester = teacher.semester ?: "1st Semester",
                                        section = teacher.section ?: "Section A"
                                    )
                                    showForm = false
                                }
                                SmallActionButton("Delete", Color(0xFFE53935)) {
                                    deleteTarget = teacher
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete teacher",
            message = "Delete this teacher?",
            confirmLabel = "Delete",
            onConfirm = {
                deleteTarget = null
                vm.requestFlowScope().launch {
                    vm.repo.deleteTeacher(target.id ?: -1).onSuccess {
                        refresh++
                    }.onFailure {
                        message = vm.repo.humanError(it)
                        messageTone = "danger"
                    }
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

/**
 * Subjects list — mirrors web `SubjectsPage.jsx`
 * (branch + semester filters, Add/Delete).
 */
@Composable
fun SubjectsScreen(vm: SessionViewModel) {
    val user = vm.user.value
    val myBranch = if (user?.role == "hod" || user?.role == "teacher") user.branch ?: "" else ""
    val branchOptions = if (myBranch.isNotEmpty()) listOf(myBranch)
    else com.trinetrax.attendance.ui.components.WebOptions.BRANCHES

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var subjects by remember { mutableStateOf<List<SubjectDto>>(emptyList()) }
    var teachers by remember { mutableStateOf<List<TeacherDto>>(emptyList()) }
    var branch by remember { mutableStateOf(myBranch.ifEmpty { "ISE" }) }
    var semester by remember { mutableStateOf("1st Semester") }
    var refresh by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    var showForm by remember { mutableStateOf(false) }
    var formName by remember { mutableStateOf("") }
    var formCode by remember { mutableStateOf("") }
    var formBranch by remember { mutableStateOf(myBranch.ifEmpty { "ISE" }) }
    var formSemester by remember { mutableStateOf("1st Semester") }
    var formTeacher by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageTone by remember { mutableStateOf("success") }
    var deleteTarget by remember { mutableStateOf<SubjectDto?>(null) }

    LaunchedEffect(branch, semester, refresh) {
        loading = true
        error = null
        vm.repo.subjects(branch = branch, semester = semester).onSuccess {
            subjects = it
            loading = false
        }.onFailure {
            error = vm.repo.humanError(it)
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        vm.repo.teachers(branch = myBranch.ifEmpty { null }).onSuccess { teachers = it }
    }

    val filtered = remember(subjects, query) {
        subjects.filter { s ->
            query.isBlank() ||
                listOfNotNull(s.name, s.code).any { it.contains(query, ignoreCase = true) }
        }
    }

    val teacherNames = remember(teachers) {
        teachers.mapNotNull { it.name }.distinct()
    }
    val teacherLabel = { id: String ->
        if (id.isEmpty()) "No teacher assigned"
        else teachers.firstOrNull { it.id?.toString() == id }?.name ?: "Teacher #$id"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Subjects", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { showForm = !showForm }) {
                    Text(if (showForm) "Close" else "+ Add Subject",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        message?.let { msg -> item { BannerCard(messageTone, msg) } }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    FieldSelect(
                        label = "Branch",
                        options = branchOptions,
                        value = branch,
                        onSelect = { branch = it }
                    )
                }
                Box(Modifier.weight(1f)) {
                    FieldSelect(
                        label = "Semester",
                        options = com.trinetrax.attendance.ui.components.WebOptions.SEMESTERS,
                        value = semester,
                        onSelect = { semester = it }
                    )
                }
            }
        }

        if (showForm) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add New Subject",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        FormField("Subject Name", formName) { formName = it }
                        FormField("Subject Code", formCode) { formCode = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Branch",
                                    options = branchOptions,
                                    value = formBranch,
                                    onSelect = { formBranch = it }
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                FieldSelect(
                                    label = "Semester",
                                    options = com.trinetrax.attendance.ui.components.WebOptions.SEMESTERS,
                                    value = formSemester,
                                    onSelect = { formSemester = it }
                                )
                            }
                        }
                        FieldSelect(
                            label = "Teacher",
                            options = teacherNames,
                            value = teachers.firstOrNull { it.id?.toString() == formTeacher }?.name ?: "",
                            onSelect = { name ->
                                formTeacher = teachers.firstOrNull { it.name == name }?.id?.toString() ?: ""
                            }
                        )
                        Text(teacherLabel(formTeacher),
                            style = MaterialTheme.typography.labelSmall, color = MutedText)
                        OutlinedButton(onClick = {
                            message = null
                            vm.requestFlowScope().launch {
                                vm.repo.createSubject(
                                    SubjectWriteRequest(
                                        name = formName,
                                        code = formCode,
                                        branch = formBranch,
                                        semester = formSemester,
                                        teacherId = formTeacher.ifBlank { null }?.toIntOrNull()
                                    )
                                ).onSuccess {
                                    message = "Subject added successfully"
                                    messageTone = "success"
                                    showForm = false
                                    formName = ""
                                    formCode = ""
                                    refresh++
                                }.onFailure {
                                    message = vm.repo.humanError(it)
                                    messageTone = "danger"
                                }
                            }
                        }) { Text("Save Subject") }
                    }
                }
            }
        }

        item {
            SearchField(value = query, onValueChange = { query = it },
                hint = "Search subject or code")
            Spacer(Modifier.height(4.dp))
            Text("${filtered.size} subjects",
                style = MaterialTheme.typography.bodySmall, color = MutedText)
        }

        when {
            loading -> item { LoadingView("Loading subjects...") }
            error != null -> item { ErrorState(error!!, onRetry = { refresh++ }) }
            filtered.isEmpty() -> item {
                Text("No subjects found", color = MutedText,
                    style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                items(filtered, key = { "sub${it.id ?: it.hashCode()}" }) { subject ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(subject.code ?: "—",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold)
                                SmallActionButton("Delete", Color(0xFFE74C3C)) {
                                    deleteTarget = subject
                                }
                            }
                            Text(subject.name ?: "—", fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(subject.branch, subject.semester).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MutedText
                            )
                            KeyValueRow(
                                "Teacher",
                                subject.teacherId?.let { teachers.firstOrNull { t -> t.id == it }?.name ?: "Teacher #$it" }
                                    ?: "Not assigned"
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete subject",
            message = "Delete this subject and its attendance records?",
            confirmLabel = "Delete",
            onConfirm = {
                deleteTarget = null
                vm.requestFlowScope().launch {
                    vm.repo.deleteSubject(target.id ?: -1).onSuccess {
                        refresh++
                    }.onFailure {
                        message = vm.repo.humanError(it)
                        messageTone = "danger"
                    }
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }
}
