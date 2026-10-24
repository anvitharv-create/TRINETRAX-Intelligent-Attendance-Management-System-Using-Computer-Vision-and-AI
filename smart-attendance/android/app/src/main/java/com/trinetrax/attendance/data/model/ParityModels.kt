package com.trinetrax.attendance.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ============ DTOs for full website feature parity ============ */

@Serializable
data class CheckUserDto(
    val role: String? = null,
    val branch: String? = null,
    val registered: Int? = null,
    val users: List<CheckUserItem> = emptyList()
)

@Serializable
data class CheckUserItem(
    val id: Int? = null,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val branch: String? = null,
    @SerialName("employee_id") val employeeId: String? = null
)

@Serializable
data class RegisterHodRequest(val branch: String)

@Serializable
data class RegisterTeacherRequest(val branch: String, val name: String)

@Serializable
data class RegisterStudentRequest(
    val name: String,
    val branch: String,
    val semester: String,
    val section: String
)

@Serializable
data class RegisterStudentRow(
    val name: String,
    @SerialName("student_id") val studentId: String? = null,
    val email: String? = null
)

@Serializable
data class RegisterBulkRequest(
    val branch: String,
    val semester: String,
    val section: String,
    val rows: List<RegisterStudentRow>
)

@Serializable
data class RegisterResultDto(
    val username: String? = null,
    val password: String? = null,
    @SerialName("student_id") val studentId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("total_created") val totalCreated: Int? = null,
    @SerialName("total_skipped") val totalSkipped: Int? = null,
    val created: List<RegisterResultCreated> = emptyList()
)

@Serializable
data class RegisterResultCreated(
    @SerialName("student_id") val studentId: String? = null,
    val name: String? = null,
    val password: String? = null
)

/* ---- create / update request bodies ---- */

@Serializable
data class StudentWriteRequest(
    @SerialName("student_id") val studentId: String? = null,
    val name: String? = null,
    @SerialName("roll_number") val rollNumber: String? = null,
    val email: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    val section: String? = null,
    val password: String? = null,
    // Parent / guardian contact - where parent reports are delivered.
    @SerialName("parent_email") val parentEmail: String? = null,
    @SerialName("parent_email_verified") val parentEmailVerified: Boolean? = null,
    @SerialName("parent_whatsapp") val parentWhatsapp: String? = null,
    @SerialName("parent_whatsapp_verified") val parentWhatsappVerified: Boolean? = null
)

@Serializable
data class TeacherWriteRequest(
    @SerialName("employee_id") val employeeId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    val section: String? = null,
    val password: String? = null
)

@Serializable
data class SubjectWriteRequest(
    val name: String,
    val code: String,
    val branch: String,
    val semester: String,
    @SerialName("teacher_id") val teacherId: Int? = null
)

@Serializable
data class HodWriteRequest(
    val name: String,
    val email: String? = null,
    val branch: String,
    val password: String? = null
)

@Serializable
data class UserUpdateRequest(
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    val section: String? = null,
    val password: String? = null
)

@Serializable
data class AttendanceUpdateRequest(
    val status: String,
    val confidence: Double? = null
)

@Serializable
data class PhotoUploadRequest(val photo: String)

/* ---- admin / reports ---- */

@Serializable
data class AdminTeacherDto(
    val id: Int? = null,
    val name: String? = null,
    @SerialName("employee_id") val employeeId: String? = null,
    val email: String? = null,
    val branch: String? = null
)

@Serializable
data class AdminStudentDto(
    val id: Int? = null,
    @SerialName("student_id") val studentId: String? = null,
    val name: String? = null,
    @SerialName("roll_number") val rollNumber: String? = null,
    val semester: String? = null,
    val section: String? = null
)

@Serializable
data class HodDto(
    val id: Int? = null,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val branch: String? = null,
    val role: String? = null
)

@Serializable
data class DepartmentDetailDto(
    val branch: String? = null,
    val semesters: List<SemesterStatDto> = emptyList()
)

@Serializable
data class SemesterStatDto(
    val semester: String? = null,
    val students: Int? = null,
    @SerialName("attendance_pct") val attendancePct: Double? = null
)

