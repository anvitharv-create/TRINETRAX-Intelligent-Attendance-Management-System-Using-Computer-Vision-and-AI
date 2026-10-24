package com.trinetrax.attendance.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs mirror the FastAPI response shapes exactly.
 * Unknown JSON keys are ignored by the shared Json config, and every field
 * that the backend treats as optional is nullable so a missing key can never
 * crash a screen.
 */

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    val user: UserDto
)

@Serializable
data class UserDto(
    val id: Int? = null,
    val username: String? = null,
    val email: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val role: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    val section: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("profile_photo") val profilePhoto: String? = null,
    @SerialName("student_id") val studentId: String? = null,
    @SerialName("employee_id") val employeeId: String? = null
)

@Serializable
data class StudentDto(
    val id: Int? = null,
    @SerialName("student_id") val studentId: String? = null,
    @SerialName("user_id") val userId: Int? = null,
    val name: String? = null,
    @SerialName("roll_number") val rollNumber: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    val section: String? = null,
    val email: String? = null,
    @SerialName("profile_photo_path") val profilePhotoPath: String? = null,
    @SerialName("face_embedding") val faceEmbedding: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("parent_email") val parentEmail: String? = null,
    @SerialName("parent_email_verified") val parentEmailVerified: Boolean? = null,
    @SerialName("parent_whatsapp") val parentWhatsapp: String? = null,
    @SerialName("parent_whatsapp_verified") val parentWhatsappVerified: Boolean? = null
)

@Serializable
data class TeacherDto(
    val id: Int? = null,
    @SerialName("user_id") val userId: Int? = null,
    @SerialName("employee_id") val employeeId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    val section: String? = null,
    @SerialName("profile_photo_path") val profilePhotoPath: String? = null
)

@Serializable
data class SubjectDto(
    val id: Int? = null,
    val name: String? = null,
    val code: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    @SerialName("teacher_id") val teacherId: Int? = null
)

@Serializable
data class AttendanceRecordDto(
    val id: Int? = null,
    @SerialName("student_id") val studentId: Int? = null,
    @SerialName("subject_id") val subjectId: Int? = null,
    val date: String? = null,
    val status: String? = null,
    val confidence: Double? = null,
    @SerialName("marked_by") val markedBy: Int? = null,
    @SerialName("marked_at") val markedAt: String? = null
)

