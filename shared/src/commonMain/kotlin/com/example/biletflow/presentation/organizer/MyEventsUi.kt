package com.example.biletflow.presentation.organizer

import com.example.biletflow.core.designsystem.TextButton
import com.example.biletflow.core.designsystem.Button
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.example.biletflow.presentation.events.formatEventDate

@Composable
fun MyEventsPage(vm: MyEventsViewModel, onCreate: () -> Unit = {}, onPlatformSupport: () -> Unit = {}) {
    val state by vm.state.collectAsState()
    val filtered = state.events.filter { state.filter == null || it.status == state.filter }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = vm::close) { Text(stringResource(Res.string.organizer_back)) }
        Text(stringResource(Res.string.my_events_title), style = MaterialTheme.typography.headlineLarge)
        Button(onClick = onCreate) { Text(stringResource(Res.string.create_event)) }
        TextButton(onClick = onPlatformSupport) { Text(stringResource(Res.string.support_platform_title)) }
        TextButton(onClick = vm::reload, enabled = !state.loading) { Text(stringResource(Res.string.my_events_refresh)) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to Res.string.events_filter_all, "draft" to Res.string.my_events_drafts,
                "published" to Res.string.my_events_published, "cancelled" to Res.string.my_events_cancelled).forEach { (status, label) ->
                FilterChip(selected = state.filter == status, onClick = { vm.filter(status) }, label = { Text(stringResource(label)) })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading -> CircularProgressIndicator()
                state.error -> {
                    Text(stringResource(Res.string.my_events_error))
                    Button(onClick = vm::reload) { Text(stringResource(Res.string.events_retry)) }
                }
                state.events.isEmpty() -> Text(stringResource(Res.string.my_events_empty))
                filtered.isEmpty() -> Text(stringResource(Res.string.my_events_no_matches))
                else -> filtered.forEach { event ->
                    Card(onClick = { vm.select(event.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(event.title, style = MaterialTheme.typography.titleLarge)
                            Text(formatEventDate(event.startTime))
                            event.venueName?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            state.summary[event.id]?.let { s ->
                                val sold = s.capacity?.let { "${s.ticketsSold} / $it" } ?: s.ticketsSold.toString()
                                Text(stringResource(Res.string.my_events_summary, sold,
                                    s.revenue.toString().reversed().chunked(3).joinToString(" ").reversed()))
                            }
                            Text(stringResource(when(event.status) {
                                "draft" -> Res.string.my_events_drafts
                                "published" -> Res.string.my_events_published
                                else -> Res.string.my_events_cancelled
                            }))
                            Text(stringResource(when(event.visibility) {
                                "public" -> Res.string.my_events_public
                                "unlisted" -> Res.string.my_events_unlisted
                                else -> Res.string.my_events_private
                            }))
                        }
                    }
                }
            }
        }
    }
}
