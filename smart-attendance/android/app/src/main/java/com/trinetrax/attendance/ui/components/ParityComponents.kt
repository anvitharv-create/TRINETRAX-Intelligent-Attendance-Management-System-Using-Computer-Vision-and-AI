package com.trinetrax.attendance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trinetrax.attendance.ui.theme.MutedText
import java.time.Instant
import java.time.ZoneOffset

/**
 * Shared widgets that reproduce the web portal's look exactly, so every screen
 * (and every agent-built screen) renders identical badges, banners and filters.
 * Colours are the same hex values used in frontend/src/theme.jsx.
 */
object WebColors {
    val Primary = Color(0xFF0F172A)
    val HeaderEnd = Color(0xFF1E3A5F)
    val Success = Color(0xFF10B981)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val Accent = Color(0xFF0EA5E9)
    val Purple = Color(0xFFA78BFA)
    val Info = Color(0xFF38BDF8)
}

/** Option lists copied from the web theme. */
object WebOptions {
    val BRANCHES = listOf("ISE", "CSE", "ECE", "AIML", "CIVIL")
    val BRANCH_CODES = mapOf("ISE" to "IS", "CSE" to "CS", "ECE" to "EC", "AIML" to "AI", "CIVIL" to "CV")
    val SEMESTERS = listOf("1st Semester", "3rd Semester", "5th Semester", "7th Semester")
    val SECTIONS = listOf("Section A", "Section B")
}

/** Web `Banner` component: info / success / warning / danger tones. */
@Composable
fun BannerCard(tone: String, message: String, modifier: Modifier = Modifier) {
    val (bg, fg, border) = when (tone) {
        "success" -> Triple(Color(0xFFD1FAE5), Color(0xFF065F46), Color(0xFFA7F3D0))
        "warning" -> Triple(Color(0xFFFEF3C7), Color(0xFF92400E), Color(0xFFFDE68A))
        "danger" -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), Color(0xFFFECACA))
        else -> Triple(Color(0xFFE0F2FE), Color(0xFF075985), Color(0xFFBAE6FD))
    }
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = bg) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(message, color = fg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val badgeColors: Map<String, Pair<Color, Color>> = mapOf(
    "present" to (Color(0xFFD1FAE5) to Color(0xFF065F46)),
    "absent" to (Color(0xFFFEE2E2) to Color(0xFF991B1B)),
    "unknown" to (Color(0xFFF1F5F9) to Color(0xFF475569)),
    "late" to (Color(0xFFFEF3C7) to Color(0xFF92400E))
)

/** Web `StatusBadge`: exact labels and colours per attendance status. */
@Composable
fun StatusBadge(status: String?) {
    val key = status?.lowercase() ?: ""
    val (bg, fg) = badgeColors[key] ?: (Color(0xFFF1F5F9) to Color(0xFF475569))
    val label = when (key) {
        "present" -> "Present"
        "absent" -> "Absent"
        "unknown" -> "Unknown"
        "late" -> "Late"
        else -> status ?: ""
    }
    Box(
        Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Dropdown matching the web selects: shows "All {label}s" when nothing chosen. */
@Composable
fun FilterSelect(
    label: String,
    options: List<String>,
    value: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value.ifEmpty { placeholder ?: "All ${label}s" },
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (placeholder != null) {
                DropdownMenuItem(
                    text = { Text(placeholder) },
                    onClick = { onSelect(""); expanded = false }
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
        }
    }
}

/** Label + value select used inside forms (always has a value, no "All" row). */
@Composable
fun FieldSelect(
    label: String,
    options: List<String>,
    value: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/** Date input that opens the native material date picker (web `type="date"`). */
@Composable
fun DateField(
    label: String,
    value: String,
    onDateChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        Text("$label: ${value.ifEmpty { "—" }}")
    }
    if (open) {
        val initial = runCatching {
            value.takeIf { it.isNotEmpty() }?.let {
                Instant.parse("${it}T00:00:00Z").toEpochMilli()
            } ?: System.currentTimeMillis()
        }.getOrDefault(System.currentTimeMillis())
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onDateChange(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        )
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = state)
        }
    }
}

/** Web `window.confirm` replacement. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = com.trinetrax.attendance.ui.theme.DangerRed)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Web `EmptyRow`: centered muted text used inside tables. */
@Composable
fun EmptyRowText(text: String = "No data found", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MutedText, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Web table header: dark gradient row with white column labels. */
@Composable
fun TableHeaderRow(
    columns: List<String>,
    modifier: Modifier = Modifier,
    weights: List<Float>? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(WebColors.Primary, WebColors.HeaderEnd)))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        columns.forEachIndexed { i, title ->
            Text(
                title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(weights?.getOrNull(i) ?: 1f)
            )
        }
    }
}

/** Small labelled value row used inside report cards. */
@Composable
fun ReportRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MutedText, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Colour for a percentage under the app-wide 75% rule. */
fun pctColor(pct: Double?): Color = when {
    pct == null -> MutedText
    pct >= 75.0 -> WebColors.Success
    pct > 0.0 -> WebColors.Warning
    else -> MutedText
}
