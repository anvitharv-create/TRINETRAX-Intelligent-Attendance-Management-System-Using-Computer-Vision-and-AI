package com.trinetrax.attendance.data

import com.trinetrax.attendance.core.NetworkModule
import com.trinetrax.attendance.core.SessionManager
import com.trinetrax.attendance.data.model.AnalyzeRequest
import com.trinetrax.attendance.data.model.AttendanceUpdateRequest
import com.trinetrax.attendance.data.model.CaptureRequest
import com.trinetrax.attendance.data.model.HodWriteRequest
import com.trinetrax.attendance.data.model.FaceAngleDto
import com.trinetrax.attendance.data.model.LoginRequest
import com.trinetrax.attendance.data.model.PhotoUploadRequest
import com.trinetrax.attendance.data.model.RegisterBulkRequest
import com.trinetrax.attendance.data.model.RegisterFaceRequest
import com.trinetrax.attendance.data.model.RegisterHodRequest
import com.trinetrax.attendance.data.model.NotifyStartRequest
import com.trinetrax.attendance.data.model.RegisterStudentRequest
import com.trinetrax.attendance.data.model.RegisterTeacherRequest
import com.trinetrax.attendance.data.model.RemoveFaceRequest
import com.trinetrax.attendance.data.model.StudentWriteRequest
import com.trinetrax.attendance.data.model.SubjectWriteRequest
import com.trinetrax.attendance.data.model.TeacherWriteRequest
import com.trinetrax.attendance.data.model.UserUpdateRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import retrofit2.HttpException

/**
 * Single access point for every backend call. Screens never talk to Retrofit
 * directly, so auth handling and error normalisation stay in one place.
 */
class AppRepository(private val session: SessionManager) {

    private val api get() = NetworkModule.api

    suspend fun health() = api.health()

    suspend fun login(username: String, password: String) = runCatching {
        val response = api.login(LoginRequest(username.trim(), password))
        session.saveSession(response.accessToken, response.user)
        response.user
    }

    suspend fun logout() = runCatching {
        runCatching { api.logout() }
        session.clear()
    }

    suspend fun me() = runCatching { api.me() }

    suspend fun updateProfile(fields: Map<String, String?>) = runCatching {
        val updated = api.updateMe(fields)
        session.updateUser(updated)
        updated
    }

    suspend fun authCheck(role: String, branch: String? = null) =
        runCatching { api.authCheck(role, branch) }

    suspend fun registerHod(branch: String) = runCatching { api.registerHod(RegisterHodRequest(branch)) }

    suspend fun registerTeacher(branch: String, name: String) =
        runCatching { api.registerTeacher(RegisterTeacherRequest(branch, name)) }

    suspend fun registerStudent(name: String, branch: String, semester: String, section: String) =
        runCatching { api.registerStudent(RegisterStudentRequest(name, branch, semester, section)) }

    suspend fun registerStudentsBulk(body: RegisterBulkRequest) =
        runCatching { api.registerStudentsBulk(body) }

    suspend fun uploadPhoto(photoDataUrl: String) =
        runCatching { api.uploadPhoto(PhotoUploadRequest(photoDataUrl)) }

    suspend fun refreshMe(): Result<com.trinetrax.attendance.data.model.UserDto> =
        runCatching {
            val updated = api.me()
            session.updateUser(updated)
            updated
        }

    suspend fun updateUser(userId: Int, body: UserUpdateRequest) =
        runCatching { api.updateUser(userId, body) }

    suspend fun students(branch: String? = null, semester: String? = null, section: String? = null) =
        runCatching { api.students(branch = branch, semester = semester, section = section) }

    suspend fun createStudent(body: StudentWriteRequest) = runCatching { api.createStudent(body) }

    suspend fun updateStudent(id: Int, body: StudentWriteRequest) =
        runCatching { api.updateStudent(id, body) }

    suspend fun deleteStudent(id: Int) = runCatching { api.deleteStudent(id) }

    suspend fun teachers(branch: String? = null) = runCatching { api.teachers(branch) }