@Serializable
data class LowAttendanceItemDto(
    @SerialName("student_id") val studentId: String? = null,
    val name: String? = null,
    @SerialName("roll_number") val rollNumber: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    val section: String? = null,
    val percentage: Double? = null
)

/* ---- teacher report (GET /api/teacher/reports/attendance) ---- */

@Serializable
data class TeacherReportSummaryDto(
    val students: Int? = null,
    val records: Int? = null,
    val present: Int? = null,
    val absent: Int? = null,
    val pct: Double? = null
)

@Serializable
data class TeacherReportRowDto(
    @SerialName("student_id") val studentId: String? = null,
    val name: String? = null,
    val semester: String? = null,
    val section: String? = null,
    val present: Int? = null,
    val absent: Int? = null,
    val other: Int? = null,
    val total: Int? = null,
    val pct: Double? = null
)

@Serializable
data class TeacherReportDto(
    val branch: String? = null,
    val summary: TeacherReportSummaryDto? = null,
    val rows: List<TeacherReportRowDto> = emptyList()
)

/* ---- analytics by semester ---- */

@Serializable
data class SemesterSubjectStatDto(
    val code: String? = null,
    val name: String? = null,
    val total: Int? = null,
    val present: Int? = null,
    val pct: Double? = null
)

@Serializable
data class SemesterAnalyticsDto(
    val semester: String? = null,
    @SerialName("total_students") val totalStudents: Int? = null,
    val below_threshold: List<LowAttendanceItemDto> = emptyList(),
    val subjects: List<SemesterSubjectStatDto> = emptyList()
)

/* ---- subject report (GET /api/reports/attendance) ---- */

@Serializable
data class SubjectReportRecordDto(
    val date: String? = null,
    val status: String? = null,
    val confidence: Double? = null
)

@Serializable
data class SubjectReportDto(
    val records: Map<String, List<SubjectReportRecordDto>> = emptyMap()
)

/* ---- face analysis preview (POST /api/recognition/analyze) ---- */

@Serializable
data class AnalyzeResultItemDto(
    @SerialName("student_id") val studentId: String? = null,
    @SerialName("db_student_id") val dbStudentId: Int? = null,
    val name: String? = null,
    @SerialName("roll_number") val rollNumber: String? = null,
    val status: String? = null,
    val confidence: Double? = null,
    val matched: Boolean? = null
)

@Serializable
data class AnalyzeResponseDto(
    val results: List<AnalyzeResultItemDto> = emptyList(),
    @SerialName("num_faces") val numFaces: Int? = null,
    val backend: RecognitionStatusDto? = null,
    val preview: Boolean? = null,
    val note: String? = null
)

@Serializable
data class AnalyzeRequest(
    @SerialName("image_base64") val imageBase64: String,
    @SerialName("subject_id") val subjectId: Int? = null,
    val section: String? = null
)

@Serializable
data class RemoveFaceRequest(@SerialName("student_id") val studentId: String)

/* ---- bulk face import (POST /api/faces/bulk/preview and /commit) ---- */

@Serializable
data class BulkRosterErrorDto(val row: Int? = null, val error: String? = null)

@Serializable
data class BulkPhotoErrorDto(val file: String? = null, val error: String? = null)

@Serializable
data class BulkFaceRosterInfoDto(
    val filename: String? = null,
    val columns: List<String> = emptyList(),
    val rows: Int = 0,
    val errors: List<BulkRosterErrorDto> = emptyList()
)

@Serializable
data class BulkFacePhotosInfoDto(
    val filename: String? = null,
    val count: Int = 0,
    val errors: List<BulkPhotoErrorDto> = emptyList()
)

@Serializable
data class BulkFaceMatchInfoDto(
    val assigned: Map<String, String> = emptyMap(),
    val unmatched: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val duplicates: Map<String, List<String>> = emptyMap()
)

@Serializable
data class BulkFaceItemDto(
    @SerialName("student_id") val studentId: String? = null,
    val name: String? = null,
    val row: Int? = null,
    val filename: String? = null,
    @SerialName("extra_photos") val extraPhotos: List<String> = emptyList(),
    @SerialName("in_db") val inDb: Boolean? = null,
    @SerialName("already_registered") val alreadyRegistered: Boolean? = null,
    val thumbnail: String? = null,
    val faces: Int? = null,
    val status: String? = null,
    val reason: String? = null
)

