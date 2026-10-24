package com.trinetrax.attendance.ui.screens.notifications

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trinetrax.attendance.data.model.NotificationDto
import com.trinetrax.attendance.ui.components.ErrorState
import com.trinetrax.attendance.ui.components.LoadingView
import com.trinetrax.attendance.viewmodel.SessionViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private data class ToneStyle(val bg: Color, val fg: Color, val icon: String)

private fun toneStyle(type: String?): ToneStyle = when (type) {
    "success" -> ToneStyle(Color(0xFFE8F5E9), Color(0xFF1B5E20), "✅")
    "warning" -> ToneStyle(Color(0xFFFFF3E0), Color(0xFF7A4F01), "⚠️")
    "danger" -> ToneStyle(Color(0xFFFEE2E2), Color(0xFFC53030), "⛔")
    else -> ToneStyle(Color(0xFFE8F4FD), Color(0xFF0B5E8A), "ℹ️")
}

private fun formatTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .parse(iso.take(19))
        SimpleDateFormat("dd/MM/yyyy, hh:mm a", Locale.US).format(parsed!!)
    }.getOrNull() ?: iso.take(16).replace('T', ' ')
}

@Composable
fun NotificationsScreen(vm: SessionViewModel) {
    val user = vm.user.value
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<NotificationDto>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var markingAll by remember { mutableStateOf(false) }

    LaunchedEffect(version, user?.id) {
        loading = true
        val id = user?.id
        if (id == null) {
            error = "No signed-in user."
            loading = false
            return@LaunchedEffect
        }
        val list = vm.repo.notifications(id).getOrElse {
            error = vm.repo.humanError(it); loading = false; return@LaunchedEffect
        }
        items = list
        loading = false
    }

    val unread = items.count { it.read != true }

    when {
        error != null -> ErrorState(error!!, onRetry = { version++ })
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Notifications",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (unread > 0) "$unread unread" else "All caught up 🎉",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF666666)
                        )
                    }
                    OutlinedButton(
                        enabled = !markingAll && unread > 0,
                        onClick = {
                            markingAll = true
                            val unreadIds = items.filter { it.read != true }
                                .mapNotNull { it.id }
                            vm.requestFlowScope().launch {
                                unreadIds.forEach { id -> vm.repo.markNotificationRead(id) }
                                markingAll = false
                                version++
                            }
                        }
                    ) {
                        Text("Mark all as read", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (loading) {
                item { LoadingView("Loading notifications...") }
            } else {
                item {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            com.trinetrax.attendance.ui.components.TableHeaderRow(
                                listOf("", "Notification", "Message", "Time", "")
                            )
                            if (items.isEmpty()) {
                                com.trinetrax.attendance.ui.components.EmptyRowText("")
                            }
                            items.forEach { n ->
                                NotificationRow(
                                    notification = n,
                                    onMarkRead = {
                                        val id = n.id ?: return@NotificationRow
                                        vm.requestFlowScope().launch {
                                            vm.repo.markNotificationRead(id)
                                            version++
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: NotificationDto, onMarkRead: () -> Unit) {
    val unread = notification.read != true
    val tone = toneStyle(notification.type)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (unread) Color(0xFFF8F9FA) else Color.White)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tone.icon, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1.1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notification.title ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (unread) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFE94F37)) {
                        Text(
                            "NEW",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        Text(
            notification.message ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF555555),
            modifier = Modifier.weight(1.4f),
            maxLines = 3
        )
        Text(
            formatTime(notification.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF888888),
            modifier = Modifier.weight(1f)
        )
        if (unread) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF0277BD),
                onClick = onMarkRead,
                modifier = Modifier.padding(start = 6.dp)
            ) {
                Text(
                    "Mark read",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            Spacer(Modifier.width(6.dp))
        }
    }
}
