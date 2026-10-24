package com.trinetrax.attendance.ui.screens.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trinetrax.attendance.data.model.RecognitionStatusDto
import com.trinetrax.attendance.data.model.RegisterFaceResultDto
import com.trinetrax.attendance.data.model.StudentDto
import com.trinetrax.attendance.ui.components.ErrorState
import com.trinetrax.attendance.ui.components.KeyValueRow
import com.trinetrax.attendance.ui.components.LoadingView
import com.trinetrax.attendance.ui.components.SearchField
import com.trinetrax.attendance.ui.components.SectionCard
import com.trinetrax.attendance.ui.components.StatusPill
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.ui.theme.SuccessGreen
import com.trinetrax.attendance.ui.theme.WarningAmber
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.launch

private data class PoseStep(val pose: String, val label: String, val instruction: String)

private val POSE_STEPS = listOf(
    PoseStep("front", "Front", "Look straight at the camera with a neutral expression."),
    PoseStep("left", "Slight left", "Turn the head slightly to the left (about 20 degrees)."),
    PoseStep("right", "Slight right", "Turn the head slightly to the right (about 20 degrees).")
)

/**
 * Guided three-angle face registration.
 *
 * Each step is a separate SFace template of the same student. The server
 * validates all three angles before writing anything, so a partial registration
 * is never reported as a success, and only the selected student is touched.
 */
@Composable
fun FaceRegistrationScreen(vm: SessionViewModel) {
    val user = vm.user.value
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var students by remember { mutableStateOf<List<StudentDto>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<StudentDto?>(null) }
    var status by remember { mutableStateOf<RecognitionStatusDto?>(null) }

    var step by remember { mutableStateOf(0) }
    var shots by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RegisterFaceResultDto?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        status = vm.repo.recognitionStatus().getOrNull()
        val branch = user?.branch
        val list = vm.repo.students(branch = branch)
            .getOrElse { error = vm.repo.humanError(it); loading = false; return@LaunchedEffect }
        students = list
        loading = false
    }

    val filtered = remember(students, query) {
        if (query.isBlank()) students else students.filter {
            listOfNotNull(it.name, it.studentId, it.rollNumber)
                .any { field -> field.contains(query, ignoreCase = true) }
        }.take(50)
    }

    val current = POSE_STEPS[step]
    val currentShot = shots[current.pose]
    val capturedCount = POSE_STEPS.count { shots[it.pose] != null }
    val allCaptured = capturedCount == POSE_STEPS.size

    fun resetFlow() {
        step = 0
        shots = emptyMap()
        result = null
        saveError = null
    }

    if (loading) LoadingView("Loading students…") else if (error != null) ErrorState(error!!)
    else Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Register a face", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Guided capture: front, slight left and slight right. The server rejects any photo without exactly one clear face and stores all three together.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (status != null) {
            SectionCard("Recognition backend") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusPill(
                        if (status!!.real == true) "Active" else "Unavailable",
                        (if (status!!.real == true) SuccessGreen else WarningAmber).copy(alpha = 0.14f),
                        if (status!!.real == true) SuccessGreen else WarningAmber
                    )
                }
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Method", status!!.method ?: "—")
                KeyValueRow("Registered faces", "${status!!.registered ?: 0}")
            }
        }

        SearchField(value = query, onValueChange = { query = it }, hint = "Search student name or USN")
        Text("${filtered.size} students", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        filtered.take(30).forEach { student ->
            val isSelected = selected?.id == student.id
            SectionCard(
                title = student.name ?: "Student",
                action = {
                    if (isSelected) {
                        StatusPill("Selected", SuccessGreen.copy(alpha = 0.14f), SuccessGreen)
                    } else {
                        OutlinedButton(onClick = { selected = student; resetFlow() }) {
                            Text("Select")
                        }
                    }
                }
            ) {
                KeyValueRow("USN", student.studentId ?: "—")
                KeyValueRow("Class", listOfNotNull(student.branch, student.semester, student.section).joinToString(" · "))
                KeyValueRow("Face registered", if (student.faceEmbedding.isNullOrBlank()) "No" else "Yes")
            }
        }

        if (selected != null) {
            // progress for all three angles
            SectionCard("Progress: $capturedCount of ${POSE_STEPS.size} angles") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    POSE_STEPS.forEachIndexed { index, pose ->
                        val done = shots[pose.pose] != null
                        val isCurrent = index == step
                        Column(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .background(
                                        color = when {
                                            done -> SuccessGreen
                                            isCurrent -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        shape = RoundedCornerShape(3.dp)
                                    )
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${index + 1}. ${pose.label}" + if (done) " ✓" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedText
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Step ${step + 1} of ${POSE_STEPS.size} — ${current.label}",
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(current.instruction, style = MaterialTheme.typography.bodyMedium, color = MutedText)
            }

            val bitmap = currentShot
            if (bitmap == null) {
                CameraSection(
                    onImageCaptured = {
                        shots = shots + (current.pose to it)
                        result = null
                        saveError = null
                    },
                    facingFront = true
                )
                Spacer(Modifier.height(220.dp))
            } else {
                SectionCard("${current.label} photo captured") {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "${current.label} capture",
                        modifier = Modifier.fillMaxWidth().height(240.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { shots = shots - current.pose }) { Text("Retake") }
                        if (step < POSE_STEPS.lastIndex) {
                            Button(onClick = { step = step + 1 }) {
                                Text("Next angle →", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Button(
                                enabled = allCaptured && !saving,
                                onClick = {
                                    val student = selected ?: return@Button
                                    saving = true
                                    saveError = null
                                    result = null
                                    vm.requestFlowScope().launch {
                                        val encoded = POSE_STEPS.associate { stepInfo ->
                                            stepInfo.pose to bitmapToBase64(shots[stepInfo.pose]!!)
                                        }
                                        val r = vm.repo.registerFaceMulti(
                                            studentId = student.studentId ?: return@launch,
                                            name = student.name,
                                            shots = encoded
                                        )
                                        saving = false
                                        r.onSuccess { result = it }
                                            .onFailure { saveError = vm.repo.humanError(it) }
                                    }
                                }
                            ) {
                                Text(
                                    if (saving) "Registering…" else "Register face ($capturedCount/3)",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    if (step > 0) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { step = step - 1 }) { Text("← Previous angle") }
                    }
                }
            }
        }

        result?.let { res ->
            SectionCard("Result") {
                Text(
                    "Face registered for ${res.name ?: selected?.name ?: "student"}",
                    fontWeight = FontWeight.SemiBold,
                    color = SuccessGreen
                )
                Spacer(Modifier.height(6.dp))
                KeyValueRow("Angles stored", "${res.angles ?: POSE_STEPS.size}")
                if (res.templates.isNotEmpty()) {
                    KeyValueRow(
                        "Stored poses",
                        res.templates.mapNotNull { it.pose }.joinToString(", ")
                    )
                }
                KeyValueRow("Registered faces on server", "${res.registered ?: 0}")
            }
        }

        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}