@Serializable
data class NotificationDto(
    val id: Int? = null,
    @SerialName("user_id") val userId: Int? = null,
    val title: String? = null,
    val message: String? = null,
    val type: String? = null,
    val read: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class TeacherSubjectStat(
    @SerialName("subject_id") val subjectId: Int? = null,
    val name: String? = null,
    val code: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    @SerialName("students_in_class") val studentsInClass: Int? = null,
    @SerialName("present_today") val presentToday: Int? = null,
    @SerialName("absent_today") val absentToday: Int? = null,
    @SerialName("marked_today") val markedToday: Int? = null
)

@Serializable
data class TodayStats(
    @SerialName("total_present") val totalPresent: Int? = null,
    @SerialName("total_marked") val totalMarked: Int? = null,
    @SerialName("total_class_size") val totalClassSize: Int? = null
)

@Serializable
data class TeacherDashboardDto(
    @SerialName("teacher_id") val teacherId: Int? = null,
    @SerialName("teacher_name") val teacherName: String? = null,
    val branch: String? = null,
    @SerialName("total_subjects") val totalSubjects: Int? = null,
    val subjects: List<TeacherSubjectStat> = emptyList(),
    val today: TodayStats? = null
)

@Serializable
data class BranchStatDto(
    val branch: String? = null,
    @SerialName("total_students") val totalStudents: Int? = null,
    val students: Int? = null,
    val teachers: Int? = null,
    val subjects: Int? = null,
    @SerialName("overall_attendance_pct") val overallAttendancePct: Double? = null,
    @SerialName("attendance_pct") val attendancePct: Double? = null
)

@Serializable
data class AnalyticsOverallDto(
    @SerialName("total_students") val totalStudents: Int? = null,
    @SerialName("total_teachers") val totalTeachers: Int? = null,
    @SerialName("total_subjects") val totalSubjects: Int? = null,
    @SerialName("total_classes") val totalClasses: Int? = null,
    @SerialName("overall_attendance_pct") val overallAttendancePct: Double? = null,
    @SerialName("present_today") val presentToday: Int? = null,
    @SerialName("absent_today") val absentToday: Int? = null,
    @SerialName("below_threshold_students") val belowThresholdStudents: Int? = null,
    @SerialName("below_threshold_list") val belowThresholdList: List<LowAttendanceStudentDto> = emptyList(),
    @SerialName("branch_stats") val branchStats: List<BranchStatDto> = emptyList()
)

@Serializable
data class LowAttendanceStudentDto(
    @SerialName("student_id") val studentId: String? = null,
    val name: String? = null,
    @SerialName("roll_number") val rollNumber: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    val section: String? = null,
    val percentage: Double? = null
)

@Serializable
data class TrendPointDto(
    val date: String? = null,
    val pct: Double? = null
)

@Serializable
data class AdminOverviewDto(
    @SerialName("total_students") val totalStudents: Int? = null,
    @SerialName("total_teachers") val totalTeachers: Int? = null,
    @SerialName("total_departments") val totalDepartments: Int? = null,
    @SerialName("overall_attendance_pct") val overallAttendancePct: Double? = null,
    @SerialName("present_today") val presentToday: Int? = null,
    @SerialName("absent_today") val absentToday: Int? = null,
    @SerialName("low_attendance_count") val lowAttendanceCount: Int? = null,
    @SerialName("low_attendance_students") val lowAttendanceStudents: List<LowAttendanceStudentDto> = emptyList(),
    @SerialName("branch_stats") val branchStats: List<BranchStatDto> = emptyList(),
    @SerialName("monthly_trend") val monthlyTrend: List<TrendPointDto> = emptyList()
)

/* ---------- Requests ---------- */

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RegisterFaceRequest(
    @SerialName("student_id") val studentId: String,
    val name: String? = null,
    @SerialName("image_base64") val imageBase64: String,
    /** Guided multi-angle captures; the server stores them together or not at all. */
    val images: List<FaceAngleDto>? = null
)

@Serializable
data class AnalyzeImageRequest(
    @SerialName("image_base64") val imageBase64: String,
    @SerialName("subject_id") val subjectId: Int? = null,
    val section: String? = null
)

/** One row of the manual-marking payload accepted by POST /api/attendance/capture. */
@Serializable
data class StudentResultPayload(
    @SerialName("student_id") val studentId: Int,
    val status: String,
    val confidence: Double
)

@Serializable
data class CaptureRequest(
    @SerialName("subject_id") val subjectId: Int,
    val date: String,
    @SerialName("image_base64") val imageBase64: String? = null,
    @SerialName("use_recognition") val useRecognition: Boolean = true,
    @SerialName("student_results") val studentResults: List<StudentResultPayload>? = null
)

@Serializable
data class CaptureResultDto(
    val message: String? = null,
    val present: Int? = null,
    val absent: Int? = null,
    val unknown: Int? = null,
    val saved: Int? = null
)

@Serializable
data class RegisterFaceResultDto(
    val status: String? = null,
    @SerialName("student_id") val studentId: String? = null,
    val name: String? = null,
    val registered: Int? = null,
    /** Only sent by the guided multi-angle endpoint. */
    val angles: Int? = null,
    val templates: List<FaceTemplateDto> = emptyList()
)

/** One stored angle of a student's face (pose + embedding size). */
@Serializable
data class FaceTemplateDto(
    val pose: String? = null,
    val dimensions: Int? = null
)

/** One guided capture: the pose and its base64 image. */
@Serializable
data class FaceAngleDto(
    val pose: String,
    @SerialName("image_base64") val imageBase64: String
)

/* ---------- HOD subject & semester comparison report ---------- */

@Serializable
data class HodComparisonSummaryDto(
    @SerialName("total_subjects") val totalSubjects: Int? = null,
    @SerialName("subjects_with_records") val subjectsWithRecords: Int? = null,
    @SerialName("semesters_represented") val semestersRepresented: Int? = null,
    @SerialName("attendance_pct") val attendancePct: Double? = null,
    @SerialName("subjects_below_threshold") val subjectsBelowThreshold: Int? = null,
    @SerialName("students") val students: Int? = null,
    @SerialName("total_records") val totalRecords: Int? = null,
    @SerialName("present_records") val presentRecords: Int? = null,
    @SerialName("threshold_pct") val thresholdPct: Double? = null
)

@Serializable
data class HodSubjectRowDto(
    @SerialName("subject_id") val subjectId: Int? = null,
    val code: String? = null,
    val name: String? = null,
    val semester: String? = null,
    val sections: List<String> = emptyList(),
    val records: Int? = null,
    val present: Int? = null,
    val absent: Int? = null,
    val pct: Double? = null,
    val status: String? = null
)

@Serializable
data class HodSemesterRowDto(
    val semester: String? = null,
    val subjects: Int? = null,
    val records: Int? = null,
    val present: Int? = null,
    val pct: Double? = null
)

@Serializable
data class HodDistributionRowDto(
    val bucket: String? = null,
    val count: Int? = null
)

@Serializable
data class HodComparisonDto(
    val branch: String? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
    val summary: HodComparisonSummaryDto? = null,
    @SerialName("subject_rows") val subjectRows: List<HodSubjectRowDto> = emptyList(),
    @SerialName("semester_rows") val semesterRows: List<HodSemesterRowDto> = emptyList(),
    val distribution: List<HodDistributionRowDto> = emptyList(),
    val insights: List<String> = emptyList()
)

@Serializable
data class RecognitionStatusDto(
    val real: Boolean? = null,
    val method: String? = null,
    val registered: Int? = null,
    val threshold: Double? = null
)

@Serializable
data class HealthDto(
    val status: String? = null,
    val message: String? = null
)

/* ---------- Student report (backend/student_report.py) ---------- */

@Serializable
data class ReportSummaryDto(
    @SerialName("total_marked") val totalMarked: Int? = null,
    val present: Int? = null,
    val absent: Int? = null,
    val other: Int? = null,
    val pct: Double? = null,
    val eligible: Boolean? = null,
    @SerialName("min_attendance_pct") val minAttendancePct: Double? = null,
    @SerialName("shortage_pct") val shortagePct: Double? = null,
    @SerialName("classes_to_attend") val classesToAttend: Int? = null
)

@Serializable
data class ReportRecordDto(
    val id: Int? = null,
    val date: String? = null,
    @SerialName("subject_id") val subjectId: Int? = null,
    val subject: String? = null,
    @SerialName("subject_code") val subjectCode: String? = null,
    val status: String? = null
)

@Serializable
data class ReportStudentProfile(
    val id: Int? = null,
    val name: String? = null,
    val usn: String? = null,
    @SerialName("roll_number") val rollNumber: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    val section: String? = null,
    val email: String? = null,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val role: String? = null
)

@Serializable
data class StudentReportDto(
    @SerialName("generated_at") val generatedAt: String? = null,
    val student: ReportStudentProfile? = null,
    val scope: String? = null,
    @SerialName("min_attendance_pct") val minAttendancePct: Double? = null,
    val summary: ReportSummaryDto? = null,
    val records: List<ReportRecordDto> = emptyList()
)