    suspend fun createTeacher(body: TeacherWriteRequest) = runCatching { api.createTeacher(body) }

    suspend fun updateTeacher(id: Int, body: TeacherWriteRequest) =
        runCatching { api.updateTeacher(id, body) }

    suspend fun deleteTeacher(id: Int) = runCatching { api.deleteTeacher(id) }

    suspend fun subjects(branch: String? = null, semester: String? = null, teacherId: Int? = null) =
        runCatching { api.subjects(branch, semester, teacherId) }

    suspend fun createSubject(body: SubjectWriteRequest) = runCatching { api.createSubject(body) }

    suspend fun deleteSubject(id: Int) = runCatching { api.deleteSubject(id) }

    suspend fun studentAttendance(studentPk: Int) = runCatching { api.studentAttendance(studentPk) }

    suspend fun attendance(
        subjectId: Int? = null,
        date: String? = null,
        startDate: String? = null,
        endDate: String? = null
    ) = runCatching { api.attendance(subjectId, date, startDate, endDate) }

    suspend fun captureAttendance(body: CaptureRequest) = runCatching { api.captureAttendance(body) }

    suspend fun updateAttendance(recordId: Int, body: AttendanceUpdateRequest) =
        runCatching { api.updateAttendance(recordId, body) }

    suspend fun deleteAttendance(recordId: Int) = runCatching { api.deleteAttendance(recordId) }

    suspend fun recognitionStatus() = runCatching { api.recognitionStatus() }

    suspend fun registerFace(studentId: String, name: String?, imageBase64: String) =
        runCatching { api.registerFace(RegisterFaceRequest(studentId, name, imageBase64)) }

    /**
     * Guided three-angle registration (front / slight left / slight right).
     * The server validates every angle and stores them together, so a partial
     * set is never written. `front` doubles as the legacy single image.
     */
    suspend fun registerFaceMulti(studentId: String, name: String?, shots: Map<String, String>) =
        runCatching {
            api.registerFaceMulti(
                RegisterFaceRequest(
                    studentId = studentId,
                    name = name,
                    imageBase64 = shots["front"].orEmpty(),
                    images = listOf("front", "left", "right").map { pose ->
                        FaceAngleDto(pose, shots[pose].orEmpty())
                    }
                )
            )
        }

    suspend fun removeFace(studentId: String) =
        runCatching { api.removeFace(RemoveFaceRequest(studentId)) }

    suspend fun analyzeImage(body: AnalyzeRequest) = runCatching { api.analyzeImage(body) }

    suspend fun teacherDashboard() = runCatching { api.teacherDashboard() }

    suspend fun teacherReport(subjectId: Int? = null, startDate: String? = null, endDate: String? = null) =
        runCatching { api.teacherReport(subjectId, startDate, endDate) }

    suspend fun subjectReport(subjectId: Int) = runCatching { api.subjectReport(subjectId) }

    suspend fun notifyEligible(channel: String) = runCatching { api.notifyEligible(channel) }

    suspend fun notifyStart(body: NotifyStartRequest) = runCatching { api.notifyStart(body) }

    suspend fun notifyBatch(batchId: String) = runCatching { api.notifyBatch(batchId) }

    suspend fun analyticsOverall() = runCatching { api.analyticsOverall() }

    suspend fun semesterAnalytics(semester: String) = runCatching { api.semesterAnalytics(semester) }

    suspend fun adminOverview() = runCatching { api.adminOverview() }

    suspend fun lowAttendance(branch: String) = runCatching { api.lowAttendance(branch) }

    suspend fun adminTeachers(branch: String) = runCatching { api.adminTeachers(branch) }

    suspend fun adminStudents(branch: String) = runCatching { api.adminStudents(branch) }

    suspend fun adminDepartments() = runCatching { api.adminDepartments() }

    suspend fun adminDepartment(branch: String) = runCatching { api.adminDepartment(branch) }

