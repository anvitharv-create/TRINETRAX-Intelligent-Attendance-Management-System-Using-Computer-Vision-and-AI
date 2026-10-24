package com.trinetrax.attendance.ui.screens.reports

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import com.trinetrax.attendance.data.model.HodComparisonDto
import com.trinetrax.attendance.data.model.SubjectDto
import com.trinetrax.attendance.ui.components.BannerCard
import com.trinetrax.attendance.ui.components.EmptyRowText
import com.trinetrax.attendance.ui.components.FilterSelect
import com.trinetrax.attendance.ui.components.KeyValueRow
import com.trinetrax.attendance.ui.components.SectionCard
import com.trinetrax.attendance.ui.components.StatCard
import com.trinetrax.attendance.ui.components.WebColors
import com.trinetrax.attendance.ui.components.WebOptions
import com.trinetrax.attendance.ui.theme.MutedText
import com.trinetrax.attendance.ui.theme.SuccessGreen
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun pctLabel(value: Double?): String =
    if (value == null) "—" else "$value%"

private fun subjectLabelOf(subject: SubjectDto): String =
    "${subject.code ?: ""} - ${subject.name ?: ""}"

/**
 * HOD subject & semester comparison - the same report the website shows at
 * /hod/reports. Every number comes from the backend (department scoped), the
 * app never recalculates anything.
 */
