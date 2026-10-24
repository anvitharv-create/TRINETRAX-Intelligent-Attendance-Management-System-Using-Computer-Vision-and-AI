package com.trinetrax.attendance.ui.screens.dashboard

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trinetrax.attendance.data.model.AnalyticsOverallDto
import com.trinetrax.attendance.data.model.LowAttendanceStudentDto
import com.trinetrax.attendance.data.model.NotificationDto
import com.trinetrax.attendance.data.model.RecognitionStatusDto
import com.trinetrax.attendance.data.model.StudentDto
import com.trinetrax.attendance.data.model.TeacherDashboardDto
import com.trinetrax.attendance.ui.components.BannerCard
import com.trinetrax.attendance.ui.components.ErrorState
import com.trinetrax.attendance.ui.components.KeyValueRow
import com.trinetrax.attendance.ui.components.LoadingView
import com.trinetrax.attendance.ui.components.SectionCard
import com.trinetrax.attendance.ui.components.StatCard
import com.trinetrax.attendance.ui.nav.Routes
import com.trinetrax.attendance.ui.screens.attendance.AttendanceSummary
import com.trinetrax.attendance.ui.screens.attendance.computeAttendanceSummary
import com.trinetrax.attendance.ui.theme.DangerRed
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.ui.theme.SuccessGreen
import com.trinetrax.attendance.ui.theme.WarningAmber
import com.trinetrax.attendance.viewmodel.SessionViewModel
import java.util.Calendar

