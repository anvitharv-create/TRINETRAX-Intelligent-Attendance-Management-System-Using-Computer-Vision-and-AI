package com.trinetrax.attendance.ui.screens.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trinetrax.attendance.data.model.AdminStudentDto
import com.trinetrax.attendance.data.model.AdminTeacherDto
import com.trinetrax.attendance.data.model.HodDto
import com.trinetrax.attendance.data.model.HodWriteRequest
import com.trinetrax.attendance.data.model.StudentWriteRequest
import com.trinetrax.attendance.data.model.TeacherWriteRequest
import com.trinetrax.attendance.data.model.UserUpdateRequest
import com.trinetrax.attendance.ui.components.BannerCard
import com.trinetrax.attendance.ui.components.ConfirmDialog
import com.trinetrax.attendance.ui.components.DividerThin
import com.trinetrax.attendance.ui.components.EmptyRowText
import com.trinetrax.attendance.ui.components.FieldSelect
import com.trinetrax.attendance.ui.components.TableHeaderRow
import com.trinetrax.attendance.ui.components.WebColors
import com.trinetrax.attendance.ui.components.WebOptions
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.launch

private data class HodEdit(
    val id: Int,
    val name: String,
    val email: String,
    val branch: String,
    val password: String
)

@Composable
private fun WideButton(
    text: String,
    background: Color,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = background,
        onClick = onClick
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
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
private fun RowScope.ActionCell(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        content()
    }
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun AdminUsersScreen(vm: SessionViewModel) {
    var tab by remember { mutableStateOf("hods") }
    var hods by remember { mutableStateOf<List<HodDto>>(emptyList()) }
    var teachers by remember { mutableStateOf<List<AdminTeacherDto>>(emptyList()) }
    var students by remember { mutableStateOf<List<AdminStudentDto>>(emptyList()) }
    var selectedBranch by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }

    var showHodForm by remember { mutableStateOf(false) }
    var hodName by remember { mutableStateOf("") }
    var hodEmail by remember { mutableStateOf("") }
    var hodPassword by remember { mutableStateOf("hod123") }
    var hodBranch by remember { mutableStateOf("ISE") }
    var editingHod by remember { mutableStateOf<HodEdit?>(null) }

    var showTeacherForm by remember { mutableStateOf(false) }
    var teacherEmployeeId by remember { mutableStateOf("") }
    var teacherName by remember { mutableStateOf("") }
    var teacherEmail by remember { mutableStateOf("") }
    var teacherBranch by remember { mutableStateOf("ISE") }

    var showStudentForm by remember { mutableStateOf(false) }
    var studentId by remember { mutableStateOf("") }
    var studentName by remember { mutableStateOf("") }
    var studentRoll by remember { mutableStateOf("") }
    var studentEmail by remember { mutableStateOf("") }
    var studentBranch by remember { mutableStateOf("ISE") }

    var pendingTeacherDelete by remember { mutableStateOf<AdminTeacherDto?>(null) }
    var pendingStudentDelete by remember { mutableStateOf<AdminStudentDto?>(null) }

    LaunchedEffect(Unit) {
        hods = vm.repo.adminHods().getOrElse { emptyList() }
    }

    LaunchedEffect(tab) {
        if (tab != "hods") {
            selectedBranch = ""
            teachers = emptyList()
            students = emptyList()
        }
    }

    val loadBranch: (String) -> Unit = { branch ->
        selectedBranch = branch
        loading = true
        vm.requestFlowScope().launch {
            if (tab == "teachers") {
                teachers = vm.repo.adminTeachers(branch).getOrElse { emptyList() }
            } else {
                students = vm.repo.adminStudents(branch).getOrElse { emptyList() }
            }
            loading = false
        }
    }

    val createHod: () -> Unit = {
        message = null
        val name = hodName.trim()
        vm.requestFlowScope().launch {
            vm.repo.createHod(
                HodWriteRequest(
                    name = name,
                    email = hodEmail.trim(),
                    branch = hodBranch,
                    password = hodPassword
                )
            ).fold(
                onSuccess = {
                    hods = vm.repo.adminHods().getOrElse { emptyList() }
                    showHodForm = false
                    hodName = ""
                    hodEmail = ""
                    hodPassword = "hod123"
                    hodBranch = "ISE"
                    message = "success" to "HOD $name created."
                },
                onFailure = { message = "danger" to vm.repo.humanError(it) }
            )
        }
    }

    val saveHod: () -> Unit = {
        message = null
        val edit = editingHod
        if (edit != null) {
            vm.requestFlowScope().launch {
                vm.repo.updateUser(
                    userId = edit.id,
                    body = UserUpdateRequest(
                        fullName = edit.name.trim(),
                        email = edit.email.trim(),
                        branch = edit.branch,
                        password = edit.password.ifBlank { null }
                    )
                ).fold(
                    onSuccess = {
                        hods = vm.repo.adminHods().getOrElse { emptyList() }
                        editingHod = null
                        message = "success" to "HOD updated."
                    },
                    onFailure = { message = "danger" to vm.repo.humanError(it) }
                )
            }
        }
    }

    val createTeacher: () -> Unit = {
        message = null
        vm.requestFlowScope().launch {
            vm.repo.createTeacher(
                TeacherWriteRequest(
                    employeeId = teacherEmployeeId.trim(),
                    name = teacherName.trim(),
                    email = teacherEmail.trim(),
                    branch = teacherBranch,
                    semester = "1st Semester",
                    section = "Section A"
                )
            ).fold(
                onSuccess = {
                    showTeacherForm = false
                    teacherEmployeeId = ""
                    teacherName = ""
                    teacherEmail = ""
                    teacherBranch = "ISE"
                    if (selectedBranch.isNotBlank()) loadBranch(selectedBranch)
                    message = "success" to "Teacher added."
                },
                onFailure = { message = "danger" to vm.repo.humanError(it) }
            )
        }
    }

    val createStudent: () -> Unit = {
        message = null
        vm.requestFlowScope().launch {
            vm.repo.createStudent(
                StudentWriteRequest(
                    studentId = studentId.trim(),
                    name = studentName.trim(),
                    rollNumber = studentRoll.trim(),
                    email = studentEmail.trim(),
                    branch = studentBranch,
                    semester = "1st Semester",
                    section = "Section A"
                )
            ).fold(
                onSuccess = {
                    showStudentForm = false
                    studentId = ""
                    studentName = ""
                    studentRoll = ""
                    studentEmail = ""
                    studentBranch = "ISE"
                    if (selectedBranch.isNotBlank()) loadBranch(selectedBranch)
                    message = "success" to "Student added."
                },
                onFailure = { message = "danger" to vm.repo.humanError(it) }
            )
        }
    }

    val confirmDeleteTeacher: () -> Unit = {
        message = null
        val teacher = pendingTeacherDelete
        pendingTeacherDelete = null
        val id = teacher?.id
        if (id != null) {
            vm.requestFlowScope().launch {
                vm.repo.deleteTeacher(id).fold(
                    onSuccess = {
                        teachers = teachers.filterNot { it.id == id }
                        message = "success" to "Teacher deleted."
                    },
                    onFailure = { message = "danger" to vm.repo.humanError(it) }
                )
            }
        }
    }

    val confirmDeleteStudent: () -> Unit = {
        message = null
        val student = pendingStudentDelete
        pendingStudentDelete = null
        val id = student?.id
        if (id != null) {
            vm.requestFlowScope().launch {
                vm.repo.deleteStudent(id).fold(
                    onSuccess = {
                        students = students.filterNot { it.id == id }
                        message = "success" to "Student deleted."
                    },
                    onFailure = { message = "danger" to vm.repo.humanError(it) }
                )
            }
        }
    }

    val tabs = listOf(
        "hods" to "HOD Management",
        "teachers" to "Teacher Management",
        "students" to "Student Management"
    )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "User Management",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            item {
                Text(
                    text = "Manage HODs, teachers, and students across the college.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedText
                )
            }
            message?.let { (tone, text) ->
                item { BannerCard(tone = tone, message = text) }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tabs.forEach { (key, label) ->
                        WideButton(
                            text = label,
                            background = if (tab == key) WebColors.Primary else Color(0xFFE0E0E0),
                            contentColor = if (tab == key) Color.White else Color(0xFF333333),
                            onClick = { tab = key }
                        )
                    }
                }
            }

            if (tab == "hods") {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "HODs",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        WideButton(
                            text = if (showHodForm) "Close" else "+ Add HOD",
                            background = WebColors.Accent,
                            onClick = { showHodForm = !showHodForm }
                        )
                    }
                }
                val edit = editingHod
                if (edit != null) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFFF8E1),
                            border = BorderStroke(1.dp, Color(0xFFF1C48B))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Edit HOD — ${edit.name}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                FormField(
                                    value = edit.name,
                                    onValueChange = { editingHod = edit.copy(name = it) },
                                    label = "Full Name"
                                )
                                FormField(
                                    value = edit.email,
                                    onValueChange = { editingHod = edit.copy(email = it) },
                                    label = "Email"
                                )
                                FormField(
                                    value = edit.password,
                                    onValueChange = { editingHod = edit.copy(password = it) },
                                    label = "New Password (optional)",
                                    password = true
                                )
                                FieldSelect(
                                    label = "Branch",
                                    options = WebOptions.BRANCHES,
                                    value = edit.branch,
                                    onSelect = { editingHod = edit.copy(branch = it) }
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    WideButton("Save Changes", WebColors.Success) { saveHod() }
                                    WideButton("Cancel", Color(0xFF95A5A6)) { editingHod = null }
                                }
                            }
                        }
                    }
                }
                if (showHodForm) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFF8F9FA)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FormField(hodName, { hodName = it }, "Full Name")
                                FormField(hodEmail, { hodEmail = it }, "Email")
                                FormField(hodPassword, { hodPassword = it }, "Password", password = true)
                                FieldSelect(
                                    label = "Branch",
                                    options = WebOptions.BRANCHES,
                                    value = hodBranch,
                                    onSelect = { hodBranch = it }
                                )
                                WideButton("Create HOD", WebColors.Success) { createHod() }
                            }
                        }
                    }
                }
                item {
                    TableHeaderRow(listOf("HOD", "Email", "Department", "Role", ""))
                }
                if (hods.isEmpty()) {
                    item { EmptyRowText() }
                } else {
                    items(hods, key = { it.id ?: it.hashCode() }) { hod ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Cell(hod.fullName ?: "—", bold = true)
                                Cell(hod.email ?: "—")
                                Cell(hod.branch ?: "—")
                                Cell(hod.role?.replaceFirstChar { it.uppercase() } ?: "—")
                                ActionCell {
                                    SmallButton("Edit", WebColors.Info) {
                                        editingHod = HodEdit(
                                            id = hod.id ?: -1,
                                            name = hod.fullName ?: "",
                                            email = hod.email ?: "",
                                            branch = hod.branch ?: "ISE",
                                            password = ""
                                        )
                                    }
                                }
                            }
                            DividerThin()
                        }
                    }
                }
            }

            if (tab == "teachers") {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Teachers",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        WideButton(
                            text = if (showTeacherForm) "Close" else "+ Add Teacher",
                            background = WebColors.Accent,
                            onClick = { showTeacherForm = !showTeacherForm }
                        )
                    }
                }
                if (showTeacherForm) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFF8F9FA)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FormField(teacherEmployeeId, { teacherEmployeeId = it }, "Employee ID")
                                FormField(teacherName, { teacherName = it }, "Full Name")
                                FormField(teacherEmail, { teacherEmail = it }, "Email")
                                FieldSelect(
                                    label = "Branch",
                                    options = WebOptions.BRANCHES,
                                    value = teacherBranch,
                                    onSelect = { teacherBranch = it }
                                )
                                WideButton("Add Teacher", WebColors.Success) { createTeacher() }
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = "Select a department to view teachers:",
                        style = MaterialTheme.typography.bodySmall,
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
                        WebOptions.BRANCHES.forEach { branch ->
                            SmallButton(
                                text = branch,
                                background = if (selectedBranch == branch) WebColors.Primary else Color(0xFFE0E0E0),
                                contentColor = if (selectedBranch == branch) Color.White else Color(0xFF333333),
                                onClick = { loadBranch(branch) }
                            )
                        }
                    }
                }
                if (loading) {
                    item {
                        Text(
                            text = "Loading...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
                        )
                    }
                } else if (selectedBranch.isNotEmpty()) {
                    item {
                        TableHeaderRow(listOf("Employee ID", "Name", "Email", "Branch", ""))
                    }
                    if (teachers.isEmpty()) {
                        item { EmptyRowText("No teachers in $selectedBranch.") }
                    } else {
                        items(teachers, key = { it.id ?: it.hashCode() }) { teacher ->
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Cell(teacher.employeeId ?: "—")
                                    Cell(teacher.name ?: "—", bold = true)
                                    Cell(teacher.email ?: "—")
                                    Cell(teacher.branch ?: "—")
                                    ActionCell {
                                        SmallButton("Delete", WebColors.Danger) {
                                            pendingTeacherDelete = teacher
                                        }
                                    }
                                }
                                DividerThin()
                            }
                        }
                    }
                }
            }

            if (tab == "students") {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Students",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        WideButton(
                            text = if (showStudentForm) "Close" else "+ Add Student",
                            background = WebColors.Accent,
                            onClick = { showStudentForm = !showStudentForm }
                        )
                    }
                }
                if (showStudentForm) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFF8F9FA)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FormField(studentId, { studentId = it }, "Student ID")
                                FormField(studentName, { studentName = it }, "Full Name")
                                FormField(studentRoll, { studentRoll = it }, "Roll No")
                                FormField(studentEmail, { studentEmail = it }, "Email")
                                FieldSelect(
                                    label = "Branch",
                                    options = WebOptions.BRANCHES,
                                    value = studentBranch,
                                    onSelect = { studentBranch = it }
                                )
                                WideButton("Add Student", WebColors.Success) { createStudent() }
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = "Select a department to view students:",
                        style = MaterialTheme.typography.bodySmall,
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
                        WebOptions.BRANCHES.forEach { branch ->
                            SmallButton(
                                text = branch,
                                background = if (selectedBranch == branch) WebColors.Primary else Color(0xFFE0E0E0),
                                contentColor = if (selectedBranch == branch) Color.White else Color(0xFF333333),
                                onClick = { loadBranch(branch) }
                            )
                        }
                    }
                }
                if (loading) {
                    item {
                        Text(
                            text = "Loading...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
                        )
                    }
                } else if (selectedBranch.isNotEmpty()) {
                    item {
                        TableHeaderRow(listOf("Student ID", "Name", "Roll No", "Semester", "Section", ""))
                    }
                    if (students.isEmpty()) {
                        item { EmptyRowText("No students in $selectedBranch.") }
                    } else {
                        items(students, key = { it.id ?: it.hashCode() }) { student ->
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Cell(student.studentId ?: "—")
                                    Cell(student.name ?: "—", bold = true)
                                    Cell(student.rollNumber ?: "—")
                                    Cell(student.semester ?: "—")
                                    Cell(student.section ?: "—")
                                    ActionCell {
                                        SmallButton("Delete", WebColors.Danger) {
                                            pendingStudentDelete = student
                                        }
                                    }
                                }
                                DividerThin()
                            }
                        }
                    }
                }
            }
        }

        pendingTeacherDelete?.let { teacher ->
            ConfirmDialog(
                title = "Delete this teacher?",
                message = "This removes ${teacher.name ?: "this teacher"} from ${
                    selectedBranch.ifBlank { teacher.branch ?: "the college" }
                }. This cannot be undone.",
                onConfirm = { confirmDeleteTeacher() },
                onDismiss = { pendingTeacherDelete = null }
            )
        }
        pendingStudentDelete?.let { student ->
            ConfirmDialog(
                title = "Delete this student?",
                message = "This removes ${student.name ?: "this student"} from ${
                    selectedBranch.ifBlank { "the college" }
                }. This cannot be undone.",
                onConfirm = { confirmDeleteStudent() },
                onDismiss = { pendingStudentDelete = null }
            )
        }
    }
}