@Serializable
data class BulkFaceSummaryDto(
    val ready: Int? = null,
    val missing_photo: Int? = null,
    val not_in_db: Int? = null,
    val already_registered: Int? = null,
    val invalid: Int? = null,
    val blocked: Int? = null,
    val unvalidated: Int? = null,
    @SerialName("roster_rows") val rosterRows: Int? = null,
    @SerialName("photos_matched") val photosMatched: Int? = null,
    @SerialName("photos_unmatched") val photosUnmatched: Int? = null,
    @SerialName("roster_errors") val rosterErrors: Int? = null,
    val imported: Int? = null,
    val skipped: Int? = null,
    val failed: Int? = null,
    val overwrite: Boolean? = null
)

@Serializable
data class BulkFacePreviewDto(
    val roster: BulkFaceRosterInfoDto? = null,
    val photos: BulkFacePhotosInfoDto? = null,
    val match: BulkFaceMatchInfoDto? = null,
    val items: List<BulkFaceItemDto> = emptyList(),
    val summary: BulkFaceSummaryDto? = null,
    val backend: RecognitionStatusDto? = null,
    val committed: Boolean? = null
)

@Serializable
data class BulkFaceCommitDto(
    val imported: List<BulkFaceItemDto> = emptyList(),
    val skipped: List<BulkFaceItemDto> = emptyList(),
    val failed: List<BulkFaceItemDto> = emptyList(),
    val summary: BulkFaceSummaryDto? = null,
    @SerialName("registered_total") val registeredTotal: Int? = null,
    val backend: RecognitionStatusDto? = null,
    val committed: Boolean? = null
)

/* ---- Parent report delivery (web ParentReportNotify.jsx) ---- */

@Serializable
data class NotifyChannelStatusDto(
    val configured: Boolean? = null,
    val provider: String? = null,
    val missing: List<String>? = null,
    val sender: String? = null
)

@Serializable
data class NotifyCountsDto(
    val total: Int? = null,
    val deliverable: Int? = null,
    @SerialName("no_contact") val noContact: Int? = null,
    val unverified: Int? = null,
    val invalid: Int? = null
)

@Serializable
data class NotifyStudentDto(
    @SerialName("student_id") val studentId: Int? = null,
    val usn: String? = null,
    val name: String? = null,
    val branch: String? = null,
    val semester: String? = null,
    val section: String? = null,
    @SerialName("phone_masked") val phoneMasked: String? = null,
    @SerialName("recipient_masked") val recipientMasked: String? = null,
    @SerialName("recipient_via") val recipientVia: String? = null,
    val verified: Boolean? = null,
    val deliverable: Boolean? = null,
    @SerialName("block_reason") val blockReason: String? = null
)

@Serializable
data class NotifyEligibleDto(
    val channel: String? = null,
    val provider: NotifyChannelStatusDto? = null,
    val channels: Map<String, NotifyChannelStatusDto>? = null,
    val counts: NotifyCountsDto? = null,
    val students: List<NotifyStudentDto>? = null
)

@Serializable
data class NotifyStartRequest(
    @SerialName("student_ids") val studentIds: List<Int> = emptyList(),
    val channel: String = "whatsapp",
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null
)

@Serializable
data class NotifyResultDto(
    @SerialName("student_id") val studentId: Int? = null,
    val usn: String? = null,
    val name: String? = null,
    @SerialName("recipient_masked") val recipientMasked: String? = null,
    @SerialName("recipient_via") val recipientVia: String? = null,
    val outcome: String? = null,
    val detail: String? = null
)

@Serializable
data class NotifyBatchDto(
    @SerialName("batch_id") val batchId: String? = null,
    val total: Int? = null,
    val processed: Int? = null,
    val channel: String? = null,
    val status: String? = null,
    val error: String? = null,
    val results: List<NotifyResultDto>? = null,
    val summary: Map<String, Int>? = null
)