private fun pctColor(pct: Double) = when {
    pct <= 0.0 -> MutedText
    pct >= 75.0 -> SuccessGreen
    else -> WarningAmber
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 0..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

private fun barColor(pct: Double): Color = when {
    pct >= 75.0 -> Color(0xFF27AE60)
    pct > 0.0 -> Color(0xFFF39C12)
    else -> Color(0xFFD0D0D0)
}

/* ------------------------------------------------------------------ TEACHER */

private data class TeacherBundle(val data: TeacherDashboardDto)

@Composable
fun TeacherDashboardScreen(vm: SessionViewModel, onNavigate: (String) -> Unit) {
    val user = vm.user.value
    val bundle = produceState<Result<TeacherBundle>?>(initialValue = null, key1 = vm) {
        value = vm.repo.teacherDashboard().map { TeacherBundle(it) }
    }

    when {
        bundle.value == null -> LoadingView("Loading your dashboard...")
        bundle.value!!.isFailure -> ErrorState(
            vm.repo.humanError(bundle.value!!.exceptionOrNull()!!)
        )
        else -> {
            val data = bundle.value!!.getOrThrow().data
            val totalClassSize = data.today?.totalClassSize ?: 0
            val totalPresent = data.today?.totalPresent ?: 0
            val totalMarked = data.today?.totalMarked ?: 0
            val attendancePct =
                if (totalClassSize > 0) (totalPresent * 100 / totalClassSize) else 0

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Column {
                        Text(
                            "${greeting()}, ${data.teacherName ?: user?.fullName ?: "Teacher"}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Teacher · ${data.branch ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
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
                            "Subjects", "${data.totalSubjects ?: 0}",
                            Modifier.width(160.dp),
                            accent = Color(0xFF0277BD),
                            caption = "Assigned to you"
                        )
                        StatCard(
                            "Class Strength", "$totalClassSize",
                            Modifier.width(170.dp),
                            accent = Color(0xFF9B59B6),
                            caption = "Students across classes"
                        )
                        StatCard(
                            "Present Today", "$totalPresent",
                            Modifier.width(160.dp),
                            accent = Color(0xFF27AE60),
                            caption = "$attendancePct% attendance"
                        )
                        StatCard(
                            "Marked Today", "$totalMarked",
                            Modifier.width(160.dp),
                            accent = MaterialTheme.colorScheme.primary,
                            caption = "Records saved"
                        )
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF0FDF4),
                        border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Today's Attendance Summary",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF166534)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (totalMarked > 0) {
                                            "$totalPresent present out of $totalClassSize students ($attendancePct%)"
                                        } else {
                                            "No attendance marked yet today"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF555555)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF27AE60),
                                    onClick = { onNavigate(Routes.AI_WORKFLOW) }
                                ) {
                                    Text(
                                        "Take Attendance",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            if (data.subjects.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                data.subjects.forEach { s ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color.White,
                                        border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(
                                                s.code ?: "—",
                                                fontWeight = FontWeight.SemiBold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                "${s.name ?: "—"} · ${s.semester ?: "—"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MutedText
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text(
                                                    "${s.presentToday ?: 0} present",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF27AE60)
                                                )
                                                Text(
                                                    "${s.absentToday ?: 0} absent",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFFE74C3C)
                                                )
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                "${s.studentsInClass ?: 0} students · " +
                                                    if ((s.markedToday ?: 0) > 0) "Marked" else "Not marked",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF888888)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "My Subjects (${data.totalSubjects ?: 0})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (data.subjects.isEmpty()) {
                    item {
                        Text(
                            "No subjects are registered under your name yet.",
                            color = MutedText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    items(data.subjects, key = { it.subjectId ?: it.hashCode() }) { s ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        s.code ?: "—",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if ((s.markedToday ?: 0) > 0)
                                            Color(0xFFDCFCE7) else Color(0xFFFEF3C7)
                                    ) {
                                        Text(
                                            if ((s.markedToday ?: 0) > 0) "Marked" else "Pending",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if ((s.markedToday ?: 0) > 0)
                                                Color(0xFF166534) else Color(0xFF92400E)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(s.name ?: "—", fontWeight = FontWeight.SemiBold)
                                Text(
                                    s.semester ?: "—",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedText
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Class Size  ${s.studentsInClass ?: 0}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MutedText
                                    )
                                    Text(
                                        "Present  ${s.presentToday ?: 0}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = SuccessGreen
                                    )
                                    Text(
                                        "Absent  ${s.absentToday ?: 0}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DangerRed
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

/* ---------------------------------------------------------------------- HOD */

private data class HodBundle(val analytics: AnalyticsOverallDto)

@Composable
fun HodDashboardScreen(vm: SessionViewModel, onNavigate: (String) -> Unit) {
    val user = vm.user.value
    val bundle = produceState<Result<HodBundle>?>(initialValue = null, key1 = vm) {
        value = vm.repo.analyticsOverall().map { HodBundle(it) }
    }

    when {
        bundle.value == null -> LoadingView()
        bundle.value!!.isFailure -> ErrorState(vm.repo.humanError(bundle.value!!.exceptionOrNull()!!))
        else -> {
            val data = bundle.value!!.getOrThrow().analytics
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Column {
                        Text(
                            "${greeting()}, ${user?.fullName ?: "there"} 👋",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "You are signed in as " +
                                (user?.role?.replaceFirstChar { it.uppercase() } ?: "—") +
                                (user?.branch?.let { " ($it)" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
                        )
                    }
                }
                if (!user?.branch.isNullOrBlank()) {
                    item {
                        BannerCard("info", "Showing ${user?.branch} department data only.")
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
                            "Total Students", data.totalStudents?.toString() ?: "—",
                            Modifier.width(160.dp), accent = Color(0xFF3498DB)
                        )
                        StatCard(
                            "Total Teachers", data.totalTeachers?.toString() ?: "—",
                            Modifier.width(160.dp), accent = Color(0xFF27AE60)
                        )
                        StatCard(
                            "Total Subjects", data.totalSubjects?.toString() ?: "—",
                            Modifier.width(160.dp), accent = Color(0xFFE67E22)
                        )
                        StatCard(
                            "Classes Held", data.totalClasses?.toString() ?: "—",
                            Modifier.width(160.dp), accent = Color(0xFF9B59B6)
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
                            "Present Today", data.presentToday?.toString() ?: "—",
                            Modifier.width(160.dp), accent = Color(0xFF27AE60)
                        )
                        StatCard(
                            "Absent Today", data.absentToday?.toString() ?: "—",
                            Modifier.width(160.dp), accent = Color(0xFFE74C3C)
                        )
                        StatCard(
                            "Below 75% Attendance", data.belowThresholdStudents?.toString() ?: "—",
                            Modifier.width(190.dp), accent = Color(0xFFE67E22)
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onNavigate(Routes.CAPTURE) }) {
                            Text("Capture attendance")
                        }
                        OutlinedButton(onClick = { onNavigate(Routes.REGISTER_FACE) }) {
                            Text("Register face")
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ STUDENT */

private data class StudentBundle(
    val summary: AttendanceSummary,
    val studentId: String?,
    val notifications: List<NotificationDto>,
    val faceStatus: RecognitionStatusDto?
)

@Composable
fun StudentDashboardScreen(vm: SessionViewModel, onNavigate: (String) -> Unit) {
    val user = vm.user.value
    val bundle = produceState<Result<StudentBundle>?>(initialValue = null, key1 = user?.id) {
        val me = user ?: return@produceState
        val students = vm.repo.students(me.branch, me.semester, me.section).getOrElse {
            value = Result.failure(it); return@produceState
        }
        val mine: StudentDto? = students.find { it.studentId == me.studentId }
        if (mine?.id == null) {
            value = Result.failure(IllegalStateException("Your student record was not found."))
            return@produceState
        }
        val subjects = vm.repo.subjects(me.branch, me.semester).getOrElse {
            value = Result.failure(it); return@produceState
        }
        val records = vm.repo.studentAttendance(mine.id).getOrElse {
            value = Result.failure(it); return@produceState
        }
        val summary = computeAttendanceSummary(records, subjects)
        val notifications = me.id?.let { vm.repo.notifications(it).getOrNull() } ?: emptyList()
        val faceStatus = vm.repo.recognitionStatus().getOrNull()
        value = Result.success(StudentBundle(summary, me.studentId, notifications, faceStatus))
    }

    val serverReport = produceState<com.trinetrax.attendance.data.model.StudentReportDto?>(
        initialValue = null,
        key1 = user?.id
    ) {
        value = vm.repo.studentReport().getOrNull()
    }

    when {
        bundle.value == null -> LoadingView()
        bundle.value!!.isFailure -> ErrorState(vm.repo.humanError(bundle.value!!.exceptionOrNull()!!))
        else -> {
            val b = bundle.value!!.getOrThrow()
            val summary = b.summary
            val total = summary.total
            val present = summary.present
            val overallPct = if (total > 0) present * 100.0 / total else 0.0
            val overallLabel = "%.1f".format(overallPct)
            val subjectStats = summary.subjects.sortedBy { it.pct }
            val lowSubjects = subjectStats.filter { it.total > 0 && it.pct > 0.0 && it.pct < 75.0 }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Column {
                        Text(
                            "My Dashboard",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Welcome back, ${user?.fullName ?: "Student"}" +
                                (b.studentId?.let { " ($it)" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
                        )
                    }
                }
                if (lowSubjects.isNotEmpty()) {
                    item {
                        val names = lowSubjects.joinToString(", ") { it.subject.name ?: it.subject.code ?: "Subject" }
                        BannerCard(
                            "warning",
                            if (lowSubjects.size > 1) {
                                "⚠️ Your attendance is below 75% in these subjects: $names. " +
                                    "Please attend classes regularly to avoid penalties."
                            } else {
                                "⚠️ Your attendance is below 75% in $names. " +
                                    "Please attend classes regularly to avoid penalties."
                            }
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
                            "Overall Attendance", "$overallLabel%",
                            Modifier.width(170.dp),
                            accent = if (overallPct >= 75) Color(0xFF27AE60) else Color(0xFFF39C12)
                        )
                        StatCard(
                            "Classes Attended", "$present",
                            Modifier.width(150.dp), accent = Color(0xFF27AE60)
                        )
                        StatCard(
                            "Total Classes", "$total",
                            Modifier.width(140.dp), accent = Color(0xFF0277BD)
                        )
                        StatCard(
                            "Subjects", "${subjectStats.size}",
                            Modifier.width(130.dp), accent = Color(0xFF9B59B6)
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
                if (subjectStats.isEmpty()) {
                    item {
                        Text("No subject data yet.", color = MutedText,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    items(subjectStats, key = { "dash${it.subject.id ?: it.hashCode()}" }) { s ->
                        val pctNum = s.pct
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, Color(0xFFDEE2E6)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        s.subject.name ?: s.subject.code ?: "—",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "%.1f%% %s".format(pctNum, if (pctNum > 0 && pctNum < 75) "⚠️" else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            pctNum >= 75 -> Color(0xFF27AE60)
                                            pctNum > 0 -> Color(0xFFF39C12)
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
                                            .fillMaxWidth(fraction = pctNum.coerceIn(0.0, 100.0).toFloat() / 100f)
                                            .height(8.dp),
                                        color = barColor(pctNum)
                                    ) {}
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Notifications",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (b.notifications.isEmpty()) {
                            Text(
                                "No notifications.",
                                modifier = Modifier.padding(16.dp),
                                color = MutedText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Column {
                                b.notifications.forEach { n ->
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (n.read == true) Color.White else Color(0xFFF8F9FA)
                                            )
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            n.title ?: "—",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            n.message ?: "—",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF555555)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Face Recognition Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item {
                    if (b.faceStatus?.real == true) {
                        BannerCard(
                            "success",
                            "Your face can be recognized automatically. " +
                                "${b.faceStatus.registered ?: 0} face(s) registered."
                        )
                    } else {
                        BannerCard("warning", "Face recognition backend unavailable — results simulated.")
                    }
                }
                serverReport.value?.let { report ->
                    item {
                        SectionCard("Attendance report") {
                            val reportSummary = report.summary
                            if (reportSummary == null) {
                                Text("The server returned no summary.", color = MutedText)
                            } else {
                                Text(
                                    if (reportSummary.eligible == true) "Eligible"
                                    else if (reportSummary.eligible == false) "Not eligible"
                                    else "No records",
                                    color = if (reportSummary.eligible == true) SuccessGreen else WarningAmber,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(6.dp))
                                KeyValueRow("Attendance", reportSummary.pct?.let { "$it%" } ?: "—")
                                KeyValueRow("Present", "${reportSummary.present ?: 0}")
                                KeyValueRow("Absent", "${reportSummary.absent ?: 0}")
                                reportSummary.classesToAttend?.let {
                                    KeyValueRow("Classes to attend", "$it consecutive")
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Report scope: ${report.scope ?: "—"} · generated ${report.generatedAt ?: "—"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedText
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { onNavigate(Routes.STUDENT_REPORT) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Generate Report")
                    }
                }
            }
        }
    }
}

@Composable
fun LowAttendanceList(students: List<LowAttendanceStudentDto>) {
    SectionCard("Low attendance (below 75%)") {
        students.forEach { s ->
            KeyValueRow(
                s.name ?: s.studentId ?: "—",
                "${s.percentage ?: 0.0}%"
            )
        }
    }
}
