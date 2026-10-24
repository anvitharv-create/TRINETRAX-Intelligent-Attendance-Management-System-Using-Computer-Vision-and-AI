package com.trinetrax.attendance.data.api

import com.trinetrax.attendance.data.model.AdminOverviewDto
import com.trinetrax.attendance.data.model.AdminStudentDto
import com.trinetrax.attendance.data.model.AdminTeacherDto
import com.trinetrax.attendance.data.model.AnalyzeRequest
import com.trinetrax.attendance.data.model.AnalyzeResponseDto
import com.trinetrax.attendance.data.model.AnalyticsOverallDto
import com.trinetrax.attendance.data.model.NotifyBatchDto
import com.trinetrax.attendance.data.model.NotifyEligibleDto
import com.trinetrax.attendance.data.model.NotifyStartRequest
import com.trinetrax.attendance.data.model.AttendanceRecordDto
import com.trinetrax.attendance.data.model.AttendanceUpdateRequest
import com.trinetrax.attendance.data.model.BranchStatDto
import com.trinetrax.attendance.data.model.BulkFaceCommitDto
import com.trinetrax.attendance.data.model.BulkFacePreviewDto
import com.trinetrax.attendance.data.model.CaptureRequest
import com.trinetrax.attendance.data.model.CaptureResultDto
import com.trinetrax.attendance.data.model.CheckUserDto
import com.trinetrax.attendance.data.model.DepartmentDetailDto
import com.trinetrax.attendance.data.model.HealthDto
import com.trinetrax.attendance.data.model.HodComparisonDto
import com.trinetrax.attendance.data.model.HodDto
import com.trinetrax.attendance.data.model.HodWriteRequest
import com.trinetrax.attendance.data.model.LoginRequest
import com.trinetrax.attendance.data.model.LoginResponse
import com.trinetrax.attendance.data.model.LowAttendanceItemDto
import com.trinetrax.attendance.data.model.NotificationDto
import com.trinetrax.attendance.data.model.PhotoUploadRequest
import com.trinetrax.attendance.data.model.RecognitionStatusDto
import com.trinetrax.attendance.data.model.RegisterBulkRequest
import com.trinetrax.attendance.data.model.RegisterFaceRequest
import com.trinetrax.attendance.data.model.RegisterFaceResultDto
import com.trinetrax.attendance.data.model.RegisterHodRequest
import com.trinetrax.attendance.data.model.RegisterResultDto
import com.trinetrax.attendance.data.model.RegisterStudentRequest
import com.trinetrax.attendance.data.model.RegisterTeacherRequest
import com.trinetrax.attendance.data.model.RemoveFaceRequest
import com.trinetrax.attendance.data.model.SemesterAnalyticsDto
import com.trinetrax.attendance.data.model.StudentDto
import com.trinetrax.attendance.data.model.StudentReportDto
import com.trinetrax.attendance.data.model.StudentWriteRequest
import com.trinetrax.attendance.data.model.SubjectDto
import com.trinetrax.attendance.data.model.SubjectReportDto
import com.trinetrax.attendance.data.model.SubjectWriteRequest
import com.trinetrax.attendance.data.model.TeacherDashboardDto
import com.trinetrax.attendance.data.model.TeacherDto
import com.trinetrax.attendance.data.model.TeacherReportDto
import com.trinetrax.attendance.data.model.TeacherWriteRequest
import com.trinetrax.attendance.data.model.UserDto
import com.trinetrax.attendance.data.model.UserUpdateRequest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Thin Retrofit mirror of the existing FastAPI backend.
 * No endpoint here is invented: every path already exists and was inspected
 * in backend/main.py before being declared.
 */
interface TrinetraApi {

    @GET("api/health")
    suspend fun health(): HealthDto

    /* ---- auth ---- */
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("api/auth/check")
    suspend fun authCheck(
        @Query("role") role: String,
        @Query("branch") branch: String? = null
    ): CheckUserDto

    @POST("api/auth/register/hod")
    suspend fun registerHod(@Body body: RegisterHodRequest): RegisterResultDto

    @POST("api/auth/register/teacher")
    suspend fun registerTeacher(@Body body: RegisterTeacherRequest): RegisterResultDto

    @POST("api/auth/register/student")
    suspend fun registerStudent(@Body body: RegisterStudentRequest): RegisterResultDto

    @POST("api/auth/register/students/bulk")
    suspend fun registerStudentsBulk(@Body body: RegisterBulkRequest): RegisterResultDto

    @POST("api/auth/logout")
    suspend fun logout(): Map<String, Any?>

