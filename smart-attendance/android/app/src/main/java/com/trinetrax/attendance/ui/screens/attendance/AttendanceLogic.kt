package com.trinetrax.attendance.ui.screens.attendance

import com.trinetrax.attendance.data.model.AttendanceRecordDto
import com.trinetrax.attendance.data.model.SubjectDto

/**
 * Same calculation rules as the existing web app:
 *   percentage = present / total * 100
 *   low        = percentage > 0 && percentage < 75
 * Nothing here invents numbers - both inputs come from backend records.
 */

data class SubjectAttendance(
    val subject: SubjectDto,
    val total: Int,
    val present: Int,
    val absent: Int,
    val pct: Double
) {
    val isLow: Boolean get() = total > 0 && pct < 75
}

data class AttendanceSummary(
    val total: Int,
    val present: Int,
    val absent: Int,
    val pct: Double,
    val subjects: List<SubjectAttendance>
) {
    val lowSubjects: List<SubjectAttendance> get() = subjects.filter { it.isLow }
}

fun computeAttendanceSummary(
    records: List<AttendanceRecordDto>,
    subjects: List<SubjectDto>
): AttendanceSummary {
    val present = records.count { it.status == "present" }
    val absent = records.count { it.status == "absent" }
    val pct = if (records.isEmpty()) 0.0 else present.toDouble() / records.size * 100.0

    val subjectStats = subjects
        .filter { it.id != null }
        .map { subject ->
            val own = records.filter { it.subjectId == subject.id }
            val p = own.count { it.status == "present" }
            val a = own.count { it.status == "absent" }
            SubjectAttendance(
                subject = subject,
                total = own.size,
                present = p,
                absent = a,
                pct = if (own.isEmpty()) 0.0 else p.toDouble() / own.size * 100.0
            )
        }
        // Weakest subject first, matching the web ordering.
        .sortedBy { it.pct }

    return AttendanceSummary(
        total = records.size,
        present = present,
        absent = absent,
        pct = pct,
        subjects = subjectStats
    )
}

fun subjectLabel(subject: SubjectDto): String {
    val code = subject.code ?: return subject.name ?: "Subject"
    val name = subject.name ?: return code
    return "$name ($code)"
}