@Composable
fun HodComparisonScreen(vm: SessionViewModel) {
    val user = vm.user.value
    val branch = user?.branch.orEmpty()
    val context = LocalContext.current

    var semester by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("") }
    var subjects by remember { mutableStateOf<List<SubjectDto>>(emptyList()) }
    var subjectLabel by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<HodComparisonDto?>(null) }
    var loading by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(branch, semester) {
        subjects = vm.repo.subjects(branch, semester.ifBlank { null }).getOrElse { emptyList() }
        subjectLabel = ""
    }

    val subjectId = subjects.firstOrNull { subjectLabelOf(it) == subjectLabel }?.id

    val filters = listOf(
        semester.ifBlank { null },
        section.ifBlank { null },
        subjectId
    )

    val generate: () -> Unit = {
        loading = true
        message = null
        vm.requestFlowScope().launch {
            vm.repo.hodComparison(
                semester = filters[0] as String?,
                section = filters[1] as String?,
                subjectId = filters[2] as Int?
            ).onSuccess { report = it }
                .onFailure { message = "danger" to vm.repo.humanError(it) }
            loading = false
        }
    }

    fun download(kind: String) {
        val current = report ?: return
        exporting = true
        val fileName = "hod-comparison-${(semester.ifBlank { "all" }).replace(" ", "-")}.$kind"
        val mime = if (kind == "pdf") "application/pdf"
        else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        vm.requestFlowScope().launch {
            val outcome = withContext(Dispatchers.IO) {
                val call = when (kind) {
                    "pdf" -> vm.repo.hodComparisonPdf(
                        semester = filters[0] as String?,
                        section = filters[1] as String?,
                        subjectId = filters[2] as Int?
                    )
                    else -> vm.repo.hodComparisonXlsx(
                        semester = filters[0] as String?,
                        section = filters[1] as String?,
                        subjectId = filters[2] as Int?
                    )
                }
                call.mapCatching { body ->
                    val cacheFile = File(context.cacheDir, fileName)
                    cacheFile.writeBytes(body.bytes())
                    val request = DownloadManager.Request(Uri.fromFile(cacheFile))
                        .setTitle(fileName)
                        .setDescription("HOD comparison report")
                        .setMimeType(mime)
                        .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        .setDestinationInExternalPublicDir(
                            android.os.Environment.DIRECTORY_DOWNLOADS,
                            fileName
                        )
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(request)
                }
            }
            exporting = false
            outcome.onSuccess { message = "success" to "Saved to your Downloads folder." }
                .onFailure { message = "danger" to vm.repo.humanError(it) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                "Subject & Semester Comparison",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        item {
            Text(
                "$branch department · from recorded attendance only",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterSelect(
                    label = "Semester",
                    options = listOf("") + WebOptions.SEMESTERS,
                    value = semester,
                    onSelect = { semester = it },
                    modifier = Modifier.width(180.dp),
                    placeholder = "All semesters"
                )
                FilterSelect(
                    label = "Subject",
                    options = listOf("") + subjects.map { subjectLabelOf(it) },
                    value = subjectLabel,
                    onSelect = { label -> subjectLabel = label },
                    modifier = Modifier.width(220.dp),
                    placeholder = "All subjects"
                )
                FilterSelect(
                    label = "Section",
                    options = listOf("") + WebOptions.SECTIONS,
                    value = section,
                    onSelect = { section = it },
                    modifier = Modifier.width(170.dp),
                    placeholder = "All sections"
                )
                Button(
                    onClick = { generate() },
                    enabled = !loading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WebColors.Primary,
                        contentColor = Color.White
                    )
                ) { Text(if (loading) "Generating…" else "Generate") }
            }
        }

        message?.let { (tone, text) -> item { BannerCard(tone = tone, message = text) } }

        if (report == null && !loading) {
            item { BannerCard(tone = "info", message = "Choose filters and tap Generate.") }
        }

        report?.let { data ->
            val summary = data.summary
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard("Subjects", "${summary?.totalSubjects ?: 0}", Modifier.width(150.dp))
                    StatCard("Semesters", "${summary?.semestersRepresented ?: 0}", Modifier.width(150.dp))
                    StatCard(
                        "Attendance",
                        pctLabel(summary?.attendancePct),
                        Modifier.width(150.dp),
                        accent = SuccessGreen
                    )
                    StatCard(
                        "Below ${summary?.thresholdPct ?: 75.0}%",
                        "${summary?.subjectsBelowThreshold ?: 0}",
                        Modifier.width(170.dp),
                        accent = WebColors.Danger
                    )
                }
            }

            if (data.insights.isNotEmpty()) {
                item {
                    SectionCard("Insights") {
                        data.insights.forEach { note ->
                            Text("• $note", style = MaterialTheme.typography.bodySmall, color = MutedText)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { download("pdf") },
                        enabled = !exporting,
                        border = BorderStroke(1.dp, WebColors.Danger),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WebColors.Danger)
                    ) { Text(if (exporting) "Saving…" else "Export PDF") }
                    OutlinedButton(
                        onClick = { download("xlsx") },
                        enabled = !exporting,
                        border = BorderStroke(1.dp, WebColors.Success),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WebColors.Success)
                    ) { Text("Export Excel") }
                }
            }

            item {
                SectionCard("Subject-wise attendance (${data.subjectRows.size})") {
                    if (data.subjectRows.isEmpty()) {
                        EmptyRowText("No subjects match these filters")
                    } else {
                        data.subjectRows.forEach { row ->
                            val status = row.status ?: "no_data"
                            val colour = when (status) {
                                "below_threshold" -> WebColors.Danger
                                "ok" -> SuccessGreen
                                else -> MutedText
                            }
                            KeyValueRow(
                                "${row.code ?: "—"} · ${row.semester ?: "—"}",
                                "${pctLabel(row.pct)} (${row.records ?: 0} classes)"
                            )
                            Text(
                                when (status) {
                                    "below_threshold" -> "Below threshold"
                                    "ok" -> "Healthy"
                                    else -> "No records"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colour
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            if (data.semesterRows.size >= 2) {
                item {
                    SectionCard("Semester-wise attendance") {
                        data.semesterRows.forEach { row ->
                            KeyValueRow(row.semester ?: "—", pctLabel(row.pct))
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            item {
                SectionCard("Attendance distribution") {
                    data.distribution.forEach { bucket ->
                        KeyValueRow(bucket.bucket ?: "—", "${bucket.count ?: 0} subject(s)")
                    }
                }
            }
        }
    }
}