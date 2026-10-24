package com.trinetrax.attendance.ui.screens.profile

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trinetrax.attendance.core.NetworkModule
import com.trinetrax.attendance.data.model.RecognitionStatusDto
import com.trinetrax.attendance.data.model.StudentDto
import com.trinetrax.attendance.ui.components.BannerCard
import com.trinetrax.attendance.ui.components.FieldSelect
import com.trinetrax.attendance.ui.components.KeyValueRow
import com.trinetrax.attendance.ui.components.SectionCard
import com.trinetrax.attendance.ui.components.StatCard
import com.trinetrax.attendance.ui.nav.Routes
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ProfileScreen(
    vm: SessionViewModel,
    onLoggedOut: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val user = vm.user.value
    val role = user?.role ?: ""

    var myStudent by remember { mutableStateOf<StudentDto?>(null) }
    var faceStatus by remember { mutableStateOf<RecognitionStatusDto?>(null) }
    var editing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageTone by remember { mutableStateOf("success") }

    var name by remember(user?.id) { mutableStateOf(user?.fullName ?: "") }
    var email by remember(user?.id) { mutableStateOf(user?.email ?: "") }
    var password by remember(user?.id) { mutableStateOf("") }
    var branch by remember(user?.id) { mutableStateOf(user?.branch ?: "") }
    var semester by remember(user?.id) { mutableStateOf(user?.semester ?: "") }
    var section by remember(user?.id) { mutableStateOf(user?.section ?: "") }

    androidx.compose.runtime.LaunchedEffect(user?.id) {
        vm.repo.recognitionStatus().onSuccess { faceStatus = it }
        if (role == "student" && !user?.studentId.isNullOrBlank()) {
            vm.repo.students(user?.branch, user?.semester, user?.section)
                .onSuccess { list ->
                    myStudent = list.find { it.studentId == user?.studentId }
                    myStudent?.let { me ->
                        if (branch.isBlank()) branch = me.branch ?: ""
                        if (semester.isBlank()) semester = me.semester ?: ""
                        if (section.isBlank()) section = me.section ?: ""
                    }
                }
        }
    }

    val displayName = user?.fullName ?: myStudent?.name ?: "User"
    val idLabel = when {
        !user?.studentId.isNullOrBlank() -> "Student ID: ${user?.studentId}"
        !user?.employeeId.isNullOrBlank() -> "Employee ID: ${user?.employeeId}"
        else -> ""
    }
    val loginUsername = user?.username ?: user?.studentId ?: user?.email ?: "—"
    val photoBitmap = remember(user?.profilePhoto) {
        runCatching {
            val dataUrl = user?.profilePhoto
            if (dataUrl.isNullOrBlank()) null
            else {
                val base64 = dataUrl.substringAfter(",", dataUrl)
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                message = "Failed to upload photo."
                messageTone = "danger"
            } else if (bytes.size > 2 * 1024 * 1024) {
                message = "Image must be under 2MB."
                messageTone = "danger"
            } else {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val dataUrl = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                vm.requestFlowScope().launch {
                    message = null
                    vm.repo.uploadPhoto(dataUrl).onSuccess {
                        vm.repo.refreshMe()
                        message = "Profile photo updated."
                        messageTone = "success"
                    }.onFailure {
                        message = "Failed to upload photo."
                        messageTone = "danger"
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
            Text("My Profile", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
        }

        message?.let { msg ->
            item { BannerCard(messageTone, msg) }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = {
                    editing = !editing
                    message = null
                    if (editing) {
                        name = user?.fullName ?: ""
                        email = user?.email ?: ""
                        password = ""
                        branch = user?.branch ?: myStudent?.branch ?: ""
                        semester = user?.semester ?: myStudent?.semester ?: ""
                        section = user?.section ?: myStudent?.section ?: ""
                    }
                }) {
                    Text(if (editing) "Cancel Edit" else "Edit Profile")
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Color(0xFFDEE2E6)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        if (photoBitmap != null) {
                            Image(
                                bitmap = photoBitmap.asImageBitmap(),
                                contentDescription = "Profile",
                                modifier = Modifier
                                    .size(96.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    displayName.take(1).uppercase(Locale.ROOT),
                                    color = Color.White,
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF0277BD),
                            border = BorderStroke(2.dp, Color.White),
                            onClick = { photoPicker.launch(arrayOf("image/*")) }
                        ) {
                            Text(
                                "📷",
                                modifier = Modifier.padding(6.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(displayName, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text(
                        role.replaceFirstChar { it.uppercase(Locale.ROOT) },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF666666)
                    )
                    Text(user?.email ?: "—", style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888))
                    if (idLabel.isNotEmpty()) {
                        Text(idLabel, style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF888888))
                    }
                    Text("Login ID: $loginUsername", style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888))
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Branch", myStudent?.branch ?: user?.branch ?: "—",
                            Modifier.weight(1f), accent = MaterialTheme.colorScheme.primary)
                        StatCard("Semester", myStudent?.semester ?: user?.semester ?: "—",
                            Modifier.weight(1f), accent = Color(0xFF0277BD))
                        StatCard("Section", myStudent?.section ?: user?.section ?: "—",
                            Modifier.weight(1f), accent = Color(0xFF27AE60))
                    }
                }
            }
        }

        if (role == "student" && !(user?.studentId.isNullOrBlank())) {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFDEE2E6)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Face Recognition", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        val fs = faceStatus
                        if (fs?.real == true) {
                            BannerCard(
                                "success",
                                "Real face recognition is active (${fs.method}). " +
                                    "There ${if (fs.registered == 1) "is" else "are"} " +
                                    "${fs.registered} registered face(s)."
                            )
                        } else {
                            BannerCard(
                                "warning",
                                "Real face recognition backend is not available — results will be simulated."
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Register your face so the AI attendance camera can recognize you " +
                                "automatically in classroom photos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF666666)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { onNavigate(Routes.REGISTER_FACE) }) {
                            Text("Register My Face")
                        }
                    }
                }
            }
        }

        item {
            if (editing) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFDEE2E6)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Edit Profile", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        FieldLabel("Full name")
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        FieldLabel("Email")
                        OutlinedTextField(
                            value = email, onValueChange = { email = it },
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        FieldLabel("New password (leave blank to keep current)")
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = { Text("---") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (role != "hod") {
                            FieldLabel("Branch")
                            FieldSelect(
                                label = "Branch",
                                options = com.trinetrax.attendance.ui.components.WebOptions.BRANCHES,
                                value = branch,
                                onSelect = { branch = it }
                            )
                            FieldLabel("Semester")
                            FieldSelect(
                                label = "Semester",
                                options = com.trinetrax.attendance.ui.components.WebOptions.SEMESTERS,
                                value = semester,
                                onSelect = { semester = it }
                            )
                            FieldLabel("Section")
                            FieldSelect(
                                label = "Section",
                                options = com.trinetrax.attendance.ui.components.WebOptions.SECTIONS,
                                value = section,
                                onSelect = { section = it }
                            )
                        }
                        OutlinedButton(
                            enabled = !saving,
                            onClick = {
                                saving = true
                                message = null
                                val fields = buildMap<String, String?> {
                                    put("full_name", name.ifBlank { null })
                                    put("email", email.ifBlank { null })
                                    if (password.isNotBlank()) put("password", password)
                                    if (role != "hod") {
                                        put("branch", branch.ifBlank { null })
                                        put("semester", semester.ifBlank { null })
                                        put("section", section.ifBlank { null })
                                    }
                                }
                                vm.requestFlowScope().launch {
                                    vm.repo.updateProfile(fields).onSuccess {
                                        saving = false
                                        editing = false
                                        message = "Profile updated successfully."
                                        messageTone = "success"
                                    }.onFailure {
                                        saving = false
                                        message = vm.repo.humanError(it)
                                        messageTone = "danger"
                                    }
                                }
                            }
                        ) {
                            Text(if (saving) "Saving..." else "Save Changes")
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFDEE2E6)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Account Information", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        RichRow("Role", role.replaceFirstChar { it.uppercase(Locale.ROOT) })
                        RichRow("Username / Login ID:", loginUsername)
                        RichRow("Email:", user?.email ?: "—")
                        RichRow("Branch:", myStudent?.branch ?: user?.branch ?: "—")
                        myStudent?.studentId?.let { RichRow("USN:", it) }
                        user?.employeeId?.takeIf { myStudent == null }
                            ?.let { RichRow("Employee ID:", it) }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Click Edit Profile to modify your info. Click the camera icon on " +
                                "your photo to upload a profile picture.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF666666)
                        )
                    }
                }
            }
        }

        if (role == "student") {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFDEE2E6)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Attendance Report", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Generate a complete report with your profile, attendance summary and history.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF666666)
                            )
                        }
                        OutlinedButton(onClick = { onNavigate(Routes.STUDENT_REPORT) }) {
                            Text("Generate Report")
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Server") {
                KeyValueRow("Backend", NetworkModule.BASE_URL)
                Text(
                    "This app reads and writes the same database as the web portal through the FastAPI backend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText
                )
            }
        }

        item {
            OutlinedButton(
                onClick = { vm.logout(onLoggedOut) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Outlined.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF555555)
    )
}

@Composable
private fun RichRow(label: String, value: String) {
    Row {
        Text("$label ", style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