    @GET("api/users/me")
    suspend fun me(): UserDto

    @PUT("api/users/me")
    suspend fun updateMe(@Body body: Map<String, String?>): UserDto

    @POST("api/users/me/photo")
    suspend fun uploadPhoto(@Body body: PhotoUploadRequest): Map<String, kotlinx.serialization.json.JsonElement>

    @PUT("api/users/{user_id}")
    suspend fun updateUser(
        @Path("user_id") userId: Int,
        @Body body: UserUpdateRequest
    ): Map<String, kotlinx.serialization.json.JsonElement>

    /* ---- people / curriculum ---- */
    @GET("api/students")
    suspend fun students(
        @Query("branch") branch: String? = null,
        @Query("semester") semester: String? = null,
        @Query("section") section: String? = null,
        @Query("skip") skip: Int? = null,
        @Query("limit") limit: Int? = null
    ): List<StudentDto>

    @GET("api/teachers")
    suspend fun teachers(@Query("branch") branch: String? = null): List<TeacherDto>

    @GET("api/subjects")
    suspend fun subjects(
        @Query("branch") branch: String? = null,
        @Query("semester") semester: String? = null,
        @Query("teacher_id") teacherId: Int? = null
    ): List<SubjectDto>

    @POST("api/subjects")
    suspend fun createSubject(@Body body: SubjectWriteRequest): Map<String, kotlinx.serialization.json.JsonElement>

    @DELETE("api/subjects/{subject_id}")
    suspend fun deleteSubject(@Path("subject_id") subjectId: Int): Map<String, kotlinx.serialization.json.JsonElement>

    @POST("api/students")
    suspend fun createStudent(@Body body: StudentWriteRequest): Map<String, kotlinx.serialization.json.JsonElement>

    @PUT("api/students/{student_id}")
    suspend fun updateStudent(
        @Path("student_id") studentId: Int,
        @Body body: StudentWriteRequest
    ): Map<String, kotlinx.serialization.json.JsonElement>

    @DELETE("api/students/{student_id}")
    suspend fun deleteStudent(@Path("student_id") studentId: Int): Map<String, kotlinx.serialization.json.JsonElement>

    @POST("api/teachers")
    suspend fun createTeacher(@Body body: TeacherWriteRequest): Map<String, kotlinx.serialization.json.JsonElement>

    @PUT("api/teachers/{teacher_id}")
    suspend fun updateTeacher(
        @Path("teacher_id") teacherId: Int,
        @Body body: TeacherWriteRequest
    ): Map<String, kotlinx.serialization.json.JsonElement>

    @DELETE("api/teachers/{teacher_id}")
    suspend fun deleteTeacher(@Path("teacher_id") teacherId: Int): Map<String, kotlinx.serialization.json.JsonElement>

    /* ---- attendance ---- */
    @GET("api/attendance/student/{student_id}")
    suspend fun studentAttendance(@Path("student_id") studentId: Int): List<AttendanceRecordDto>

    @GET("api/attendance")
    suspend fun attendance(
        @Query("subject_id") subjectId: Int? = null,
        @Query("date") date: String? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): List<AttendanceRecordDto>

    @POST("api/attendance/capture")
    suspend fun captureAttendance(@Body body: CaptureRequest): CaptureResultDto

    @PUT("api/attendance/{record_id}")
    suspend fun updateAttendance(
        @Path("record_id") recordId: Int,
        @Body body: AttendanceUpdateRequest
    ): Map<String, kotlinx.serialization.json.JsonElement>

    @DELETE("api/attendance/{record_id}")
    suspend fun deleteAttendance(
        @Path("record_id") recordId: Int
    ): Map<String, kotlinx.serialization.json.JsonElement>

    /* ---- recognition (camera workflows) ---- */
    @GET("api/recognition/status")
    suspend fun recognitionStatus(): RecognitionStatusDto

    @POST("api/recognition/register")
    suspend fun registerFace(@Body body: RegisterFaceRequest): RegisterFaceResultDto

    @POST("api/recognition/register/multi")
    suspend fun registerFaceMulti(@Body body: RegisterFaceRequest): RegisterFaceResultDto

    @POST("api/recognition/remove")
    suspend fun removeFace(@Body body: RemoveFaceRequest): Map<String, kotlinx.serialization.json.JsonElement>

    @POST("api/recognition/analyze")
    suspend fun analyzeImage(@Body body: AnalyzeRequest): AnalyzeResponseDto

    /* ---- role-scoped dashboards / reports ---- */
    @GET("api/teacher/dashboard")
    suspend fun teacherDashboard(): TeacherDashboardDto