    /** HOD subject & semester comparison, scoped to the caller's own department. */
    suspend fun hodComparison(
        semester: String? = null,
        subjectId: Int? = null,
        section: String? = null,
        startDate: String? = null,
        endDate: String? = null
    ) = runCatching {
        api.hodComparison(semester, subjectId, section, startDate, endDate)
    }

    suspend fun hodComparisonPdf(
        semester: String? = null,
        subjectId: Int? = null,
        section: String? = null,
        startDate: String? = null,
        endDate: String? = null
    ): Result<ResponseBody> = runCatching {
        api.hodComparisonPdf(semester, subjectId, section, startDate, endDate)
    }

    suspend fun hodComparisonXlsx(
        semester: String? = null,
        subjectId: Int? = null,
        section: String? = null,
        startDate: String? = null,
        endDate: String? = null
    ): Result<ResponseBody> = runCatching {
        api.hodComparisonXlsx(semester, subjectId, section, startDate, endDate)
    }

    suspend fun adminHods() = runCatching { api.adminHods() }

    /** Excel export of the currently filtered admin report (same numbers as on screen). */
    suspend fun adminReportsExport(
        reportType: String,
        branch: String? = null,
        semester: String? = null,
        section: String? = null,
        subjectId: Int? = null,
        startDate: String? = null,
        endDate: String? = null
    ): Result<ResponseBody> = runCatching {
        api.adminReportsExport(
            reportType = reportType,
            branch = branch,
            semester = semester,
            section = section,
            subjectId = subjectId,
            startDate = startDate,
            endDate = endDate
        )
    }

    suspend fun createHod(body: HodWriteRequest) = runCatching { api.createHod(body) }

    suspend fun studentReport(startDate: String? = null, endDate: String? = null) =
        runCatching { api.studentReport(startDate, endDate) }

    suspend fun studentReportPdf(startDate: String? = null, endDate: String? = null): Result<ResponseBody> =
        runCatching { api.studentReportPdf(startDate, endDate) }

    suspend fun notifications(userId: Int) = runCatching { api.notifications(userId) }

    suspend fun markNotificationRead(id: Int) = runCatching { api.markNotificationRead(id) }

    suspend fun bulkFacesPreview(
        rosterName: String,
        rosterBytes: ByteArray,
        photosName: String,
        photosBytes: ByteArray
    ) = runCatching {
        api.bulkFacesPreview(
            rosterBytes.toPart("roster", rosterName),
            photosBytes.toPart("photos", photosName)
        )
    }

    suspend fun bulkFacesCommit(
        rosterName: String,
        rosterBytes: ByteArray,
        photosName: String,
        photosBytes: ByteArray,
        overwrite: Boolean
    ) = runCatching {
        api.bulkFacesCommit(
            rosterBytes.toPart("roster", rosterName),
            photosBytes.toPart("photos", photosName),
            okhttp3.RequestBody.create(
                "text/plain".toMediaTypeOrNull(),
                if (overwrite) "true" else "false"
            )
        )
    }

    private fun ByteArray.toPart(name: String, fileName: String): okhttp3.MultipartBody.Part =
        okhttp3.MultipartBody.Part.createFormData(
            name, fileName,
            okhttp3.RequestBody.create("application/octet-stream".toMediaTypeOrNull(), this)
        )

    /** Turns transport failures into the backend's own `detail` message when present. */
    fun humanError(t: Throwable): String = when (t) {
        is HttpException -> {
            val raw = t.response()?.errorBody()?.string()
            parseDetail(raw) ?: "Server error (HTTP ${t.code()})"
        }
        is java.net.UnknownHostException -> "Cannot reach the server. Check that the backend is running."
        is java.net.SocketTimeoutException -> "Request timed out. Try again."
        else -> t.message ?: "Something went wrong"
    }

    private fun parseDetail(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            (element as? kotlinx.serialization.json.JsonObject)
                ?.get("detail")
                ?.let { detail ->
                    (detail as? kotlinx.serialization.json.JsonPrimitive)?.content
                }
        }.getOrNull()
    }
}