    @GET("api/teacher/reports/attendance")
    suspend fun teacherReport(
        @Query("subject_id") subjectId: Int? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): TeacherReportDto

    @GET("api/reports/attendance")
    suspend fun subjectReport(@Query("subject_id") subjectId: Int): SubjectReportDto

    /* ---- parent report delivery (web ParentReportNotify) ---- */
    @GET("api/teacher/reports/notify/eligible")
    suspend fun notifyEligible(@Query("channel") channel: String = "whatsapp"): NotifyEligibleDto

    @POST("api/teacher/reports/notify")
    suspend fun notifyStart(@Body body: NotifyStartRequest): NotifyBatchDto

    @GET("api/teacher/reports/notify/batch/{batchId}")
    suspend fun notifyBatch(@Path("batchId") batchId: String): NotifyBatchDto

    @GET("api/analytics/overall")
    suspend fun analyticsOverall(): AnalyticsOverallDto

    @GET("api/analytics/semester/{semester}")
    suspend fun semesterAnalytics(@Path("semester") semester: String): SemesterAnalyticsDto

    @GET("api/admin/overview")
    suspend fun adminOverview(): AdminOverviewDto

    @GET("api/admin/low-attendance/{branch}")
    suspend fun lowAttendance(@Path("branch") branch: String): List<LowAttendanceItemDto>

    @GET("api/admin/teachers/{branch}")
    suspend fun adminTeachers(@Path("branch") branch: String): List<AdminTeacherDto>

    @GET("api/admin/students/{branch}")
    suspend fun adminStudents(@Path("branch") branch: String): List<AdminStudentDto>

    @GET("api/admin/departments")
    suspend fun adminDepartments(): List<BranchStatDto>

    @GET("api/admin/departments/{branch}")
    suspend fun adminDepartment(@Path("branch") branch: String): DepartmentDetailDto

    @GET("api/admin/hods")
    suspend fun adminHods(): List<HodDto>

    @POST("api/admin/hods")
    suspend fun createHod(@Body body: HodWriteRequest): Map<String, kotlinx.serialization.json.JsonElement>

    /** Excel export of exactly the report currently shown (same backend calculations). */
    @GET("api/admin/reports/export")
    @Streaming
    suspend fun adminReportsExport(
        @Query("report_type") reportType: String,
        @Query("branch") branch: String? = null,
        @Query("semester") semester: String? = null,
        @Query("section") section: String? = null,
        @Query("subject_id") subjectId: Int? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): okhttp3.ResponseBody

    /* ---- student's own report (shared with the web app) ---- */
    @GET("api/student/report")
    suspend fun studentReport(
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): StudentReportDto

    @GET("api/student/report/pdf")
    suspend fun studentReportPdf(
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): okhttp3.ResponseBody

    /* ---- notifications ---- */
    @GET("api/notifications/{user_id}")
    suspend fun notifications(@Path("user_id") userId: Int): List<NotificationDto>

    @PUT("api/notifications/{notification_id}/read")
    suspend fun markNotificationRead(@Path("notification_id") notificationId: Int): Map<String, Any?>

    /* ---- bulk face import (multipart: roster xlsx + photos zip) ---- */

    @Multipart
    @POST("api/faces/bulk/preview")
    suspend fun bulkFacesPreview(
        @Part roster: MultipartBody.Part,
        @Part photos: MultipartBody.Part
    ): BulkFacePreviewDto

    @Multipart
    @POST("api/faces/bulk/commit")
    suspend fun bulkFacesCommit(
        @Part roster: MultipartBody.Part,
        @Part photos: MultipartBody.Part,
        @Part("overwrite") overwrite: RequestBody
    ): BulkFaceCommitDto

    /* ---- HOD subject & semester comparison (shared with the web app) ---- */

    @GET("api/hod/reports/comparison")
    suspend fun hodComparison(
        @Query("semester") semester: String? = null,
        @Query("subject_id") subjectId: Int? = null,
        @Query("section") section: String? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): HodComparisonDto

    @GET("api/hod/reports/comparison/pdf")
    @Streaming
    suspend fun hodComparisonPdf(
        @Query("semester") semester: String? = null,
        @Query("subject_id") subjectId: Int? = null,
        @Query("section") section: String? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): okhttp3.ResponseBody

    @GET("api/hod/reports/comparison/xlsx")
    @Streaming
    suspend fun hodComparisonXlsx(
        @Query("semester") semester: String? = null,
        @Query("subject_id") subjectId: Int? = null,
        @Query("section") section: String? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): okhttp3.ResponseBody
}
